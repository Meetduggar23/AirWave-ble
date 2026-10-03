package com.airwave.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.random.Random

/**
 * The entire BLE layer for AirWave.
 *
 * Every phone runs both roles at once:
 *  - Peripheral: advertises the AirWave service + runs a GATT server.
 *  - Central: scans for the AirWave service and connects to peers.
 *
 * Wire protocol (UTF-8, fields separated by '|', text is '|' sanitized):
 *   HELLO|sender|group|
 *   BYE|sender|group|
 *   SYS|sender|group|text
 *   MSG|sender|group|senderTime|text
 *   REPLY|sender|group|senderTime|quotedSender|quotedText|text
 *   TYPING|sender|group|1|0
 *   ACK|sender|group|senderTime
 *   IMG_START|sender|group|imgId|totalChunks|senderTime|caption
 *   IMG_CHUNK|sender|group|imgId|index|base64
 *   IMG_END|sender|group|imgId
 *   REACT|sender|group|targetTime|emoji            (empty emoji = remove)
 *   PIN|sender|group|targetTime|1|0                (1 = pin, 0 = unpin)
 *   GNAME|sender|groupId|newName                   (admin only)
 *   GAVATAR|sender|groupId|colorIndex              (admin only)
 *   ROLE|sender|groupId|targetName|admin|member     (admin only)
 *   KICK|sender|groupId|targetName                 (admin only)
 *   ROSTER|host|groupId|entries                    (host -> members, v3.2.5)
 *      entries = "name\u0002role" joined by "\u0001"; keeps member screens
 *      showing the full member list (members never hear each other's HELLOs).
 *   group is "dm" for 1-to-1 chats, otherwise the group id.
 *   senderTime is the sender's System.currentTimeMillis at send time; ACK
 *   echoes it back so the sender can match the exact message (✓/✓✓ ticks).
 *   Images are chunked (360-char base64 chunks, ~25ms apart) because the
 *   MTU is 512. Both phones must run v3.0+ for images/reactions/pins/roles;
 *   older builds ignore the new frame types.
 *
 * Group chat is a star topology: the group host's GATT server accepts many
 * centrals and relays each MSG to every other connected device.
 */
@SuppressLint("MissingPermission") // Permissions are requested in the activities
object AirWaveBle {

    // ---------------- protocol ----------------
    private const val T_MSG = "MSG"
    private const val T_HELLO = "HELLO"
    private const val T_BYE = "BYE"
    private const val T_SYS = "SYS"
    private const val T_ACK = "ACK"
    private const val T_TYPING = "TYPING"
    private const val T_REPLY = "REPLY"
    // v3.0 additive frames
    private const val T_IMG_START = "IMG_START"
    private const val T_IMG_CHUNK = "IMG_CHUNK"
    private const val T_IMG_END = "IMG_END"
    private const val T_REACT = "REACT"
    private const val T_PIN = "PIN"
    private const val T_GNAME = "GNAME"
    private const val T_GAVATAR = "GAVATAR"
    private const val T_ROLE = "ROLE"
    private const val T_KICK = "KICK"
    private const val T_ROSTER = "ROSTER"
    const val DM = "dm"

    private val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-9abc-def012345678")
    private val CHAT_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-9abc-def012345679")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val MFG_ID = 0xABCD
    private const val MAX_MTU = 517

    // ---------------- models ----------------
    data class Peer(val name: String, val address: String, val groupId: String?, val rssi: Int)
    data class ReplyInfo(val sender: String, val text: String)
    data class ChatMessage(
        val sender: String,
        var text: String,
        val mine: Boolean,
        val time: Long,
        val system: Boolean = false,
        val id: String = UUID.randomUUID().toString(),
        var delivered: Boolean = false,
        val replyTo: ReplyInfo? = null,
        // v3.0
        val isImage: Boolean = false,
        val imgId: String? = null,
        val imgTotal: Int = 0,
        var imgReceived: Int = 0,
        var imagePath: String? = null,
        val caption: String = "",
        val reactions: MutableMap<String, String> = mutableMapOf(),
        var pinned: Boolean = false,
        var failed: Boolean = false
    )
    data class HistoryEvent(val time: Long, val text: String)

    interface Listener {
        fun onPeerFound(peer: Peer) {}
        fun onStatus(text: String) {}
        fun onConnectionChanged(address: String, connected: Boolean) {}
        fun onMessage(convId: String, msg: ChatMessage) {}
        fun onMembersChanged(groupId: String, members: List<String>) {}
        fun onMessageUpdated(convId: String) {}
        fun onUnreadChanged() {}
        fun onTyping(convId: String, name: String?) {}
        // v3.0
        fun onRssi(rssi: Int) {}
        fun onKicked(groupId: String) {}
        fun onGroupChanged(groupId: String) {}
    }

    // ---------------- state ----------------
    private lateinit var appContext: Context
    private lateinit var adapter: BluetoothAdapter
    var listener: Listener? = null

    var myName: String = "Android"
        private set

    private var online = false
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
    private var gattClient: BluetoothGatt? = null
    private var clientChar: BluetoothGattCharacteristic? = null

    private var advertising = false
    private var scanning = false
    private var clientReady = false
    private var mtuAttempts = 0
    private val gattHandler = Handler(Looper.getMainLooper())

    private val serverConnections = LinkedHashSet<BluetoothDevice>()
    private val memberNames = LinkedHashMap<String, String>() // address -> name

    private val peers = LinkedHashMap<String, Peer>()
    private val conversations = LinkedHashMap<String, MutableList<ChatMessage>>()
    private val unread = LinkedHashMap<String, Int>()

    // v3.0 state (all session-only by design)
    private val sessionHistory = mutableListOf<HistoryEvent>()
    private val lastSeen = LinkedHashMap<String, Long>() // peer address -> last frame time
    private val groupNames = LinkedHashMap<String, String>() // groupId -> display name
    private val groupAvatarIdx = LinkedHashMap<String, Int>() // groupId -> AvatarUtil color index
    private val groupRoles = LinkedHashMap<String, MutableMap<String, String>>() // groupId -> name -> admin|member
    private val lastInviteNotify = LinkedHashMap<String, Long>() // groupId -> last invite notif time
    private val incomingImages = LinkedHashMap<String, IncomingImg>()
    private var lastRssi: Int = Int.MIN_VALUE

    private data class IncomingImg(
        val imgId: String,
        val total: Int,
        val chunks: Array<String?>,
        var received: Int,
        val convId: String,
        val msgId: String,
        val senderTime: Long
    )

    var hostingGroupId: String? = null
        private set
    private var joinedGroupId: String? = null
    private var pendingJoinGroup: String? = null
    private var clientPeerAddress: String? = null

    // v3.2.5 identity fix: a phone's BLE address depends on its ROLE — the
    // address we scan/dial (their advertiser) is usually NOT the address our
    // GATT server sees when they dial us (their central). Without mapping the
    // two together, one person ends up as two conversations: the open chat
    // watches the empty one while notifications fire for the other. Key = the
    // address our server observes, value = the canonical (scanned/dialed) one.
    private val addrAliases = LinkedHashMap<String, String>()

    // v3.2.5: member-side roster (names of everyone in the joined group).
    private val rosterNames = LinkedHashMap<String, List<String>>()

    /** Canonical (UI-facing) address for a device address. */
    private fun resolveAddr(address: String): String = addrAliases[address] ?: address

