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
import androidx.room.Room
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    // ---- v4.0: voice notes ----
    //   VOICE_START|sender|group|voiceId|totalChunks|senderTime|durationSec
    //   VOICE_CHUNK|sender|group|voiceId|index|base64
    //   VOICE_END|sender|group|voiceId
    private const val T_VOICE_START = "VOICE_START"
    private const val T_VOICE_CHUNK = "VOICE_CHUNK"
    private const val T_VOICE_END = "VOICE_END"
    // ---- v4.1 ----
    private const val T_SEEN = "SEEN"               // SEEN|sender|group|targetTime
    private const val T_FILE_START = "FILE_START"   // FILE_START|sender|group|fileId|total|time|name|size|mime
    private const val T_FILE_CHUNK = "FILE_CHUNK"   // FILE_CHUNK|sender|group|fileId|index|base64
    private const val T_FILE_END = "FILE_END"       // FILE_END|sender|group|fileId
    private const val T_POLL_START = "POLL_START"   // POLL_START|sender|group|pollId|time|question|opts
    private const val T_POLL_VOTE = "POLL_VOTE"     // POLL_VOTE|sender|group|pollId|optIdx
    private const val POLL_SEP = "\u001F"
    private const val FILE_MAX_BYTES = 5 * 1024 * 1024
    private const val T_REACT = "REACT"
    private const val T_PIN = "PIN"
    private const val T_GNAME = "GNAME"
    private const val T_GAVATAR = "GAVATAR"
    private const val T_ROLE = "ROLE"
    private const val T_KICK = "KICK"
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
        // v4.0 voice notes
        val isVoice: Boolean = false,
        val voiceId: String? = null,
        val voiceTotal: Int = 0,
        var voiceReceived: Int = 0,
        var voicePath: String? = null,
        val voiceDurationSec: Int = 0,
        // v4.1 file sharing
        val isFile: Boolean = false,
        val fileId: String? = null,
        val fileTotal: Int = 0,
        var fileReceived: Int = 0,
        var filePath: String? = null,
        val fileName: String = "",
        val fileSize: Long = 0L,
        val mime: String = "",
        // v4.1 read receipts
        var seen: Boolean = false,
        // v4.1 polls
        val isPoll: Boolean = false,
        val pollId: String? = null,
        val question: String = "",
        val options: List<String> = emptyList(),
        val votes: MutableMap<String, Int> = mutableMapOf(),
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
        // v4.1
        fun onE2eChanged(convId: String) {}
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
    private var serviceAttempts = 0
    private val gattHandler = Handler(Looper.getMainLooper())

    private val serverConnections = LinkedHashSet<BluetoothDevice>()
    private val memberNames = LinkedHashMap<String, String>() // address -> name

    // v3.2.5: address aliasing. A peer's BLE advertising address (seen in
    // scans, used for connect() and chat IDs) can differ from the connection
    // address reported by the GATT server for the same physical phone
    // (rotating private address vs identity address). This maps
    // connection-address -> canonical (scan/connect) address so both sides
    // file DM messages under one conversation ID.
    private val addressAliases = LinkedHashMap<String, String>()

    /** Canonical peer address for any address we have seen. */
    private fun canonicalAddress(addr: String): String = addressAliases[addr] ?: addr

    /** Live server connection for a canonical peer address (alias-aware). */
    private fun serverConnFor(canonical: String): BluetoothDevice? =
        serverConnections.firstOrNull {
            it.address == canonical || canonicalAddress(it.address) == canonical
        }

    private val peers = LinkedHashMap<String, Peer>()
    private val conversations = LinkedHashMap<String, MutableList<ChatMessage>>()
    private val unread = LinkedHashMap<String, Int>()

    // v4.0: persistent WhatsApp-style storage. Room is the durable store;
    // the in-memory maps above stay as the UI source of truth and are
    // written through to the DB on every change, then reloaded in init().
    //
    // DM threads are keyed by STABLE contact id ("dm:c7"), never by the
    // volatile BLE address, so a reconnect with a rotated address lands in
    // the same thread instead of spawning a duplicate chat.
    private lateinit var db: ChatDatabase
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contacts = ConcurrentHashMap<String, ContactEntity>() // contactId -> row
    private val addrContact = ConcurrentHashMap<String, String>() // ble addr -> contactId
    private val contactAddr = ConcurrentHashMap<String, String>() // contactId -> current ble addr
    private var contactSeq = 0L

    // v3.0 state (all session-only by design)
    private val sessionHistory = mutableListOf<HistoryEvent>()
    private val lastSeen = LinkedHashMap<String, Long>() // peer address -> last frame time
    private val groupNames = LinkedHashMap<String, String>() // groupId -> display name
    private val groupAvatarIdx = LinkedHashMap<String, Int>() // groupId -> AvatarUtil color index
    private val groupRoles = LinkedHashMap<String, MutableMap<String, String>>() // groupId -> name -> admin|member
    private val lastInviteNotify = LinkedHashMap<String, Long>() // groupId -> last invite notif time
    private val incomingImages = LinkedHashMap<String, IncomingImg>()
    private var lastRssi: Int = Int.MIN_VALUE

    private val incomingVoices = LinkedHashMap<String, IncomingVoice>()

    private data class IncomingVoice(
        val voiceId: String,
        val total: Int,
        val chunks: Array<String?>,
        var received: Int,
        val convId: String,
        val msgId: String,
        val senderTime: Long,
        val durationSec: Int
    )

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

    /** Conversation currently open on screen; suppresses notifications for it. */
    var foregroundConv: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = mgr.adapter
        // v4.0: build the persistent store and reload chats/contacts so the
        // conversation list is visible immediately — no BLE connect needed.
        db = Room.databaseBuilder(appContext, ChatDatabase::class.java, "airwave.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        ioScope.launch {
            try {
                val cs = db.contactDao().all()
                for (c in cs) {
                    contacts[c.id] = c
                    c.addresses.split(",").filter { it.isNotBlank() }
                        .forEach { addrContact[it] = c.id }
                    contactSeq = maxOf(contactSeq, c.id.removePrefix("c").toLongOrNull() ?: 0L)
                }
                var convs = db.conversationDao().all()
                // v4.1: load the disappearing TTL cache + purge stale rows.
                convs.forEach { disappearCache[it.convId] = it.disappearSecs }
                convs.forEach { ce ->
                    conversations[ce.convId] = mutableListOf()
                    if (ce.unread > 0) unread[ce.convId] = ce.unread
                    val cid = ce.contactId
                    val addr = ce.lastBleAddr
                    if (cid != null && addr != null) {
                        contactAddr[cid] = addr
                    }
                }
                for (ce in convs) {
                    conversations[ce.convId] =
                        db.messageDao().forConv(ce.convId).map { it.toChatMessage() }.toMutableList()
                }
                // v4.1: enforce TTLs on the reloaded threads.
                conversations.keys.forEach { purgeExpired(it) }
            } catch (_: Exception) {
            }
            gattHandler.post { listener?.onUnreadChanged() }
        }
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

    // ---------------- v4.0 stable contacts ----------------
    //
    // DM threads are keyed by contactId ("dm:c7"). A contact is resolved by
    // BLE address first, then by exact name match (single match only), and
    // created on first sight. Known addresses accumulate on the contact, so
    // rotating BLE addresses never split a thread.

    /** Stable contact id for a BLE address + display name. Thread-safe. */
    @Synchronized
    fun ensureContact(address: String, name: String): String {
        val addr = address.trim()
        if (addr.isNotEmpty()) addrContact[addr]?.let { return it }
        val cleanName = name.ifBlank { addr.ifBlank { "Peer" } }
        val byName = contacts.values.filter { it.name.equals(cleanName, ignoreCase = true) }
        if (byName.size == 1) {
            val c = byName[0]
            val addrs = (c.addresses.split(",").filter { it.isNotBlank() } +
                    (if (addr.isNotEmpty()) listOf(addr) else emptyList())).distinct()
            val updated = c.copy(
                name = cleanName,
                addresses = addrs.joinToString(","),
                lastSeen = now()
            )
            contacts[c.id] = updated
            if (addr.isNotEmpty()) {
                addrContact[addr] = c.id
                contactAddr[c.id] = addr
            }
            ioScope.launch { runCatching { db.contactDao().upsert(updated) } }
            return c.id
        }
        val id = "c${++contactSeq}"
        val c = ContactEntity(id, cleanName, addr, now())
        contacts[id] = c
        if (addr.isNotEmpty()) {
            addrContact[addr] = id
            contactAddr[id] = addr
        }
        ioScope.launch { runCatching { db.contactDao().upsert(c) } }
        return id
    }

    /** Current BLE address for a contact, or null when unknown/offline. */
    fun bleAddressForContact(contactId: String): String? = contactAddr[contactId]

    /** DM thread id for a stable contact. */
    fun dmConvFor(contactId: String): String = "$DM:$contactId"

    /** Contact id behind a DM convId, or null for groups. */
    fun contactOf(convId: String): String? =
        if (convId.startsWith("$DM:")) convId.removePrefix("$DM:") else null

    /** Best-known display name for a contact id. */
    fun contactName(contactId: String): String = contacts[contactId]?.name ?: contactId

    /** True while this BLE address is in the current scan results. */
    fun isPeerDiscovered(address: String): Boolean = peers.containsKey(address)

    /** Live BLE send target for a DM convId — "" when the peer is offline. */
    private fun dmTarget(convId: String): String {
        val cid = contactOf(convId) ?: return ""
        return bleAddressForContact(cid) ?: ""
    }

    /**
     * DM thread for an incoming frame: resolves the stable contact (address
     * first, then name), and also aliases the raw connection address to the
     * same contact so every later frame matches instantly.
     */
    private fun dmConvFromDevice(device: BluetoothDevice, sender: String): String {
        val canonical = canonicalAddress(device.address)
        val cid = ensureContact(canonical, sender)
        addrContact[device.address] = cid
        return dmConvFor(cid)
    }

    /** Blocked check that survives address rotation (checks all known addrs). */
    fun isContactBlocked(contactId: String): Boolean {
        val c = contacts[contactId] ?: return false
        return c.addresses.split(",").any { it.isNotBlank() && Prefs.blocked.contains(it) }
    }

    /** Block/unblock every known address of a contact (rotation-proof). */
    fun setContactBlocked(contactId: String, blocked: Boolean) {
        val c = contacts[contactId]
        val addrs = c?.addresses?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val set = Prefs.blocked
        if (addrs.isEmpty()) {
            // Offline chat with no known address yet — fall back to nothing.
            return
        }
        for (a in addrs) {
            if (blocked) set.add(a) else set.remove(a)
        }
        Prefs.blocked = set
    }

    private fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
        sender = sender,
        text = text,
        mine = mine,
        time = time,
        system = system,
        id = id,
        delivered = delivered,
        replyTo = if (replyToSender != null) ReplyInfo(replyToSender, replyToText ?: "") else null,
        isImage = kind == 1,
        isVoice = kind == 3,
        imagePath = imagePath,
        caption = caption,
        voicePath = voicePath,
        voiceDurationSec = voiceDurationSec,
        // v4.1
        isFile = kind == 4,
        filePath = filePath,
        fileName = fileName,
        fileSize = fileSize,
        mime = mime,
        seen = seen,
        isPoll = kind == 5,
        pollId = pollId,
        question = if (kind == 5) pollData.substringBefore("\n") else "",
        options = if (kind == 5) pollData.substringAfter("\n").substringBefore("\n")
            .split(POLL_SEP).filter { it.isNotEmpty() } else emptyList(),
        votes = if (kind == 5) pollData.substringAfterLast("\n").split(";").mapNotNull {
            val kv = it.split("=")
            if (kv.size == 2) {
                val v = kv[1].toIntOrNull()
                if (v != null) kv[0] to v else null
            } else null
        }.toMap().toMutableMap() else mutableMapOf(),
        failed = failed
    )

    private fun ChatMessage.toEntity(convId: String): MessageEntity = MessageEntity(
        id = id,
        convId = convId,
        sender = sender,
        text = text,
        time = time,
        mine = mine,
        delivered = delivered,
        failed = failed,
        system = system,
        kind = when {
            system -> 2
            isPoll -> 5
            isFile -> 4
            isVoice -> 3
            isImage -> 1
            else -> 0
        },
        imagePath = imagePath,
        caption = caption,
        voicePath = voicePath,
        voiceDurationSec = voiceDurationSec,
        filePath = filePath,
        fileName = fileName,
        fileSize = fileSize,
        mime = mime,
        seen = seen,
        pollId = pollId,
        pollData = if (isPoll) {
            question + "\n" + options.joinToString(POLL_SEP) + "\n" +
                votes.entries.joinToString(";") { (k, v) ->
                    "${k.replace(';', ',').replace('=', '-')}=$v"
                }
        } else "",
        replyToSender = replyTo?.sender,
        replyToText = replyTo?.text
    )

    fun peersList(): List<Peer> = peers.values.toList()

    /** v4.1: chat list for the forward picker. */
    fun conversationList(): List<Pair<String, String>> =
        conversations.keys.map { it to convTitle(it) }

    /** v4.1: forward any message into another conversation as a new message. */
    fun forwardMessage(fromConv: String, msgId: String, toConv: String): Boolean {
        val msg = messageById(fromConv, msgId) ?: return false
        return when {
            msg.isPoll -> createPoll(toConv, msg.question, msg.options)
            msg.isFile -> sendFile(toConv, msg.filePath ?: return false, msg.fileName, msg.mime)
            msg.isVoice -> sendVoiceNote(toConv, msg.voicePath ?: return false, msg.voiceDurationSec)
            msg.isImage -> {
                val bmp = msg.imagePath?.let { BitmapFactory.decodeFile(it) } ?: return false
                sendImage(toConv, bmp, msg.caption)
            }
            else -> sendChat(toConv, msg.text)
        }
    }
    fun messages(convId: String): List<ChatMessage> = conversations[convId]?.toList() ?: emptyList()
    fun groupMembers(): List<String> = listOf(myName) + memberNames.values

    /** Best-known display name for a device address (handshake name, else scan name). */
    fun nameFor(address: String): String =
        memberNames[address] ?: peers[address]?.name ?: address

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
        // v4.0: keep the process alive in the background for real-time messages.
        BleService.start(appContext)
        return true
    }

    fun goOffline() {
        online = false
        BleService.stop(appContext)
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
        addressAliases.clear()
        unread.clear()
        // v4.0: a session wipe now also wipes the persistent store.
        contacts.clear()
        addrContact.clear()
        contactAddr.clear()
        ioScope.launch {
            runCatching {
                db.messageDao().clear()
                db.conversationDao().clear()
                db.contactDao().clear()
            }
        }
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
        serviceAttempts = 0
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
            if (g != null && c != null && clientReady && clientPeerAddress == target) {
                writeCompat(g, c, bytes)
                sent = true
            } else {
                val dev = serverConnFor(target)
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

    // ---------------- v4.1: end-to-end encryption (DMs only) ----------------

    /** v4.1 E2E encryption removed — always false. */
    fun isE2e(convId: String): Boolean = false

    /** v4.1 E2E encryption removed — DMs go as plain text so every message
     *  is always readable on the other phone. */
    private fun encText(convId: String, plain: String): String = plain

    private fun decText(convId: String, field: String): String {
        if (!field.startsWith("ENC:")) return field
        val blob = try {
            Base64.decode(field.removePrefix("ENC:"), Base64.NO_WRAP)
        } catch (_: Exception) {
            return field
        }
        return Crypto.decrypt(convId, blob)?.toString(Charsets.UTF_8) ?: field
    }

    /** v4.1 E2E encryption removed — media bytes go as-is. */
    private fun encBytes(convId: String, raw: ByteArray): ByteArray = raw

    private fun decBytes(convId: String, blob: ByteArray): ByteArray = blob

    /** Send a chat message in a conversation. Returns false if nothing is connected. */
    fun sendChat(convId: String, text: String): Boolean {
        val clean = text.replace('|', '/').trim()
        if (clean.isEmpty()) return false
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val ts = now()
        // The sender's timestamp rides in the frame so the ACK can echo it
        // back for an exact match on this phone (delivery ticks).
        val frameStr = "$T_MSG|${s(myName)}|${s(group)}|$ts|${s(encText(convId, clean))}"
        val target = if (isGroup) "" else dmTarget(convId)
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
        val target = if (isGroup) "" else dmTarget(convId)
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
        val target = if (isGroup) "" else dmTarget(convId)
        val ts = now()
        val quote = s(quotedText.take(120))
        val payload =
            "$T_REPLY|${s(myName)}|${s(group)}|$ts|${s(quotedSender)}|$quote|${s(encText(convId, clean))}"
        val sent = transport(group, target, payload.toByteArray(Charsets.UTF_8))
        addMessage(
            convId,
            ChatMessage(
                myName, clean, mine = true, time = ts,
                replyTo = ReplyInfo(quotedSender, quotedText.take(120)), failed = !sent
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
        val target = if (isGroup) "" else dmTarget(convId)
        val ts = now()
        val imgId = UUID.randomUUID().toString()
        val cap = s(caption.take(120))
        val rawBytes = try {
            jpegBytes(bitmap)
        } catch (_: Exception) {
            return false
        }
        val b64 = Base64.encodeToString(encBytes(convId, rawBytes), Base64.NO_WRAP)
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

    // ---------------- v4.0: voice notes ----------------

    private const val VOICE_MAX_SEC = 30
    private val voiceHandler = Handler(Looper.getMainLooper())

    private fun voiceDir(): File = File(appContext.filesDir, "voice").apply { mkdirs() }

    /**
     * Send a recorded .m4a voice note. Mirrors the image pipeline:
     * base64 → 360-char frames paced ~25ms apart.
     */
    fun sendVoiceNote(convId: String, filePath: String, durationSec: Int): Boolean {
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else dmTarget(convId)
        val ts = now()
        val voiceId = UUID.randomUUID().toString()
        val dur = durationSec.coerceIn(1, VOICE_MAX_SEC)
        val bytes = try {
            File(filePath).readBytes()
        } catch (_: Exception) {
            return false
        }
        if (bytes.isEmpty()) return false
        val b64 = Base64.encodeToString(encBytes(convId, bytes), Base64.NO_WRAP)
        val chunks = b64.chunked(IMG_CHUNK)
        if (chunks.isEmpty() || chunks.size > IMG_MAX_CHUNKS) return false
        val saved = try {
            val f = File(voiceDir(), "$voiceId.m4a")
            FileOutputStream(f).use { it.write(bytes) }
            f.absolutePath
        } catch (_: Exception) {
            null
        }
        val msg = ChatMessage(
            myName, "", mine = true, time = ts,
            isVoice = true, voiceId = voiceId, voiceTotal = chunks.size,
            voiceReceived = chunks.size, voicePath = saved, voiceDurationSec = dur
        )
        val start =
            "$T_VOICE_START|${s(myName)}|${s(group)}|$voiceId|${chunks.size}|$ts|$dur"
                .toByteArray(Charsets.UTF_8)
        val sent = transport(group, target, start)
        msg.failed = !sent
        addMessage(convId, msg)
        if (sent) {
            logHistory("Sent voice note to ${convTitle(convId)}")
            transmitVoiceChunks(group, target, voiceId, chunks, convId, msg.id)
        }
        return sent
    }

    private fun transmitVoiceChunks(
        group: String, target: String, voiceId: String,
        chunks: List<String>, convId: String, msgId: String
    ) {
        var idx = 0
        var failed = false
        val run = object : Runnable {
            override fun run() {
                if (idx < chunks.size) {
                    val frame =
                        "$T_VOICE_CHUNK|${s(myName)}|${s(group)}|$voiceId|$idx|${chunks[idx]}"
                            .toByteArray(Charsets.UTF_8)
                    if (!transport(group, target, frame)) failed = true
                    idx++
                    voiceHandler.postDelayed(this, 25)
                } else {
                    val end = "$T_VOICE_END|${s(myName)}|${s(group)}|$voiceId"
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
        voiceHandler.post(run)
    }

    // ---------------- v4.1: file sharing ----------------

    private fun filesDir(): File = File(appContext.filesDir, "files").apply { mkdirs() }

    private val fileHandler = Handler(Looper.getMainLooper())
    private val incomingFiles = LinkedHashMap<String, IncomingFile>()

    private data class IncomingFile(
        val fileId: String,
        val total: Int,
        val chunks: Array<String?>,
        var received: Int,
        val convId: String,
        val msgId: String,
        val senderTime: Long,
        val name: String,
        val size: Long,
        val mime: String
    )

    fun sendFile(convId: String, path: String, name: String, mime: String): Boolean {
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else dmTarget(convId)
        val ts = now()
        val fileId = UUID.randomUUID().toString()
        val bytes = try {
            File(path).readBytes()
        } catch (_: Exception) {
            return false
        }
        if (bytes.isEmpty() || bytes.size > FILE_MAX_BYTES) return false
        val safeName = s(name.take(80)).ifBlank { "file" }
        val safeMime = s(mime.take(60)).ifBlank { "*/*" }
        val b64 = Base64.encodeToString(encBytes(convId, bytes), Base64.NO_WRAP)
        val chunks = b64.chunked(IMG_CHUNK)
        if (chunks.isEmpty() || chunks.size > IMG_MAX_CHUNKS) return false
        val saved = try {
            val f = File(filesDir(), "$fileId-$safeName")
            FileOutputStream(f).use { it.write(bytes) }
            f.absolutePath
        } catch (_: Exception) {
            null
        }
        val msg = ChatMessage(
            myName, "", mine = true, time = ts, isFile = true, fileId = fileId,
            fileTotal = chunks.size, fileReceived = chunks.size, filePath = saved,
            fileName = safeName, fileSize = bytes.size.toLong(), mime = safeMime
        )
        val start =
            "$T_FILE_START|${s(myName)}|${s(group)}|$fileId|${chunks.size}|$ts|$safeName|${bytes.size}|$safeMime"
                .toByteArray(Charsets.UTF_8)
        val sent = transport(group, target, start)
        msg.failed = !sent
        addMessage(convId, msg)
        if (sent) {
            logHistory("Sent file $safeName to ${convTitle(convId)}")
            transmitFileChunks(group, target, fileId, chunks, convId, msg.id)
        }
        return sent
    }

    private fun transmitFileChunks(
        group: String, target: String, fileId: String,
        chunks: List<String>, convId: String, msgId: String
    ) {
        var idx = 0
        var failed = false
        val run = object : Runnable {
            override fun run() {
                if (idx < chunks.size) {
                    val fr =
                        "$T_FILE_CHUNK|${s(myName)}|${s(group)}|$fileId|$idx|${chunks[idx]}"
                            .toByteArray(Charsets.UTF_8)
                    if (!transport(group, target, fr)) failed = true
                    idx++
                    fileHandler.postDelayed(this, 25)
                } else {
                    val end = "$T_FILE_END|${s(myName)}|${s(group)}|$fileId"
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
        fileHandler.post(run)
    }

    /** Assemble an incoming file; returns its senderTime for the DM ACK. */
    private fun finishFile(fileId: String): Long? {
        val t = incomingFiles.remove(fileId) ?: return null
        val b64 = t.chunks.filterNotNull().joinToString("")
        val rawDec = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
        val raw = rawDec?.let { decBytes(t.convId, it) }
        val msg = messageById(t.convId, t.msgId)
        if (raw != null && raw.isNotEmpty() && msg != null) {
            val path = try {
                val f = File(filesDir(), "$fileId-${t.name}")
                FileOutputStream(f).use { it.write(raw) }
                f.absolutePath
            } catch (_: Exception) {
                null
            }
            msg.filePath = path
            msg.fileReceived = t.total
            if (path == null) msg.text = "(file)"
            listener?.onMessageUpdated(t.convId)
            logHistory("Received file ${t.name} from ${msg.sender}")
        } else if (msg != null) {
            msg.text = appContext.getString(R.string.file_failed)
            listener?.onMessageUpdated(t.convId)
        }
        return t.senderTime
    }

    // ---------------- v4.1: read receipts (DMs only) ----------------

    fun sendSeen(convId: String, targetTime: Long) {
        if (convId.startsWith("group:")) return
        val target = dmTarget(convId)
        transport(
            DM, target,
            "$T_SEEN|${s(myName)}|$DM|$targetTime".toByteArray(Charsets.UTF_8)
        )
    }

    private fun applySeen(conv: String, t: Long) {
        var changed = false
        conversations[conv]?.forEach {
            if (it.mine && !it.system && it.time <= t && !it.seen) {
                it.seen = true; changed = true
            }
        }
        if (changed) {
            listener?.onMessageUpdated(conv)
            ioScope.launch { runCatching { db.messageDao().markSeen(conv, t) } }
        }
    }

    // ---------------- v4.1: polls ----------------

    fun createPoll(convId: String, question: String, options: List<String>): Boolean {
        val q = question.trim().take(140)
        if (q.isEmpty()) return false
        val opts = options.map { it.trim().take(40) }.filter { it.isNotEmpty() }.take(5)
        if (opts.size < 2) return false
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else dmTarget(convId)
        val ts = now()
        val pollId = UUID.randomUUID().toString()
        val msg = ChatMessage(
            myName, "", mine = true, time = ts, isPoll = true,
            pollId = pollId, question = q, options = opts
        )
        val payload =
            "$T_POLL_START|${s(myName)}|${s(group)}|$pollId|$ts|${s(q)}|${opts.joinToString(POLL_SEP) { s(it) }}"
                .toByteArray(Charsets.UTF_8)
        val sent = transport(group, target, payload)
        msg.failed = !sent
        addMessage(convId, msg)
        return sent
    }

    fun votePoll(convId: String, pollId: String, optIdx: Int) {
        val msg = conversations[convId]?.firstOrNull { it.pollId == pollId } ?: return
        if (optIdx !in msg.options.indices) return
        msg.votes[myName] = optIdx
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else dmTarget(convId)
        transport(
            group, target,
            "$T_POLL_VOTE|${s(myName)}|${s(group)}|$pollId|$optIdx".toByteArray(Charsets.UTF_8)
        )
        listener?.onMessageUpdated(convId)
        ioScope.launch { runCatching { db.messageDao().insert(msg.toEntity(convId)) } }
    }

    // ---------------- v4.1: disappearing messages ----------------

    private val disappearCache = mutableMapOf<String, Int>()

    fun disappearSecs(convId: String): Int = disappearCache[convId] ?: 0

    fun setDisappearing(convId: String, secs: Int) {
        disappearCache[convId] = secs
        ioScope.launch {
            runCatching { db.conversationDao().setDisappearing(convId, secs) }
        }
        if (secs > 0) purgeExpired(convId)
    }

    /** Delete messages older than the per-chat TTL (media files too). */
    fun purgeExpired(convId: String) {
        val secs = disappearCache[convId] ?: 0
        if (secs <= 0) return
        val before = now() - secs * 1000L
        ioScope.launch {
            val doomed = runCatching { db.messageDao().expired(convId, before) }
                .getOrDefault(emptyList())
            doomed.forEach { e ->
                e.imagePath?.let { runCatching { File(it).delete() } }
                e.voicePath?.let { runCatching { File(it).delete() } }
                e.filePath?.let { runCatching { File(it).delete() } }
            }
            runCatching { db.messageDao().deleteExpired(convId, before) }
            conversations[convId]?.removeAll { it.time < before }
            listener?.onMessageUpdated(convId)
        }
    }

    /** Retry a failed outgoing message (text, reply, image or voice). */
    fun resendMessage(convId: String, msgId: String): Boolean {
        val msg = messageById(convId, msgId) ?: return false
        if (!msg.mine || !msg.failed) return false
        val isGroup = convId.startsWith("group:")
        val group = if (isGroup) convId.removePrefix("group:") else DM
        val target = if (isGroup) "" else dmTarget(convId)
        if (msg.isVoice) {
            val path = msg.voicePath ?: return false
            val bytes = try {
                File(path).readBytes()
            } catch (_: Exception) {
                return false
            }
            val chunks = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(IMG_CHUNK)
            if (chunks.isEmpty() || chunks.size > IMG_MAX_CHUNKS) return false
            val start =
                "$T_VOICE_START|${s(myName)}|${s(group)}|${msg.voiceId}|${chunks.size}|${msg.time}|${msg.voiceDurationSec}"
                    .toByteArray(Charsets.UTF_8)
            val sent = transport(group, target, start)
            if (sent) {
                msg.failed = false
                listener?.onMessageUpdated(convId)
                transmitVoiceChunks(group, target, msg.voiceId ?: "", chunks, convId, msg.id)
            }
            return sent
        }
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
        val target = if (isGroup) "" else dmTarget(convId)
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
        val target = if (isGroup) "" else dmTarget(convId)
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

    /** True if we have a live link to this peer (either direction). */
    fun isPeerConnected(address: String): Boolean {
        if (gattClient != null && clientReady && clientPeerAddress == address) return true
        return serverConnFor(address) != null
    }

    /** True when a GATT link (or a link attempt in progress) exists to [address],
     *  even if it is not fully ready yet. Used to avoid restarting a handshake
     *  that is already underway. */
    fun hasLinkTo(address: String): Boolean {
        if (gattClient != null && clientPeerAddress == address) return true
        return serverConnFor(address) != null
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
        gattClient != null && clientReady && clientPeerAddress == address

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

    fun messageById(convId: String, id: String): ChatMessage? =
        conversations[convId]?.firstOrNull { it.id == id }

    private fun displayText(msg: ChatMessage): String = when {
        msg.isPoll -> "\uD83D\uDCCA ${msg.question}"
        msg.isFile -> "\uD83D\uDCCE ${msg.fileName}"
        msg.isVoice -> "\uD83C\uDFA4 ${VoiceNotes.fmt(msg.voiceDurationSec)}"
        msg.isImage -> "\uD83D\uDCF7 ${msg.caption.ifBlank { "Photo" }}"
        else -> msg.text
    }

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
                listener?.onConnectionChanged(device.address, true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasMember = memberNames.containsKey(device.address)
                serverConnections.remove(device)
                if (wasMember) handleLeave(device, memberNames.remove(device.address) ?: "Someone")
                listener?.onConnectionChanged(canonicalAddress(device.address), false)
                addressAliases.remove(device.address)
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
        // Frames carry 4-9 '|' separated fields; split generously, validate per type.
        val parts = bytes.toString(Charsets.UTF_8).split('|', limit = 9)
        // HELLO tolerates legacy 3-field (HELLO|name|gid) and v4.1 4-field
        // (HELLO|name|gid|pubkey); every other frame type still needs >= 4.
        if (parts.size < 3) return
        if (parts.size < 4 && parts[0] != T_HELLO) return
        lastSeen[device.address] = now() // v3.0 presence
        val type = parts[0]
        val sender = parts[1]
        val group = parts[2]
        when (type) {
            T_HELLO -> {
                // Always learn the sender's name, for 1-to-1 and group alike.
                memberNames[device.address] = sender
                // v3.2.5: link this connection's address to the peer's scanned
                // address (matched by name — the peer was just scanned before
                // connecting) so DM frames file under one conversation ID.
                if (!addressAliases.containsKey(device.address)) {
                    val match = peers.values.filter {
                        it.name.equals(sender, ignoreCase = true)
                    }
                    if (match.size == 1 && match[0].address != device.address) {
                        addressAliases[device.address] = match[0].address
                    }
                }
                // v4.0: resolve the stable contact on handshake — this is what
                // keys the DM thread, so rotating addresses never duplicate it.
                dmConvFromDevice(device, sender)
                // v4.1 E2E: 4th field carries the peer pubkey -> derive the DM
                // key. Tolerant: 3-field HELLO skips this; a bad key never
                // kills the handshake; anything beyond field 4 is ignored.
                val e2eConv = dmConvFromDevice(device, sender)
                if (parts.size >= 4 && parts[3].isNotBlank()) {
                    try {
                        if (Crypto.rememberPeer(appContext, e2eConv, parts[3])) {
                            listener?.onE2eChanged(e2eConv)
                        }
                    } catch (_: Exception) {
                        // Bad key must never kill the connection.
                    }
                }
                val gid = hostingGroupId
                if (gid != null && group == gid) {
                    sysToGroup(gid, "$sender ${appContext.getString(R.string.joined_group)}")
                    listener?.onMembersChanged(gid, groupMembers())
                }
                // Lets any open chat refresh the peer's display name.
                listener?.onConnectionChanged(canonicalAddress(device.address), true)
            }
            T_MSG -> {
                if (parts.size != 5) return
                val ts = parts[3].toLongOrNull() ?: now()
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                addMessage(conv, ChatMessage(sender, decText(conv, parts[4]), mine = false, time = ts))
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
                val conv = dmConvFromDevice(device, sender)
                val msg = conversations[conv]
                    ?.firstOrNull { it.mine && it.time == ackTime && !it.delivered }
                    ?: return
                msg.delivered = true
                listener?.onMessageUpdated(conv)
                // v4.0: persist the delivery tick.
                ioScope.launch { runCatching { db.messageDao().markDelivered(conv, ackTime) } }
            }
            T_TYPING -> {
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                listener?.onTyping(conv, if (parts[3] == "1") sender else null)
            }
            T_REPLY -> {
                if (parts.size != 7) return
                val ts = parts[3].toLongOrNull() ?: now()
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                addMessage(
                    conv,
                    ChatMessage(
                        sender, decText(conv, parts[6]), mine = false, time = ts,
                        replyTo = ReplyInfo(parts[4], decText(conv, parts[5]))
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
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
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
            T_VOICE_START -> {
                if (parts.size != 7) return
                val voiceId = parts[3]
                val total = parts[4].toIntOrNull() ?: return
                if (total <= 0 || total > IMG_MAX_CHUNKS) return
                val senderTime = parts[5].toLongOrNull() ?: now()
                val dur = parts[6].toIntOrNull()?.coerceIn(1, VOICE_MAX_SEC) ?: 0
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                if (!incomingVoices.containsKey(voiceId)) {
                    val msg = ChatMessage(
                        sender, "", mine = false, time = senderTime,
                        isVoice = true, voiceId = voiceId, voiceTotal = total,
                        voiceReceived = 0, voiceDurationSec = dur
                    )
                    addMessage(conv, msg)
                    incomingVoices[voiceId] =
                        IncomingVoice(voiceId, total, arrayOfNulls(total), 0, conv, msg.id, senderTime, dur)
                }
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_VOICE_CHUNK -> {
                if (parts.size != 6) return
                val t = incomingVoices[parts[3]]
                val idx = parts[4].toIntOrNull()
                if (t != null && idx != null && idx in 0 until t.total && t.chunks[idx] == null) {
                    t.chunks[idx] = parts[5]
                    t.received++
                    messageById(t.convId, t.msgId)?.let {
                        it.voiceReceived = t.received
                        listener?.onMessageUpdated(t.convId)
                    }
                }
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_VOICE_END -> {
                if (parts.size != 4) return
                val senderTime = finishVoice(parts[3])
                if (group == DM && senderTime != null) {
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
            // ---- v4.1 files ----
            T_FILE_START -> {
                if (parts.size != 9) return
                val fileId = parts[3]
                val total = parts[4].toIntOrNull() ?: return
                if (total <= 0 || total > IMG_MAX_CHUNKS) return
                val senderTime = parts[5].toLongOrNull() ?: now()
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                if (!incomingFiles.containsKey(fileId)) {
                    val msg = ChatMessage(
                        sender, "", mine = false, time = senderTime, isFile = true,
                        fileId = fileId, fileTotal = total, fileReceived = 0,
                        fileName = parts[6], fileSize = parts[7].toLongOrNull() ?: 0L,
                        mime = parts[8]
                    )
                    addMessage(conv, msg)
                    incomingFiles[fileId] = IncomingFile(
                        fileId, total, arrayOfNulls(total), 0, conv, msg.id,
                        senderTime, parts[6], parts[7].toLongOrNull() ?: 0L, parts[8]
                    )
                }
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_FILE_CHUNK -> {
                if (parts.size != 6) return
                val t = incomingFiles[parts[3]]
                val idx = parts[4].toIntOrNull()
                if (t != null && idx != null && idx in 0 until t.total && t.chunks[idx] == null) {
                    t.chunks[idx] = parts[5]
                    t.received++
                    messageById(t.convId, t.msgId)?.let {
                        it.fileReceived = t.received
                        listener?.onMessageUpdated(t.convId)
                    }
                }
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_FILE_END -> {
                if (parts.size != 4) return
                val senderTime = finishFile(parts[3])
                if (group == DM && senderTime != null) {
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
            // ---- v4.1 read receipts ----
            T_SEEN -> {
                if (parts.size != 4) return
                val t = parts[3].toLongOrNull() ?: return
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                applySeen(conv, t)
            }
            // ---- v4.1 polls ----
            T_POLL_START -> {
                if (parts.size != 8) return
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                val opts = parts[7].split(POLL_SEP).map { it.take(40) }.filter { it.isNotEmpty() }
                if (opts.size < 2) return
                addMessage(
                    conv, ChatMessage(
                        sender, "", mine = false, time = parts[4].toLongOrNull() ?: now(),
                        isPoll = true, pollId = parts[3], question = parts[6].take(140), options = opts
                    )
                )
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_POLL_VOTE -> {
                if (parts.size != 6) return
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                val msg = conversations[conv]?.firstOrNull { it.pollId == parts[3] }
                val idx = parts[5].toIntOrNull()
                if (msg != null && idx != null && idx in msg.options.indices) {
                    msg.votes[sender] = idx
                    listener?.onMessageUpdated(conv)
                    ioScope.launch {
                        runCatching { db.messageDao().insert(msg.toEntity(conv)) }
                    }
                }
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_REACT -> {
                if (parts.size != 5) return
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
                applyReaction(conv, parts[3].toLongOrNull() ?: return, sender, parts[4])
                notifyAll(bytes, exclude = device) // relay like MSG
            }
            T_PIN -> {
                if (parts.size != 5) return
                val conv = if (group == DM) dmConvFromDevice(device, sender) else groupConv(group)
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
        val rawDec = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
        val raw = rawDec?.let { decBytes(t.convId, it) }
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

    /** Assemble an incoming voice note; returns its senderTime for the DM ACK. */
    private fun finishVoice(voiceId: String): Long? {
        val t = incomingVoices.remove(voiceId) ?: return null
        val b64 = t.chunks.filterNotNull().joinToString("")
        val rawDec = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
        val raw = rawDec?.let { decBytes(t.convId, it) }
        val msg = messageById(t.convId, t.msgId)
        if (raw != null && raw.isNotEmpty() && msg != null) {
            val path = try {
                val f = File(voiceDir(), "$voiceId.m4a")
                FileOutputStream(f).use { it.write(raw) }
                f.absolutePath
            } catch (_: Exception) {
                null
            }
            msg.voicePath = path
            msg.voiceReceived = t.total
            if (path == null) msg.text = "(voice note)"
            listener?.onMessageUpdated(t.convId)
            logHistory("Received voice note from ${msg.sender}")
        } else if (msg != null) {
            msg.text = appContext.getString(R.string.voice_failed)
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
                // The peer's GATT service registers asynchronously — it may
                // simply not be there yet if both phones went online at once.
                // Retry discovery instead of abandoning the link.
                if (serviceAttempts < 3) {
                    serviceAttempts++
                    logHistory("Peer service not ready, retrying…")
                    gattHandler.postDelayed({
                        if (gatt == gattClient) {
                            try {
                                gatt.discoverServices()
                            } catch (_: Exception) {
                            }
                        }
                    }, 1500)
                } else {
                    logHistory("Peer service not found")
                }
                return
            }
            serviceAttempts = 0
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
            if (clientReady) return
            if (mtuAttempts >= 8) {
                logHistory("Link speed failed — move the phones closer and tap the peer again")
                return
            }
            mtuAttempts++
            if (mtuAttempts == 1) logHistory("Negotiating link speed…")
            try {
                gatt.requestMtu(MAX_MTU)
            } catch (_: Exception) {
            }
            // If onMtuChanged still hasn't fired, the request was dropped while
            // another operation was outstanding — retry now that the stack is
            // idle. First retries are fast; later ones back off so a busy
            // stack still gets its MTU instead of a permanently dead link.
            val delay = if (mtuAttempts < 3) 4000L else 10000L
            gattHandler.postDelayed({
                if (!clientReady && gatt == gattClient) requestMtuSafe(gatt)
            }, delay)
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
        // Frames carry 4-9 '|' separated fields; split generously, validate per type.
        val parts = bytes.toString(Charsets.UTF_8).split('|', limit = 9)
        if (parts.size < 4) return
        lastSeen[gatt.device.address] = now() // v3.0 presence
        val type = parts[0]
        val sender = parts[1]
        val group = parts[2]
        when (type) {
            T_MSG -> {
                if (parts.size != 5) return
                val ts = parts[3].toLongOrNull() ?: now()
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                addMessage(conv, ChatMessage(sender, decText(conv, parts[4]), mine = false, time = ts))
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
                val conv = dmConvFromDevice(gatt.device, sender)
                val msg = conversations[conv]
                    ?.firstOrNull { it.mine && it.time == ackTime && !it.delivered }
                    ?: return
                msg.delivered = true
                listener?.onMessageUpdated(conv)
                // v4.0: persist the delivery tick.
                ioScope.launch { runCatching { db.messageDao().markDelivered(conv, ackTime) } }
            }
            T_TYPING -> {
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                listener?.onTyping(conv, if (parts[3] == "1") sender else null)
            }
            T_REPLY -> {
                if (parts.size != 7) return
                val ts = parts[3].toLongOrNull() ?: now()
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                addMessage(
                    conv,
                    ChatMessage(
                        sender, decText(conv, parts[6]), mine = false, time = ts,
                        replyTo = ReplyInfo(parts[4], decText(conv, parts[5]))
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
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
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
            T_VOICE_START -> {
                if (parts.size != 7) return
                val voiceId = parts[3]
                val total = parts[4].toIntOrNull() ?: return
                if (total <= 0 || total > IMG_MAX_CHUNKS) return
                val senderTime = parts[5].toLongOrNull() ?: now()
                val dur = parts[6].toIntOrNull()?.coerceIn(1, VOICE_MAX_SEC) ?: 0
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                if (!incomingVoices.containsKey(voiceId)) {
                    val msg = ChatMessage(
                        sender, "", mine = false, time = senderTime,
                        isVoice = true, voiceId = voiceId, voiceTotal = total,
                        voiceReceived = 0, voiceDurationSec = dur
                    )
                    addMessage(conv, msg)
                    incomingVoices[voiceId] =
                        IncomingVoice(voiceId, total, arrayOfNulls(total), 0, conv, msg.id, senderTime, dur)
                }
            }
            T_VOICE_CHUNK -> {
                if (parts.size != 6) return
                val t = incomingVoices[parts[3]]
                val idx = parts[4].toIntOrNull()
                if (t != null && idx != null && idx in 0 until t.total && t.chunks[idx] == null) {
                    t.chunks[idx] = parts[5]
                    t.received++
                    messageById(t.convId, t.msgId)?.let {
                        it.voiceReceived = t.received
                        listener?.onMessageUpdated(t.convId)
                    }
                }
            }
            T_VOICE_END -> {
                if (parts.size != 4) return
                val senderTime = finishVoice(parts[3])
                if (group == DM && senderTime != null) {
                    val c = clientChar
                    if (c != null) {
                        writeCompat(
                            gatt, c,
                            frame(T_ACK, myName, DM, senderTime.toString()).toByteArray(Charsets.UTF_8)
                        )
                    }
                }
            }
            // ---- v4.1 files (member side; host already relayed) ----
            T_FILE_START -> {
                if (parts.size != 9) return
                val fileId = parts[3]
                val total = parts[4].toIntOrNull() ?: return
                if (total <= 0 || total > IMG_MAX_CHUNKS) return
                val senderTime = parts[5].toLongOrNull() ?: now()
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                if (!incomingFiles.containsKey(fileId)) {
                    val msg = ChatMessage(
                        sender, "", mine = false, time = senderTime, isFile = true,
                        fileId = fileId, fileTotal = total, fileReceived = 0,
                        fileName = parts[6], fileSize = parts[7].toLongOrNull() ?: 0L,
                        mime = parts[8]
                    )
                    addMessage(conv, msg)
                    incomingFiles[fileId] = IncomingFile(
                        fileId, total, arrayOfNulls(total), 0, conv, msg.id,
                        senderTime, parts[6], parts[7].toLongOrNull() ?: 0L, parts[8]
                    )
                }
            }
            T_FILE_CHUNK -> {
                if (parts.size != 6) return
                val t = incomingFiles[parts[3]]
                val idx = parts[4].toIntOrNull()
                if (t != null && idx != null && idx in 0 until t.total && t.chunks[idx] == null) {
                    t.chunks[idx] = parts[5]
                    t.received++
                    messageById(t.convId, t.msgId)?.let {
                        it.fileReceived = t.received
                        listener?.onMessageUpdated(t.convId)
                    }
                }
            }
            T_FILE_END -> {
                if (parts.size != 4) return
                val senderTime = finishFile(parts[3])
                if (group == DM && senderTime != null) {
                    val c = clientChar
                    if (c != null) {
                        writeCompat(
                            gatt, c,
                            frame(T_ACK, myName, DM, senderTime.toString()).toByteArray(Charsets.UTF_8)
                        )
                    }
                }
            }
            // ---- v4.1 read receipts (member side) ----
            T_SEEN -> {
                if (parts.size != 4) return
                val t = parts[3].toLongOrNull() ?: return
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                applySeen(conv, t)
            }
            // ---- v4.1 polls (member side) ----
            T_POLL_START -> {
                if (parts.size != 8) return
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                val opts = parts[7].split(POLL_SEP).map { it.take(40) }.filter { it.isNotEmpty() }
                if (opts.size < 2) return
                addMessage(
                    conv, ChatMessage(
                        sender, "", mine = false, time = parts[4].toLongOrNull() ?: now(),
                        isPoll = true, pollId = parts[3], question = parts[6].take(140), options = opts
                    )
                )
            }
            T_POLL_VOTE -> {
                if (parts.size != 6) return
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                val msg = conversations[conv]?.firstOrNull { it.pollId == parts[3] }
                val idx = parts[5].toIntOrNull()
                if (msg != null && idx != null && idx in msg.options.indices) {
                    msg.votes[sender] = idx
                    listener?.onMessageUpdated(conv)
                    ioScope.launch {
                        runCatching { db.messageDao().insert(msg.toEntity(conv)) }
                    }
                }
            }
            T_REACT -> {
                if (parts.size != 5) return
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                applyReaction(conv, parts[3].toLongOrNull() ?: return, sender, parts[4])
            }
            T_PIN -> {
                if (parts.size != 5) return
                val conv = if (group == DM) dmConvFromDevice(gatt.device, sender) else groupConv(group)
                applyPin(conv, parts[3].toLongOrNull() ?: return, parts[4] == "1")
            }
            T_GNAME -> {
                if (parts.size != 4) return
                if (!isHostFrame(gatt.device.address, group)) return
                groupNames[group] = parts[3].take(40)
                groupRoles.getOrPut(group) { mutableMapOf() }[sender] = "admin"
                listener?.onGroupChanged(group)
            }
            T_GAVATAR -> {
                if (parts.size != 4) return
                if (!isHostFrame(gatt.device.address, group)) return
                groupAvatarIdx[group] = parts[3].toIntOrNull()?.coerceIn(0, 31) ?: 0
                listener?.onGroupChanged(group)
            }
            T_ROLE -> {
                if (parts.size != 5) return
                if (!isHostFrame(gatt.device.address, group)) return
                groupRoles.getOrPut(group) { mutableMapOf() }[parts[3]] =
                    if (parts[4] == "admin") "admin" else "member"
                listener?.onMembersChanged(group, groupMembers())
            }
            T_KICK -> {
                if (parts.size != 4) return
                if (!isHostFrame(gatt.device.address, group)) return
                if (parts[3] == myName) {
                    leaveGroup()
                    logHistory("Removed from group $group")
                    listener?.onKicked(group)
                }
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
        // v4.0: contact-aware so a rotated address can't dodge the block.
        if (!msg.mine && convId.startsWith("$DM:")) {
            val cid = contactOf(convId)
            if (cid != null && isContactBlocked(cid)) return
        }
        conversations.getOrPut(convId) { mutableListOf() }.add(msg)
        listener?.onMessage(convId, msg)
        if (!msg.mine && !msg.system && Prefs.notificationsEnabled && foregroundConv != convId) {
            unread[convId] = (unread[convId] ?: 0) + 1
            listener?.onUnreadChanged()
        }
        if (!msg.mine && !msg.system && Prefs.notificationsEnabled &&
            foregroundConv != convId && !isMuted(convId)
        ) {
            try {
                NotificationHelper.showChat(appContext, convId, msg.sender, displayText(msg))
            } catch (_: Exception) {
            }
        }
        // v4.0: write through to the persistent store (WhatsApp-style history).
        ioScope.launch {
            runCatching {
                db.messageDao().insert(msg.toEntity(convId))
                val isGroup = convId.startsWith("group:")
                val cid = contactOf(convId)
                db.conversationDao().upsert(
                    ConversationEntity(
                        convId = convId,
                        title = convTitle(convId),
                        isGroup = isGroup,
                        groupId = if (isGroup) convId.removePrefix("group:") else null,
                        contactId = cid,
                        lastBleAddr = cid?.let { contactAddr[it] },
                        lastText = displayText(msg),
                        lastTime = msg.time,
                        unread = unread[convId] ?: 0,
                        disappearSecs = disappearCache[convId] ?: 0
                    )
                )
            }
        }
        // v4.1: enforce the per-chat TTL for incoming messages.
        if (!msg.mine) purgeExpired(convId)
    }

    fun unreadCount(convId: String): Int = unread[convId] ?: 0
    fun totalUnread(): Int = unread.values.sum()

    fun markRead(convId: String) {
        if (unread.remove(convId) != null) listener?.onUnreadChanged()
        // v4.0: persist the read state.
        ioScope.launch { runCatching { db.conversationDao().clearUnread(convId) } }
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
    /** Display title for a thread — contact name for DMs (survives restarts). */
    private fun convTitle(convId: String): String =
        if (convId.startsWith("group:")) {
            groupDisplayName(convId.removePrefix("group:"))
        } else {
            val cid = contactOf(convId)
            if (cid != null) contactName(cid) else nameFor(convId.removePrefix("$DM:"))
        }

    fun conversationSummaries(): List<ConvSummary> {
        val out = mutableListOf<ConvSummary>()
        for ((convId, list) in conversations) {
            if (list.isEmpty()) continue
            val last = list.last()
            val isGroup = convId.startsWith("group:")
            val title = convTitle(convId)
            val snippet = when {
                last.system -> last.text
                last.mine -> "You: ${displayText(last)}"
                else -> "${last.sender}: ${displayText(last)}"
            }
            val cid = contactOf(convId)
            out.add(
                ConvSummary(
                    convId = convId,
                    title = title,
                    snippet = snippet,
                    time = last.time,
                    unread = unreadCount(convId),
                    isGroup = isGroup,
                    // v4.0: current BLE address for the contact, null when offline.
                    peerAddress = if (isGroup) null else cid?.let { bleAddressForContact(it) },
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
        // v4.0: keep the persistent store in sync.
        ioScope.launch { runCatching { db.messageDao().delete(id) } }
    }

    fun clearConversation(convId: String) {
        conversations[convId]?.clear()
        listener?.onMessageUpdated(convId)
        // v4.0: keep the persistent store in sync.
        ioScope.launch { runCatching { db.messageDao().clearConv(convId) } }
    }

    private fun now() = System.currentTimeMillis()
}