    /** The real device address a canonical (scanned/dialed) address maps to, if any. */
    private fun realAddrOf(canonical: String): String =
        addrAliases.entries.firstOrNull { it.value == canonical }?.key ?: canonical

    /** [convId] with its DM address canonicalized (no-op for group convs). */
    fun canonicalConvId(convId: String): String =
        if (convId.startsWith("$DM:")) dmId(resolveAddr(convId.removePrefix("$DM:"))) else convId

    private fun bindAddrAlias(real: String, canonical: String) {
        if (real == canonical || addrAliases.containsKey(real)) return
        addrAliases[real] = canonical
        // Frames may already have landed under the raw address; re-key them so
        // the conversation merges into the one the UI has been watching.
        val oldConv = dmId(real)
        val old = conversations[oldConv]
        if (old != null) {
            conversations.remove(oldConv)
            val newConv = dmId(canonical)
            val target = conversations.getOrPut(newConv) { mutableListOf() }
            for (m in old) if (target.none { it.id == m.id }) target.add(m)
            val moved = unread.remove(oldConv)
            if (moved != null) unread[newConv] = (unread[newConv] ?: 0) + moved
            listener?.onMessageUpdated(newConv)
        }
    }

    /**
     * When a peer dials us, our server sees their central address, which is
     * usually different from the address we scanned them at. If exactly one
     * scanned peer carries this HELLO name, bind the two addresses so their
     * messages land in the conversation the user already has open.
     */
    private fun bindHelloAlias(realAddr: String, senderName: String) {
        if (addrAliases.containsKey(realAddr)) return
        val matches = peers.values.filter {
            it.name.equals(senderName, ignoreCase = true) && it.address != realAddr
        }
        if (matches.size == 1) bindAddrAlias(realAddr, matches[0].address)
    }

    /** Host pushes the full member list (names + roles) to every member. */
    private fun broadcastRoster(groupId: String) {
        if (hostingGroupId != groupId) return
        val roles = groupRoles[groupId]
        val entries = mutableListOf("$myName\u0002admin")
        for (name in memberNames.values) {
            val clean = name.replace("\u0001", "/").replace("\u0002", "/")
            entries.add("$clean\u0002${roles?.get(name) ?: "member"}")
        }
        notifyAll(
            frame(T_ROSTER, myName, groupId, entries.joinToString("\u0001"))
                .toByteArray(Charsets.UTF_8),
            exclude = null
        )
    }

    /** Conversation currently open on screen; suppresses notifications for it. */
    var foregroundConv: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = mgr.adapter
    }

    fun setName(name: String) {
        myName = name.ifBlank { Build.MODEL ?: "Android" }
        try {
            adapter.name = myName
        } catch (_: Exception) {
        }
    }

    fun isOnline(): Boolean = online
    fun isBluetoothOn(): Boolean = adapter.isEnabled

    fun dmId(address: String) = "$DM:$address"
    fun groupConv(groupId: String) = "group:$groupId"

    fun peersList(): List<Peer> = peers.values.toList()
    fun messages(convId: String): List<ChatMessage> = conversations[convId]?.toList() ?: emptyList()

    /** Full member list. The host derives it from live connections; members
     *  use the roster the host broadcasts (ROSTER frame, v3.2.5) — members
     *  never hear each other's HELLOs, so their memberNames map is empty. */
    fun groupMembers(): List<String> {
        if (hostingGroupId != null) return listOf(myName) + memberNames.values
        joinedGroupId?.let { gid -> rosterNames[gid]?.let { return it } }
        return listOf(myName) + memberNames.values
    }

    /** Best-known display name for a device address (handshake name, else scan name). */
    fun nameFor(address: String): String =
        memberNames[address]
            ?: memberNames[realAddrOf(address)]
            ?: peers[address]?.name
            ?: address

    fun connectionSummary(): String {
        if (!online) return appContext.getString(R.string.not_connected)
        val gid = hostingGroupId
        if (gid != null) {
            val n = groupMembers().size
            return appContext.getString(R.string.hosting_group, gid, n)
        }
        val jid = joinedGroupId
        if (jid != null) return appContext.getString(R.string.in_group, jid)
        val addr = clientPeerAddress
        if (gattClient != null && addr != null) {
            val name = memberNames[addr] ?: peers[addr]?.name ?: addr
            return appContext.getString(R.string.connected_to, name)
        }
        if (serverConnections.isNotEmpty()) {
            val d = serverConnections.first()
            val name = memberNames[d.address] ?: peers[d.address]?.name ?: d.address
            return appContext.getString(R.string.connected_to, name)
        }
        return appContext.getString(R.string.not_connected)
    }

    // ---------------- lifecycle ----------------

    fun goOnline(): Boolean {
        if (online) return true
        if (!adapter.isEnabled) return false
        // Apply the username to the adapter now that permissions are granted;
        // calling this earlier (before BLUETOOTH_CONNECT) silently fails.
        setName(Prefs.name)
        online = true
        startServer()
        startAdvertising()
        startScanning()
        return true
    }

    fun goOffline() {
        online = false
        stopScanning()
        stopAdvertising()
        disconnectClient()
        for (d in serverConnections.toList()) {
            try {
                gattServer?.cancelConnection(d)
            } catch (_: Exception) {
            }
        }
        serverConnections.clear()
        memberNames.clear()
        addrAliases.clear()
        rosterNames.clear()
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        serverChar = null
        hostingGroupId = null
        joinedGroupId = null
        pendingJoinGroup = null
        peers.clear()
        conversations.clear()
        unread.clear()
        listener?.onStatus(appContext.getString(R.string.offline))
    }

    fun setDiscoverable(on: Boolean) {
        Prefs.discoverable = on
        if (online) {
            stopAdvertising()
            startAdvertising()
        }
    }

    fun clearSession() {
        conversations.clear()
        peers.clear()
        memberNames.clear()
        unread.clear()
        addrAliases.clear()
        rosterNames.clear()
        listener?.onStatus(appContext.getString(R.string.session_cleared))
    }

    // ---------------- group ----------------

    /** Start hosting a group. Returns the group code. */
    fun startGroup(): String {
        val gid = Random.nextBytes(4).joinToString("") { "%02X".format(it) }.take(6)
        hostingGroupId = gid
        groupRoles[gid] = mutableMapOf(myName to "admin")
        stopAdvertising()
        startAdvertising()
        logHistory("Started group $gid")
        return gid
    }

    fun stopGroup() {
        for (d in serverConnections.toList()) {
            try {
                gattServer?.cancelConnection(d)
            } catch (_: Exception) {
            }
        }
        serverConnections.clear()
        memberNames.clear()
        hostingGroupId = null
        stopAdvertising()
        startAdvertising()
    }

    fun joinGroup(peer: Peer) {
        pendingJoinGroup = peer.groupId
        peer.groupId?.let { gid ->
            groupRoles.getOrPut(gid) { mutableMapOf() }[myName] = "member"
            logHistory("Joining group $gid")
        }
        connect(peer.address)
    }

    /** Leave whatever group involvement exists (host or member). */
    fun leaveGroup() {
        val gid = hostingGroupId ?: joinedGroupId
        if (hostingGroupId != null) {
            stopGroup()
        } else {
            if (clientReady) {
                val g = gattClient
                val c = clientChar
                val joined = joinedGroupId
                if (g != null && c != null && joined != null) {
                    writeCompat(g, c, frame(T_BYE, myName, joined, "").toByteArray(Charsets.UTF_8))
                }
            }
            joinedGroupId = null
            pendingJoinGroup = null
            disconnectClient()
        }
        if (gid != null) logHistory("Left group $gid")
    }

    // ---------------- connections ----------------

    fun connect(address: String) {
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            return
        }
        try {
            gattClient?.close()
        } catch (_: Exception) {
        }
        clearWriteQueue()
        clientReady = false
        mtuAttempts = 0
        clientChar = null
        clientPeerAddress = address
        stopScanning()
        listener?.onStatus(appContext.getString(R.string.connecting))
        gattClient = device.connectGatt(appContext, false, gattClientCallback)
    }

    fun disconnectClient() {
        clientPeerAddress = null
        clientReady = false
        clearWriteQueue()
        try {
            gattClient?.disconnect()
        } catch (_: Exception) {
        }
    }

    /**
     * Shared transport decision for all outgoing frames.
     * Routing is identical to the original sendChat logic:
     *  - hosted group: relay to every server connection (notifyAll), exactly once
     *  - joined group: write to the host through the outgoing client connection
     *  - 1-to-1: exactly one path — the outgoing client connection if it is for
     *    the target, otherwise the target's connection to our GATT server
     */
    private fun transport(group: String, target: String, bytes: ByteArray): Boolean {
        var sent = false
        if (group != DM) {
            if (hostingGroupId == group) {
                // I host this group: relay to everyone on my server, exactly once.
                if (serverConnections.isNotEmpty()) {
                    notifyAll(bytes, exclude = null)
                    sent = true
                }
            } else {
                // I'm a member: send to the host through my client connection.
                val g = gattClient
                val c = clientChar
                if (g != null && c != null && clientReady) {
                    writeCompat(g, c, bytes)
                    sent = true
                }
            }
        } else {
            val g = gattClient
            val c = clientChar
            if (g != null && c != null && clientReady &&
                resolveAddr(clientPeerAddress ?: "") == resolveAddr(target)
            ) {
                writeCompat(g, c, bytes)
                sent = true
            } else {
                // v3.2.5: the target may be the canonical (scanned) address while
                // the live server connection is keyed by their central address.
                val dev = serverConnections.firstOrNull {
                    it.address == target || addrAliases[it.address] == target
                }
                val server = gattServer
                val char = serverChar
                if (dev != null && server != null && char != null) {
                    notifyCompat(server, dev, char, bytes)
                    sent = true
                }
            }
        }
        return sent
    }

    /** Send a chat message in a conversation. Returns false if nothing is connected. */
    fun sendChat(convId: String, text: String): Boolean {
        val clean = text.replace('|', '/').trim()
        if (clean.isEmpty()) return false
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val ts = now()
        // The sender's timestamp rides in the frame so the ACK can echo it
        // back for an exact match on this phone (delivery ticks).
        val frameStr = "$T_MSG|${s(myName)}|${s(group)}|$ts|${s(clean)}"
        val target = if (isGroup) "" else convId.removePrefix("$DM:")
        val sent = transport(group, target, frameStr.toByteArray(Charsets.UTF_8))
        // v3.0: keep failed messages in the list so the user can retry them.
        addMessage(convId, ChatMessage(myName, clean, mine = true, time = ts, failed = !sent))
        if (!sent) logHistory("Couldn't send: link not ready yet")
        return sent
    }

    /** Broadcast typing state. Never stored, never notified. */
    fun sendTyping(convId: String, typing: Boolean) {
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else convId.removePrefix("$DM:")
        val bytes = frame(T_TYPING, myName, group, if (typing) "1" else "0")
            .toByteArray(Charsets.UTF_8)
        transport(group, target, bytes)
    }

    /** Send a reply quoting another message. Returns false if not connected. */
    fun sendReply(convId: String, quotedSender: String, quotedText: String, text: String): Boolean {
        val clean = text.replace('|', '/').trim()
        if (clean.isEmpty()) return false
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else convId.removePrefix("$DM:")
        val ts = now()
        val quote = s(quotedText.take(120))
        val payload =
            "$T_REPLY|${s(myName)}|${s(group)}|$ts|${s(quotedSender)}|$quote|$clean"
        val sent = transport(group, target, payload.toByteArray(Charsets.UTF_8))
        addMessage(
            convId,
            ChatMessage(
                myName, clean, mine = true, time = ts,
                replyTo = ReplyInfo(quotedSender, quote), failed = !sent
            )
        )
        return sent
    }

    // ---------------- v3.0: images ----------------

    private const val IMG_MAX_PX = 960
    private const val IMG_QUALITY = 65
    private const val IMG_CHUNK = 360
    private const val IMG_MAX_CHUNKS = 1000
    private val imgHandler = Handler(Looper.getMainLooper())

    private fun imagesDir(): File = File(appContext.filesDir, "images").apply { mkdirs() }

    private fun saveJpeg(imgId: String, bmp: Bitmap): String? {
        return try {
            val f = File(imagesDir(), "$imgId.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, IMG_QUALITY, it) }
            f.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleDown(bmp: Bitmap, maxPx: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxPx && h <= maxPx) return bmp
        val ratio = maxPx / maxOf(w, h).toFloat()
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private fun jpegBytes(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        scaleDown(bmp, IMG_MAX_PX).compress(Bitmap.CompressFormat.JPEG, IMG_QUALITY, out)
        return out.toByteArray()
    }

    /**
     * Send an image in a conversation. Compresses to max 960px JPEG q65,
     * chunks into 360-char base64 frames paced ~25ms apart. Returns false
     * (and marks the message failed for retry) if the first frame can't go.
     */
    fun sendImage(convId: String, bitmap: Bitmap, caption: String): Boolean {
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else convId.removePrefix("$DM:")
        val ts = now()
        val imgId = UUID.randomUUID().toString()
        val cap = s(caption.take(120))
        val b64 = try {
            Base64.encodeToString(jpegBytes(bitmap), Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        val chunks = b64.chunked(IMG_CHUNK)
        if (chunks.isEmpty() || chunks.size > IMG_MAX_CHUNKS) return false
        val path = try {
            saveJpeg(imgId, scaleDown(bitmap, IMG_MAX_PX))
        } catch (_: Exception) {
            null
        }
        val msg = ChatMessage(
            myName, "", mine = true, time = ts,
            isImage = true, imgId = imgId, imgTotal = chunks.size,
            imgReceived = chunks.size, imagePath = path, caption = cap
        )
        val start =
            "$T_IMG_START|${s(myName)}|${s(group)}|$imgId|${chunks.size}|$ts|$cap"
                .toByteArray(Charsets.UTF_8)
        val sent = transport(group, target, start)
        msg.failed = !sent
        addMessage(convId, msg)
        if (sent) {
            logHistory("Sent image to ${convTitle(convId)}")
            transmitImageChunks(group, target, imgId, chunks, convId, msg.id)
        }
        return sent
    }

    private fun transmitImageChunks(
        group: String, target: String, imgId: String,
        chunks: List<String>, convId: String, msgId: String
    ) {
        var idx = 0
        var failed = false
        val run = object : Runnable {
            override fun run() {
                if (idx < chunks.size) {
                    val frame =
                        "$T_IMG_CHUNK|${s(myName)}|${s(group)}|$imgId|$idx|${chunks[idx]}"
                            .toByteArray(Charsets.UTF_8)
                    if (!transport(group, target, frame)) failed = true
                    idx++
                    imgHandler.postDelayed(this, 25)
                } else {
                    val end = "$T_IMG_END|${s(myName)}|${s(group)}|$imgId"
                        .toByteArray(Charsets.UTF_8)
                    if (!transport(group, target, end)) failed = true
                    if (failed) {
                        messageById(convId, msgId)?.let {
                            it.failed = true
                            listener?.onMessageUpdated(convId)
                        }
                    }
                }
            }
        }
        imgHandler.post(run)
    }

    /** Retry a failed outgoing message (text, reply or image). */
    fun resendMessage(convId: String, msgId: String): Boolean {
        val msg = messageById(convId, msgId) ?: return false
        if (!msg.mine || !msg.failed) return false
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else convId.removePrefix("$DM:")
        if (msg.isImage) {
            val path = msg.imagePath ?: return false
            val bmp = BitmapFactory.decodeFile(path) ?: return false
            val b64 = try {
                Base64.encodeToString(jpegBytes(bmp), Base64.NO_WRAP)
            } catch (_: Exception) {
                return false
            }
            val chunks = b64.chunked(IMG_CHUNK)
            if (chunks.isEmpty() || chunks.size > IMG_MAX_CHUNKS) return false
            val start =
                "$T_IMG_START|${s(myName)}|${s(group)}|${msg.imgId}|${chunks.size}|${msg.time}|${s(msg.caption)}"
                    .toByteArray(Charsets.UTF_8)
            val sent = transport(group, target, start)
            if (sent) {
                msg.failed = false
                listener?.onMessageUpdated(convId)
                transmitImageChunks(group, target, msg.imgId ?: "", chunks, convId, msg.id)
            }
            return sent
        }
        val payload = if (msg.replyTo != null) {
            "$T_REPLY|${s(myName)}|${s(group)}|${msg.time}|${s(msg.replyTo.sender)}|${s(msg.replyTo.text)}|${s(msg.text)}"
        } else {
            "$T_MSG|${s(myName)}|${s(group)}|${msg.time}|${s(msg.text)}"
        }
        val sent = transport(group, target, payload.toByteArray(Charsets.UTF_8))
        msg.failed = !sent
        listener?.onMessageUpdated(convId)
        return sent
    }

    // ---------------- v3.0: reactions & pins ----------------

    /** React to a message (empty emoji removes my reaction). No ACK. */
    fun sendReaction(convId: String, targetTime: Long, emoji: String) {
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else convId.removePrefix("$DM:")
        applyReaction(convId, targetTime, myName, emoji)
        val frame = "$T_REACT|${s(myName)}|${s(group)}|$targetTime|$emoji"
            .toByteArray(Charsets.UTF_8)
        transport(group, target, frame)
    }

    private fun applyReaction(convId: String, targetTime: Long, name: String, emoji: String) {
        val msg = conversations[convId]?.firstOrNull { it.time == targetTime } ?: return
        if (emoji.isEmpty()) msg.reactions.remove(name) else msg.reactions[name] = emoji
        listener?.onMessageUpdated(convId)
    }

    /** Pin/unpin a message. No ACK. */
    fun sendPin(convId: String, targetTime: Long, pin: Boolean) {
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else convId.removePrefix("$DM:")
        applyPin(convId, targetTime, pin)
        val frame = "$T_PIN|${s(myName)}|${s(group)}|$targetTime|${if (pin) "1" else "0"}"
            .toByteArray(Charsets.UTF_8)
        transport(group, target, frame)
    }

    private fun applyPin(convId: String, targetTime: Long, pin: Boolean) {
        val msg = conversations[convId]?.firstOrNull { it.time == targetTime } ?: return
        msg.pinned = pin
        listener?.onMessageUpdated(convId)
    }

    fun pinnedMessages(convId: String): List<ChatMessage> =
        conversations[convId]?.filter { it.pinned } ?: emptyList()

    // ---------------- v3.0: group admin ----------------

    /** Display name for a group (renamable), falling back to "Group <id>". */
    fun groupDisplayName(groupId: String): String =
        groupNames[groupId] ?: appContext.getString(R.string.group_title, groupId)

    fun groupAvatarColor(groupId: String): Int {
        val idx = groupAvatarIdx[groupId]
        return if (idx != null) {
            AvatarUtil.AVATAR_COLORS[idx % AvatarUtil.AVATAR_COLORS.size]
        } else {
            AvatarUtil.colorFor("Group $groupId")
        }
    }

    fun groupRoleOf(groupId: String, name: String): String =
        groupRoles[groupId]?.get(name) ?: "member"

    /** True if I'm the host or a promoted admin of this group. */
    fun amIAdmin(groupId: String): Boolean =
        hostingGroupId == groupId || groupRoleOf(groupId, myName) == "admin"

    private fun adminFrameOk(groupId: String, sender: String): Boolean =
        sender == myName || groupRoleOf(groupId, sender) == "admin"

    fun sendGroupName(groupId: String, newName: String) {
        val clean = newName.replace('|', '/').trim().take(40)
        if (clean.isEmpty()) return
        groupNames[groupId] = clean
        listener?.onGroupChanged(groupId)
        val frame = "$T_GNAME|${s(myName)}|${s(groupId)}|$clean".toByteArray(Charsets.UTF_8)
        transport(groupId, "", frame)
    }

    fun sendGroupAvatar(groupId: String, colorIndex: Int) {
        groupAvatarIdx[groupId] = colorIndex
        listener?.onGroupChanged(groupId)
        val frame = "$T_GAVATAR|${s(myName)}|${s(groupId)}|$colorIndex".toByteArray(Charsets.UTF_8)
        transport(groupId, "", frame)
    }

    fun sendRole(groupId: String, targetName: String, role: String) {
        val r = if (role == "admin") "admin" else "member"
        groupRoles.getOrPut(groupId) { mutableMapOf() }[targetName] = r
        listener?.onMembersChanged(groupId, groupMembers())
        val frame = "$T_ROLE|${s(myName)}|${s(groupId)}|${s(targetName)}|$r"
            .toByteArray(Charsets.UTF_8)
        transport(groupId, "", frame)
        broadcastRoster(groupId)
        logHistory("$targetName is now $r in group $groupId")
    }

    fun sendKick(groupId: String, targetName: String) {
        val frame = "$T_KICK|${s(myName)}|${s(groupId)}|${s(targetName)}"
            .toByteArray(Charsets.UTF_8)
        transport(groupId, "", frame)
        // Drop their connection; the disconnect callback posts the "left" note.
        val addr = memberNames.entries.firstOrNull { it.value == targetName }?.key
        if (addr != null) {
            serverConnections.firstOrNull { it.address == addr }?.let { d ->
                try {
                    gattServer?.cancelConnection(d)
                } catch (_: Exception) {
                }
            }
        }
        logHistory("$targetName was removed from group $groupId")
    }

    // ---------------- v3.0: presence, mute, history ----------------

    /** True if we have a live link to this peer (either direction).
     *  v3.2.5: alias-aware — the link may be known under either address form. */
    fun isPeerConnected(address: String): Boolean {
        val canon = resolveAddr(address)
        if (gattClient != null && clientReady &&
            resolveAddr(clientPeerAddress ?: "") == canon
        ) return true
        return serverConnections.any { resolveAddr(it.address) == canon }
    }

    /** True when a GATT link (or a link attempt in progress) exists to [address],
     *  even if it is not fully ready yet. Used to avoid restarting a handshake
     *  that is already underway. */
    fun hasLinkTo(address: String): Boolean {
        val canon = resolveAddr(address)
        if (gattClient != null && resolveAddr(clientPeerAddress ?: "") == canon) return true
        return serverConnections.any { resolveAddr(it.address) == canon }
    }

    fun lastSeenLabel(address: String): String {
        if (isPeerConnected(address)) return appContext.getString(R.string.active_now)
        val t = lastSeen[address] ?: return appContext.getString(R.string.not_connected)
        val now = now()
        val day = 24 * 60 * 60 * 1000L
        val label = when {
            now - t < day && isSameDay(t, now) ->
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
            now - t < 2 * day -> appContext.getString(R.string.yesterday)
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
        }
        return appContext.getString(R.string.last_seen, label)
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = a
        val da = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val ya = cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = b
        return da == cal.get(java.util.Calendar.DAY_OF_YEAR) && ya == cal.get(java.util.Calendar.YEAR)
    }

    /** RSSI helpers for the chat header signal indicator. */
    fun hasClientTo(address: String): Boolean =
        gattClient != null && clientReady &&
            resolveAddr(clientPeerAddress ?: "") == resolveAddr(address)

    fun requestRssi() {
        try {
            gattClient?.readRemoteRssi()
        } catch (_: Exception) {
        }
    }

    fun setMuted(convId: String, muted: Boolean) {
        val set = Prefs.mutedConvs
        if (muted) set.add(convId) else set.remove(convId)
        Prefs.mutedConvs = set
    }

    fun isMuted(convId: String): Boolean = Prefs.mutedConvs.contains(convId)

    private fun logHistory(text: String) {
        sessionHistory.add(HistoryEvent(now(), text))
        if (sessionHistory.size > 200) sessionHistory.removeAt(0)
    }

    fun history(): List<HistoryEvent> = sessionHistory.toList()

    private fun convTitle(convId: String): String =
        if (convId.startsWith("group:")) groupDisplayName(convId.removePrefix("group:"))
        else nameFor(convId.removePrefix("$DM:"))

    fun messageById(convId: String, id: String): ChatMessage? =
        conversations[convId]?.firstOrNull { it.id == id }

    private fun displayText(msg: ChatMessage): String =
        if (msg.isImage) "\uD83D\uDCF7 ${msg.caption.ifBlank { "Photo" }}" else msg.text

    // ---------------- peripheral: advertising + GATT server ----------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            listener?.onStatus("Advertising failed ($errorCode)")
        }
    }

    private fun startAdvertising() {
        if (!Prefs.discoverable && hostingGroupId == null) return
        advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Keep the primary packet tiny: service UUID + optional group beacon.
        // The device name goes in the scan response instead.
        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .apply {
                hostingGroupId?.let { addManufacturerData(MFG_ID, it.toByteArray(Charsets.UTF_8)) }
            }
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (_: Exception) {
        }
    }

    private fun stopAdvertising() {
        if (advertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {
            }
        }
        advertising = false
    }

    private fun startServer() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val chat = BluetoothGattCharacteristic(
            CHAT_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        chat.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(chat)
        gattServer = adapter.let {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .openGattServer(appContext, gattServerCallback)
        }
        gattServer?.addService(service)
        serverChar = chat
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverConnections.add(device)
                // v3.2.5: listeners get the canonical address so chats keyed by
                // the scanned address see connect/disconnect events too.
                listener?.onConnectionChanged(resolveAddr(device.address), true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasMember = memberNames.containsKey(device.address)
                serverConnections.remove(device)
                if (wasMember) handleLeave(device, memberNames.remove(device.address) ?: "Someone")
                listener?.onConnectionChanged(resolveAddr(device.address), false)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            // NOTE: always use the `value` callback parameter. On the GATT server,
            // characteristic.value is never set by us and reads back null/stale on
            // Android 12 and below, which silently dropped every incoming message.
            if (characteristic.uuid == CHAT_CHAR_UUID) {
                handleServerFrame(device, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private fun handleServerFrame(device: BluetoothDevice, bytes: ByteArray) {
        // Frames carry 4-7 '|' separated fields; split generously, validate per type.
        val parts = bytes.toString(Charsets.UTF_8).split('|', limit = 7)
        if (parts.size < 4) return
        // v3.2.5: the peer's central address (what our server sees) usually
        // differs from the address we scanned/dialed them at. Canonicalize so
        // frames land in the conversation the UI is actually watching.
        val peerAddr = resolveAddr(device.address)
        lastSeen[peerAddr] = now() // v3.0 presence
        val type = parts[0]
        val sender = parts[1]
        val group = parts[2]
        when (type) {
            T_HELLO -> {
                // Always learn the sender's name, for 1-to-1 and group alike.
                memberNames[device.address] = sender
                bindHelloAlias(device.address, sender)
                val gid = hostingGroupId
                if (gid != null && group == gid) {
                    sysToGroup(gid, "$sender ${appContext.getString(R.string.joined_group)}")
                    broadcastRoster(gid)
                    listener?.onMembersChanged(gid, groupMembers())
                }
                // Lets any open chat refresh the peer's display name.
                listener?.onConnectionChanged(peerAddr, true)
            }
            T_MSG -> {
                if (parts.size != 5) return
                val ts = parts[3].toLongOrNull() ?: now()
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                addMessage(conv, ChatMessage(sender, parts[4], mine = false, time = ts))
                if (group == DM) {
                    // 1-to-1 delivery receipt: echo the sender's timestamp back.
                    val server = gattServer
                    val char = serverChar
                    if (server != null && char != null) {
                        notifyCompat(
                            server, device, char,
                            frame(T_ACK, myName, DM, ts.toString()).toByteArray(Charsets.UTF_8)
                        )
                    }
                }
                notifyAll(bytes, exclude = device) // relay to everyone else
            }
            T_ACK -> {
                // Echoes the sender-side timestamp: exact match, no guessing.
                val ackTime = parts[3].toLongOrNull() ?: return
                val conv = dmId(peerAddr)
                val msg = conversations[conv]
                    ?.firstOrNull { it.mine && it.time == ackTime && !it.delivered }
                    ?: return
                msg.delivered = true
                listener?.onMessageUpdated(conv)
            }
            T_TYPING -> {
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                listener?.onTyping(conv, if (parts[3] == "1") sender else null)
            }
            T_REPLY -> {
                if (parts.size != 7) return
                val ts = parts[3].toLongOrNull() ?: now()
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                addMessage(
                    conv,
                    ChatMessage(
                        sender, parts[6], mine = false, time = ts,
                        replyTo = ReplyInfo(parts[4], parts[5])
                    )
                )
                notifyAll(bytes, exclude = device) // relay exactly like T_MSG
            }
            T_BYE -> {
                val name = memberNames.remove(device.address) ?: sender
                handleLeave(device, name)
            }
            // ---- v3.0 additive frames ----
            T_IMG_START -> {
                if (parts.size != 7) return
                val imgId = parts[3]
                val total = parts[4].toIntOrNull() ?: return
                if (total <= 0 || total > IMG_MAX_CHUNKS) return
                val senderTime = parts[5].toLongOrNull() ?: now()
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                if (!incomingImages.containsKey(imgId)) {
                    val msg = ChatMessage(
                        sender, "", mine = false, time = senderTime,
                        isImage = true, imgId = imgId, imgTotal = total,
                        imgReceived = 0, caption = parts[6]
                    )
                    addMessage(conv, msg)
                    incomingImages[imgId] =
                        IncomingImg(imgId, total, arrayOfNulls(total), 0, conv, msg.id, senderTime)
                }
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_IMG_CHUNK -> {
                if (parts.size != 6) return
                val t = incomingImages[parts[3]]
                val idx = parts[4].toIntOrNull()
                if (t != null && idx != null && idx in 0 until t.total && t.chunks[idx] == null) {
                    t.chunks[idx] = parts[5]
                    t.received++
                    messageById(t.convId, t.msgId)?.let {
                        it.imgReceived = t.received
                        listener?.onMessageUpdated(t.convId)
                    }
                }
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_IMG_END -> {
                if (parts.size != 4) return
                val senderTime = finishImage(parts[3])
                if (group == DM && senderTime != null) {
                    // 1-to-1 delivery receipt: echo the sender's timestamp back.
                    val server = gattServer
                    val char = serverChar
                    if (server != null && char != null) {
                        notifyCompat(
                            server, device, char,
                            frame(T_ACK, myName, DM, senderTime.toString()).toByteArray(Charsets.UTF_8)
                        )
                    }
                }
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_REACT -> {
                if (parts.size != 5) return
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                applyReaction(conv, parts[3].toLongOrNull() ?: return, sender, parts[4])
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_PIN -> {
                if (parts.size != 5) return
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                applyPin(conv, parts[3].toLongOrNull() ?: return, parts[4] == "1")
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_GNAME -> {
                if (parts.size != 4) return
                if (!adminFrameOk(group, sender)) return
                groupNames[group] = parts[3].take(40)
                groupRoles.getOrPut(group) { mutableMapOf() }[sender] = "admin"
                listener?.onGroupChanged(group)
                notifyAll(bytes, exclude = device)
            }
            T_GAVATAR -> {
                if (parts.size != 4) return
                if (!adminFrameOk(group, sender)) return
                groupAvatarIdx[group] = parts[3].toIntOrNull()?.coerceIn(0, 31) ?: 0
                listener?.onGroupChanged(group)
                notifyAll(bytes, exclude = device)
            }
            T_ROLE -> {
                if (parts.size != 5) return
                if (!adminFrameOk(group, sender)) return
                groupRoles.getOrPut(group) { mutableMapOf() }[parts[3]] =
                    if (parts[4] == "admin") "admin" else "member"
                listener?.onMembersChanged(group, groupMembers())
                notifyAll(bytes, exclude = device)
            }
            T_KICK -> {
                if (parts.size != 4) return
                if (!adminFrameOk(group, sender)) return
                // A promoted admin kicked someone: drop their connection too.
                memberNames.entries.firstOrNull { it.value == parts[3] }?.key?.let { addr ->
                    serverConnections.firstOrNull { it.address == addr }?.let { d ->
                        try {
                            gattServer?.cancelConnection(d)
                        } catch (_: Exception) {
                        }
                    }
                }
                notifyAll(bytes, exclude = device)
            }
        }
    }

    /** Assemble an incoming image; returns its senderTime for the DM ACK. */
    private fun finishImage(imgId: String): Long? {
        val t = incomingImages.remove(imgId) ?: return null
        val b64 = t.chunks.filterNotNull().joinToString("")
        val raw = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
        val msg = messageById(t.convId, t.msgId)
        if (raw != null && raw.isNotEmpty() && msg != null) {
            val path = try {
                val f = File(imagesDir(), "$imgId.jpg")
                FileOutputStream(f).use { it.write(raw) }
                f.absolutePath
            } catch (_: Exception) {
                null
            }
            msg.imagePath = path
            msg.imgReceived = t.total
            if (path == null) msg.text = "(image)"
            listener?.onMessageUpdated(t.convId)
            logHistory("Received image from ${msg.sender}")
        } else if (msg != null) {
            msg.text = appContext.getString(R.string.image_failed)
            listener?.onMessageUpdated(t.convId)
        }
        return t.senderTime
    }

    /** True when a member-side frame comes from the host we're joined to. */
    private fun isHostFrame(address: String, groupId: String): Boolean =
        joinedGroupId == groupId && clientPeerAddress == address

    private fun handleLeave(device: BluetoothDevice, name: String) {
        val gid = hostingGroupId ?: return
        sysToGroup(gid, "$name ${appContext.getString(R.string.left_group)}")
        broadcastRoster(gid)
        listener?.onMembersChanged(gid, groupMembers())
    }

    private fun sysToGroup(groupId: String, text: String) {
        val sys = frame(T_SYS, "AirWave", groupId, text).toByteArray(Charsets.UTF_8)
        addMessage(
            groupConv(groupId),
            ChatMessage("AirWave", text, mine = false, time = now(), system = true)
        )
        notifyAll(sys, exclude = null)
    }

    private fun notifyAll(bytes: ByteArray, exclude: BluetoothDevice?) {
        val server = gattServer ?: return
        val char = serverChar ?: return
        for (d in serverConnections.toList()) {
            if (exclude != null && d.address == exclude.address) continue
            notifyCompat(server, d, char, bytes)
        }
    }

    // ---------------- central: scanning + GATT client ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val rec = result.scanRecord
            val name = rec?.deviceName ?: device.name ?: "AirWave"
            val mfg = rec?.getManufacturerSpecificData(MFG_ID)
            val groupId = mfg?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            val peer = Peer(name, device.address, groupId, result.rssi)
            peers[device.address] = peer
            listener?.onPeerFound(peer)
            // v3.0: group invite notification (10-minute cooldown per group).
            if (groupId != null && hostingGroupId != groupId && joinedGroupId != groupId) {
                val last = lastInviteNotify[groupId] ?: 0L
                if (now() - last > 10 * 60 * 1000L) {
                    lastInviteNotify[groupId] = now()
                    try {
                        NotificationHelper.showCustom(
                            appContext,
                            appContext.getString(R.string.group_invite_title),
                            appContext.getString(R.string.group_invite_text, name, groupId),
                            NearbyActivity::class.java,
                            groupId.hashCode()
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            listener?.onStatus("Scan failed ($errorCode)")
        }
    }

    private fun startScanning() {
        scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (_: Exception) {
        }
    }

    private fun stopScanning() {
        if (scanning) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
        }
        scanning = false
    }

    /** Restart scanning if we're online but it stopped (e.g. after connect()). */
    fun ensureScanning() {
        if (online && !scanning) startScanning()
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    logHistory("Connection failed (status=$status)")
                    try {
                        gatt.disconnect()
                    } catch (_: Exception) {
                    }
                    return
                }
                listener?.onConnectionChanged(gatt.device.address, true)
                logHistory("Connected to ${peers[gatt.device.address]?.name ?: gatt.device.address}")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clientReady = false
                clientChar = null
                joinedGroupId = null
                clearWriteQueue()
                listener?.onConnectionChanged(gatt.device.address, false)
                logHistory("Disconnected from ${peers[gatt.device.address]?.name ?: gatt.device.address}")
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                if (gatt == gattClient) {
                    gattClient = null
                    clientPeerAddress = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAT_CHAR_UUID)
            if (char == null) {
                logHistory("Peer service not found")
                return
            }
            clientChar = char
            logHistory("Peer service found, subscribing…")
            mtuAttempts = 0
            gatt.setCharacteristicNotification(char, true)
            // NOTE: GATT operations must be strictly serialized — Android allows only
            // one outstanding operation per connection (mDeviceBusy). Firing
            // writeDescriptor() and requestMtu() back-to-back silently drops the MTU
            // request, which used to leave clientReady=false forever AND the link MTU
            // at 23 bytes, so no frame could get through in either direction.
            // Chain: CCCD write -> onDescriptorWrite -> MTU request -> onMtuChanged.
            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                requestMtuSafe(gatt)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                // Fallback: if the descriptor-write callback never arrives, still
                // attempt the MTU exchange so we never stall forever.
                gattHandler.postDelayed({ requestMtuSafe(gatt) }, 4000)
            }
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                logHistory("Subscribed to peer updates")
                requestMtuSafe(gatt)
            }
        }

        // NOTE: on API 33+ the descriptor-write callback moved to a 4-arg
        // overload that this project's compile SDK doesn't include, so only
        // the 3-arg version is overridden. The 4s fallback below covers newer
        // phones (it fires requestMtuSafe even if this callback never arrives).

        /**
         * Request the MTU with retries. Safe to call from several callbacks —
         * attempts are capped and it no-ops once the link is ready.
         */
        private fun requestMtuSafe(gatt: BluetoothGatt) {
            if (clientReady || mtuAttempts >= 3) return
            mtuAttempts++
            if (mtuAttempts == 1) logHistory("Negotiating link speed…")
            try {
                gatt.requestMtu(MAX_MTU)
            } catch (_: Exception) {
            }
            // If onMtuChanged still hasn't fired, the request was dropped while
            // another operation was outstanding — retry now that the stack is idle.
            gattHandler.postDelayed({
                if (!clientReady) requestMtuSafe(gatt)
            }, 4000)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (clientReady) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // MTU exchange failed — the link would stay at 23 bytes and
                // truncate frames. Retry instead of marking ready.
                logHistory("Link speed failed, retrying…")
                requestMtuSafe(gatt)
                return
            }
            clientReady = true
            logHistory("Link ready")
            val c = clientChar
            val gid = pendingJoinGroup
            if (gid != null) {
                joinedGroupId = gid
                pendingJoinGroup = null
                if (c != null) {
                    writeCompat(gatt, c, frame(T_HELLO, myName, gid, "").toByteArray(Charsets.UTF_8))
                }
                listener?.onStatus(appContext.getString(R.string.in_group, gid))
            } else {
                // 1-to-1: introduce ourselves so the other side learns our real
                // name even if the Bluetooth adapter name didn't update.
                if (c != null) {
                    writeCompat(gatt, c, frame(T_HELLO, myName, DM, "").toByteArray(Charsets.UTF_8))
                }
                listener?.onStatus(appContext.getString(R.string.ready_to_chat))
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            handleClientFrame(gatt, bytes)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleClientFrame(gatt, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            // One queued write confirmed — send the next, if any.
            writeInFlight = false
            pumpWrites()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastRssi = rssi
                listener?.onRssi(rssi)
            }
        }
    }

    private fun handleClientFrame(gatt: BluetoothGatt, bytes: ByteArray) {
        // Frames carry 4-7 '|' separated fields; split generously, validate per type.
        val parts = bytes.toString(Charsets.UTF_8).split('|', limit = 7)
        if (parts.size < 4) return
        // v3.2.5: keep DM conversation keys on the address we DIALED — some
        // stacks report a different (resolved) address in gatt.device.
        val dialed = clientPeerAddress
        val peerAddr = if (dialed != null && gatt.device.address != dialed) {
            bindAddrAlias(gatt.device.address, dialed)
            dialed
        } else {
            gatt.device.address
        }
        lastSeen[peerAddr] = now() // v3.0 presence
        val type = parts[0]
        val sender = parts[1]
        val group = parts[2]
        when (type) {
            T_MSG -> {
                if (parts.size != 5) return
                val ts = parts[3].toLongOrNull() ?: now()
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                addMessage(conv, ChatMessage(sender, parts[4], mine = false, time = ts))
                if (group == DM) {
                    // 1-to-1 delivery receipt: echo the sender's timestamp back.
                    val c = clientChar
                    if (c != null) {
                        writeCompat(
                            gatt, c,
                            frame(T_ACK, myName, DM, ts.toString()).toByteArray(Charsets.UTF_8)
                        )
                    }
                }
            }
            T_ACK -> {
                // Echoes the sender-side timestamp: exact match, no guessing.
                val ackTime = parts[3].toLongOrNull() ?: return
                val conv = dmId(peerAddr)
                val msg = conversations[conv]
                    ?.firstOrNull { it.mine && it.time == ackTime && !it.delivered }
                    ?: return
                msg.delivered = true
                listener?.onMessageUpdated(conv)
            }
            T_TYPING -> {
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                listener?.onTyping(conv, if (parts[3] == "1") sender else null)
            }
            T_REPLY -> {
                if (parts.size != 7) return
                val ts = parts[3].toLongOrNull() ?: now()
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                addMessage(
                    conv,
                    ChatMessage(
                        sender, parts[6], mine = false, time = ts,
                        replyTo = ReplyInfo(parts[4], parts[5])
                    )
                )
            }
            T_SYS -> {
                addMessage(
                    groupConv(group),
                    ChatMessage(sender, parts[3], mine = false, time = now(), system = true)
                )
            }
            // ---- v3.0 additive frames (member side; the host already relayed) ----
            T_IMG_START -> {
                if (parts.size != 7) return
                val imgId = parts[3]
                val total = parts[4].toIntOrNull() ?: return
                if (total <= 0 || total > IMG_MAX_CHUNKS) return
                val senderTime = parts[5].toLongOrNull() ?: now()
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                if (!incomingImages.containsKey(imgId)) {
                    val msg = ChatMessage(
                        sender, "", mine = false, time = senderTime,
                        isImage = true, imgId = imgId, imgTotal = total,
                        imgReceived = 0, caption = parts[6]
                    )
                    addMessage(conv, msg)
                    incomingImages[imgId] =
                        IncomingImg(imgId, total, arrayOfNulls(total), 0, conv, msg.id, senderTime)
                }
            }
            T_IMG_CHUNK -> {
                if (parts.size != 6) return
                val t = incomingImages[parts[3]]
                val idx = parts[4].toIntOrNull()
                if (t != null && idx != null && idx in 0 until t.total && t.chunks[idx] == null) {
                    t.chunks[idx] = parts[5]
                    t.received++
                    messageById(t.convId, t.msgId)?.let {
                        it.imgReceived = t.received
                        listener?.onMessageUpdated(t.convId)
                    }
                }
            }
            T_IMG_END -> {
                if (parts.size != 4) return
                val senderTime = finishImage(parts[3])
                if (group == DM && senderTime != null) {
                    // 1-to-1 delivery receipt: echo the sender's timestamp back.
                    val c = clientChar
                    if (c != null) {
                        writeCompat(
                            gatt, c,
                            frame(T_ACK, myName, DM, senderTime.toString()).toByteArray(Charsets.UTF_8)
                        )
                    }
                }
            }
            T_REACT -> {
                if (parts.size != 5) return
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                applyReaction(conv, parts[3].toLongOrNull() ?: return, sender, parts[4])
            }
            T_PIN -> {
                if (parts.size != 5) return
                val conv = if (group == DM) dmId(peerAddr) else groupConv(group)
                applyPin(conv, parts[3].toLongOrNull() ?: return, parts[4] == "1")
            }
            T_GNAME -> {
                if (parts.size != 4) return
                if (!isHostFrame(peerAddr, group)) return
                groupNames[group] = parts[3].take(40)
                groupRoles.getOrPut(group) { mutableMapOf() }[sender] = "admin"
                listener?.onGroupChanged(group)
            }
            T_GAVATAR -> {
                if (parts.size != 4) return
                if (!isHostFrame(peerAddr, group)) return
                groupAvatarIdx[group] = parts[3].toIntOrNull()?.coerceIn(0, 31) ?: 0
                listener?.onGroupChanged(group)
            }
            T_ROLE -> {
                if (parts.size != 5) return
                if (!isHostFrame(peerAddr, group)) return
                groupRoles.getOrPut(group) { mutableMapOf() }[parts[3]] =
                    if (parts[4] == "admin") "admin" else "member"
                listener?.onMembersChanged(group, groupMembers())
            }
            T_KICK -> {
                if (parts.size != 4) return
                if (!isHostFrame(peerAddr, group)) return
                if (parts[3] == myName) {
                    leaveGroup()
                    logHistory("Removed from group $group")
                    listener?.onKicked(group)
                }
            }
            T_ROSTER -> {
                // v3.2.5: host's authoritative member list (names + roles).
                if (parts.size != 4) return
                if (!isHostFrame(peerAddr, group)) return
                val roles = groupRoles.getOrPut(group) { mutableMapOf() }
                roles.clear()
                val names = mutableListOf<String>()
                for (entry in parts[3].split('\u0001')) {
                    if (entry.isEmpty()) continue
                    val i = entry.indexOf('\u0002')
                    val name = if (i >= 0) entry.substring(0, i) else entry
                    val role = if (i >= 0) entry.substring(i + 1) else "member"
                    if (name.isNotEmpty()) {
                        names.add(name)
                        roles[name] = if (role == "admin") "admin" else "member"
                    }
                }
                rosterNames[group] = names
                listener?.onMembersChanged(group, groupMembers())
            }
        }
    }

    // ---------------- compat + helpers ----------------

    private fun writeCompat(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
        // Queue every write: Android drops a write issued while another is
        // outstanding (mDeviceBusy) with no error. The queue drains one frame
        // at a time as onCharacteristicWrite confirms each one.
        writeQueue.add(PendingWrite(gatt, char, value))
        pumpWrites()
    }

    private data class PendingWrite(
        val gatt: BluetoothGatt,
        val char: BluetoothGattCharacteristic,
        val value: ByteArray
    )
    private val writeQueue = ArrayDeque<PendingWrite>()
    private var writeInFlight = false

    private fun pumpWrites() {
        if (writeInFlight) return
        val w = writeQueue.removeFirstOrNull() ?: return
        if (w.gatt != gattClient) {
            pumpWrites() // stale connection — drop and try the next
            return
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            w.gatt.writeCharacteristic(w.char, w.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            w.char.value = w.value
            w.char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            w.gatt.writeCharacteristic(w.char)
        }
        if (ok) {
            writeInFlight = true
        } else {
            // Transient stack-busy: requeue at the front and retry shortly.
            writeQueue.addFirst(w)
            gattHandler.postDelayed({ pumpWrites() }, 100)
        }
    }

    private fun clearWriteQueue() {
        writeQueue.clear()
        writeInFlight = false
    }

    private fun notifyCompat(
        server: BluetoothGattServer, device: BluetoothDevice,
        char: BluetoothGattCharacteristic, value: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, char, false, value)
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, char, false)
        }
    }

    private fun frame(type: String, sender: String, group: String, text: String): String =
        "$type|${sender.replace('|', '/')}|${group.replace('|', '/')}|${text.replace('|', '/')}"

    /** Field sanitizer for frames with more than 4 fields. */
    private fun s(v: String) = v.replace('|', '/')

    private fun addMessage(convId: String, msg: ChatMessage) {
        // Blocked peers: drop silently — nothing stored, nothing shown.
        if (!msg.mine && convId.startsWith("$DM:")) {
            if (isBlocked(convId.removePrefix("$DM:"))) return
        }
        conversations.getOrPut(convId) { mutableListOf() }.add(msg)
        listener?.onMessage(convId, msg)
        // v3.2.5: unread badges no longer depend on the notifications setting.
        if (!msg.mine && !msg.system && foregroundConv != convId) {
            unread[convId] = (unread[convId] ?: 0) + 1
            listener?.onUnreadChanged()
        }
        if (!msg.mine && !msg.system && Prefs.notificationsEnabled &&
            foregroundConv != convId && !isMuted(convId)
        ) {
            try {
                NotificationHelper.show(
                    appContext, msg.sender, displayText(msg),
                    convId, convId.startsWith("group:")
                )
            } catch (_: Exception) {
            }
        }
    }

    fun unreadCount(convId: String): Int = unread[convId] ?: 0
    fun totalUnread(): Int = unread.values.sum()

    fun markRead(convId: String) {
        if (unread.remove(convId) != null) listener?.onUnreadChanged()
    }

    data class ConvSummary(
        val convId: String,
        val title: String,
        val snippet: String,
        val time: Long,
        val unread: Int,
        val isGroup: Boolean,
        val peerAddress: String?,
        val groupId: String?
    )

    /** One row per non-empty conversation, newest first, for the home chats list. */
    fun conversationSummaries(): List<ConvSummary> {
        val out = mutableListOf<ConvSummary>()
        for ((convId, list) in conversations) {
            if (list.isEmpty()) continue
            val last = list.last()
            val isGroup = convId.startsWith("group:")
            val title = if (isGroup) {
                groupDisplayName(convId.removePrefix("group:"))
            } else {
                nameFor(convId.removePrefix("$DM:"))
            }
            val snippet = when {
                last.system -> last.text
                last.mine -> "You: ${displayText(last)}"
                else -> "${last.sender}: ${displayText(last)}"
            }
            out.add(
                ConvSummary(
                    convId = convId,
                    title = title,
                    snippet = snippet,
                    time = last.time,
                    unread = unreadCount(convId),
                    isGroup = isGroup,
                    peerAddress = if (isGroup) null else convId.removePrefix("$DM:"),
                    groupId = if (isGroup) convId.removePrefix("group:") else null
                )
            )
        }
        return out.sortedByDescending { it.time }
    }

    fun setBlocked(address: String, blocked: Boolean) {
        val set = Prefs.blocked
        if (blocked) set.add(address) else set.remove(address)
        Prefs.blocked = set
    }

    fun isBlocked(address: String): Boolean = Prefs.blocked.contains(address)

    fun deleteMessage(convId: String, id: String) {
        conversations[convId]?.removeAll { it.id == id }
        listener?.onMessageUpdated(convId)
    }

    fun clearConversation(convId: String) {
        conversations[convId]?.clear()
        listener?.onMessageUpdated(convId)
    }

    private fun now() = System.currentTimeMillis()
}
