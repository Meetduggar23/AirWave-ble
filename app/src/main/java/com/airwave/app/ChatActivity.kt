package com.airwave.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import com.airwave.app.databinding.ActivityChatBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : BaseActivity(), AirWaveBle.Listener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var convId: String
    private var peerName: String = ""
    private var peerAddress: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val clearTypingRunnable = Runnable { binding.typingText.visibility = View.GONE }
    private var lastTypingSent = 0L
    private var typingStateSent = false
    private var pendingReply: AirWaveBle.ReplyInfo? = null

    // v3.0: gallery picker + RSSI polling
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            // v3.2.7 (A3): decode off the UI thread with sampling — decoding
            // full-size gallery photos here caused OOM on low-RAM devices.
            Thread {
                val bmp = ImageUtils.decodeSampledUri(contentResolver, uri)
                runOnUiThread {
                    if (bmp != null) {
                        AirWaveBle.sendImage(convId, bmp, "")
                        render()
                    } else {
                        Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    private val rssiRunnable = object : Runnable {
        override fun run() {
            if (AirWaveBle.hasClientTo(peerAddress)) {
                AirWaveBle.requestRssi()
            }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerName = intent.getStringExtra("peer_name") ?: "Peer"
        peerAddress = intent.getStringExtra("peer_address") ?: ""
        convId = AirWaveBle.canonicalConvId(AirWaveBle.dmId(peerAddress))

        renderHeader()
        binding.backButton.setOnClickListener { finish() }
        binding.overflowButton.setOnClickListener { showOverflow(it) }
        binding.replyClose.setOnClickListener { clearReply() }
        // v3.0
        binding.imageButton.setOnClickListener { pickImage.launch("image/*") }
        binding.searchButton.setOnClickListener { toggleSearch() }
        binding.pinButton.setOnClickListener { showPinned() }
        binding.searchClose.setOnClickListener { toggleSearch() }
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.setQuery(s?.toString() ?: "")
                val n = adapter.matchCount
                binding.searchCount.text = getString(R.string.matches_found, n)
            }
        })

        adapter = MessageAdapter(this)
        adapter.onItemLongClick = { showMessageOptions(it) }
        adapter.onRetryClick = { AirWaveBle.resendMessage(convId, it.id); render() }
        adapter.onImageClick = {
            startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                putExtra("convId", convId)
                putExtra("msgId", it.id)
            })
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
        adapter.onReactionsClick = {
            if (it.reactions.containsKey(AirWaveBle.myName)) {
                AirWaveBle.sendReaction(convId, it.time, "")
            }
        }
        binding.messagesList.adapter = adapter

        binding.messageEdit.addTextChangedListener(typingWatcher)
        binding.sendButton.setOnClickListener { send() }
        // v3.2.7 (A6): the keyboard's "Send" action now actually sends — the
        // input declares imeOptions=actionSend but nothing handled it.
        binding.messageEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        handler.post(rssiRunnable)
    }

    private fun renderHeader() {
        binding.peerName.text = peerName
        binding.peerInitial.text = peerName.firstOrNull()?.uppercase() ?: "?"
        AvatarUtil.tintAvatar(binding.peerAvatar, AvatarUtil.colorFor(peerName))
        renderStatus()
    }

    /** v3.0: last-seen / active subtitle. */
    private fun renderStatus() {
        binding.peerStatus.text =
            if (binding.typingText.visibility == View.VISIBLE) "" else AirWaveBle.lastSeenLabel(peerAddress)
    }

    override fun onResume() {
        super.onResume()
        AirWaveBle.listener = this
        AirWaveBle.foregroundConv = convId
        AirWaveBle.markRead(convId)
        // If the link to this peer died (or never started), reconnect now so
        // the chat heals itself instead of failing every send. v3.2.5: alias-
        // aware check no longer opens a second GATT link to the same person.
        if (!AirWaveBle.hasLinkTo(peerAddress)) {
            AirWaveBle.connect(peerAddress)
        }
        renderStatus()
        render()
        handler.removeCallbacks(rssiRunnable)
        handler.post(rssiRunnable)
    }

    override fun onPause() {
        super.onPause()
        AirWaveBle.foregroundConv = null
        handler.removeCallbacks(clearTypingRunnable)
        handler.removeCallbacks(rssiRunnable)
        if (typingStateSent) {
            AirWaveBle.sendTyping(convId, false)
            typingStateSent = false
        }
    }

    override fun onDestroy() {
        // v3.2.5: never leave a dead activity registered as the BLE listener.
        if (AirWaveBle.listener === this) AirWaveBle.listener = null
        super.onDestroy()
    }

    // ---------------- send ----------------

    private val typingWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val typing = !s.isNullOrEmpty()
            val now = System.currentTimeMillis()
            if (typing != typingStateSent || now - lastTypingSent > 2500) {
                typingStateSent = typing
                lastTypingSent = now
                AirWaveBle.sendTyping(convId, typing)
            }
        }
    }

    private fun send() {
        val text = binding.messageEdit.text.toString()
        if (text.isBlank()) return
        val reply = pendingReply
        val ok = if (reply != null) {
            AirWaveBle.sendReply(convId, reply.sender, reply.text, text)
        } else {
            AirWaveBle.sendChat(convId, text)
        }
        if (ok) {
            binding.messageEdit.text.clear()
            clearReply()
            AirWaveBle.sendTyping(convId, false)
            typingStateSent = false
        } else {
            // v3.0: failed messages stay in the list with a retry icon.
            Toast.makeText(this, getString(R.string.not_connected_yet), Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun render() {
        adapter.setMessages(AirWaveBle.messages(convId))
        binding.messagesEmpty.visibility =
            if (adapter.count == 0) View.VISIBLE else View.GONE
        binding.messagesList.post {
            val count = adapter.count
            if (count > 0) binding.messagesList.smoothScrollToPosition(count - 1)
        }
    }

    // ---------------- reply bar ----------------

    private fun startReply(msg: AirWaveBle.ChatMessage) {
        pendingReply = AirWaveBle.ReplyInfo(msg.sender, msg.text)
        binding.quoteSender.text = msg.sender
        binding.quoteText.text = msg.text
        binding.replyBar.visibility = View.VISIBLE
        binding.messageEdit.requestFocus()
    }

    private fun clearReply() {
        pendingReply = null
        binding.replyBar.visibility = View.GONE
    }

    // ---------------- long-press actions ----------------

    private fun showMessageOptions(msg: AirWaveBle.ChatMessage) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (!msg.isImage) {
            items += getString(R.string.copy) to {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("airwave", msg.text))
                Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
            }
        }
        items += getString(R.string.react) to { showReactDialog(msg) }
        items += getString(
            if (msg.pinned) R.string.unpin_message else R.string.pin_message
        ) to {
            AirWaveBle.sendPin(convId, msg.time, !msg.pinned)
            Toast.makeText(
                this,
                getString(if (msg.pinned) R.string.unpinned else R.string.pinned),
                Toast.LENGTH_SHORT
            ).show()
        }
        if (!msg.isImage) items += getString(R.string.reply) to { startReply(msg) }
        items += getString(R.string.delete) to {
            AirWaveBle.deleteMessage(convId, msg.id)
            render()
            Toast.makeText(this, getString(R.string.message_deleted), Toast.LENGTH_SHORT).show()
        }
        AlertDialog.Builder(this)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    // ---------------- v3.0: reactions, pins, search, export ----------------

    private val EMOJIS = arrayOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    private fun showReactDialog(msg: AirWaveBle.ChatMessage) {
        val labels = EMOJIS.toMutableList()
        if (msg.reactions.containsKey(AirWaveBle.myName)) labels += getString(R.string.remove_reaction)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.react))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < EMOJIS.size) {
                    AirWaveBle.sendReaction(convId, msg.time, EMOJIS[which])
                } else {
                    AirWaveBle.sendReaction(convId, msg.time, "")
                }
            }
            .show()
    }

    private fun toggleSearch() {
        val show = binding.searchBar.visibility != View.VISIBLE
        binding.searchBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            adapter.setQuery("")
            binding.searchEdit.text.clear()
        } else {
            binding.searchEdit.requestFocus()
        }
    }

    private fun showPinned() {
        val pinned = AirWaveBle.pinnedMessages(convId)
        if (pinned.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_pinned), Toast.LENGTH_SHORT).show()
            return
        }
        val labels = pinned.map {
            val body = if (it.isImage) "📷 ${it.caption.ifBlank { "Photo" }}" else it.text
            "${it.sender}: $body"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pinned_messages))
            .setItems(labels, null)
            .setPositiveButton(getString(R.string.cancel), null)
            .show()
    }

    /** v3.0: export this chat as a plain-text file and share it. */
    private fun exportChat() {
        try {
            val msgs = AirWaveBle.messages(convId)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder()
            sb.appendLine("AirWave chat export — $peerName")
            sb.appendLine("Exported: ${fmt.format(Date(System.currentTimeMillis()))}")
            sb.appendLine()
            for (m in msgs) {
                val body = when {
                    m.system -> m.text
                    m.isImage -> "📷 [image] ${m.caption}"
                    else -> m.text
                }
                val reacts = if (m.reactions.isEmpty()) "" else
                    "  [" + m.reactions.entries.joinToString(" ") { "${it.key}:${it.value}" } + "]"
                sb.appendLine("[${fmt.format(Date(m.time))}] ${m.sender}: $body$reacts")
            }
            val out = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(out, "airwave_chat_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString())
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.export_chat)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- overflow ----------------

    private fun showOverflow(anchor: View) {
        val blocked = AirWaveBle.isBlocked(peerAddress)
        val muted = AirWaveBle.isMuted(convId)
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.clear_chat))
        popup.menu.add(
            0, 2, 0,
            getString(if (blocked) R.string.unblock_user else R.string.block_user)
        )
        popup.menu.add(0, 3, 0, getString(R.string.disconnect))
        popup.menu.add(0, 4, 0, getString(if (muted) R.string.unmute_chat else R.string.mute_chat))
        popup.menu.add(0, 5, 0, getString(R.string.export_chat))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmClearChat()
                2 -> toggleBlock()
                3 -> confirmDisconnect()
                4 -> toggleMute()
                5 -> exportChat()
            }
            true
        }
        popup.show()
    }

    private fun toggleMute() {
        val muted = AirWaveBle.isMuted(convId)
        AirWaveBle.setMuted(convId, !muted)
        Toast.makeText(
            this,
            getString(if (muted) R.string.unmuted else R.string.muted),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmClearChat() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.clear_chat_confirm))
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                AirWaveBle.clearConversation(convId)
                render()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleBlock() {
        val blocked = AirWaveBle.isBlocked(peerAddress)
        AirWaveBle.setBlocked(peerAddress, !blocked)
        Toast.makeText(
            this,
            getString(
                if (blocked) R.string.unblocked_toast else R.string.blocked_toast, peerName
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.disconnect_confirm, peerName))
            .setPositiveButton(getString(R.string.disconnect)) { _, _ ->
                AirWaveBle.disconnectClient()
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ---------------- AirWaveBle.Listener ----------------

    override fun onMessage(convId: String, msg: AirWaveBle.ChatMessage) {
        if (convId == this.convId) runOnUiThread { render() }
    }

    override fun onMessageUpdated(convId: String) {
        if (convId == this.convId) runOnUiThread { render() }
    }

    override fun onUnreadChanged() {
        // Badge lives on the home screen; nothing to update here.
    }

    override fun onTyping(convId: String, name: String?) {
        if (convId != this.convId) return
        runOnUiThread {
            handler.removeCallbacks(clearTypingRunnable)
            if (name == null) {
                binding.typingText.visibility = View.GONE
                renderStatus()
            } else {
                binding.typingText.text = getString(R.string.typing)
                binding.typingText.visibility = View.VISIBLE
                handler.postDelayed(clearTypingRunnable, 4000)
            }
        }
    }

    /** v3.0: BLE link-quality bars in the chat header. */
    override fun onRssi(rssi: Int) {
        runOnUiThread { binding.signalView.setRssi(rssi) }
    }

    override fun onConnectionChanged(address: String, connected: Boolean) {
        // v3.2.5: the callback address may be the alias (canonical) form of
        // this chat's peer — accept both so the header refreshes either way.
        if (address != peerAddress &&
            address != AirWaveBle.canonicalConvId(AirWaveBle.dmId(peerAddress))
                .removePrefix("${AirWaveBle.DM}:")
        ) return
        runOnUiThread {
            if (!connected) {
                Toast.makeText(this, getString(R.string.peer_disconnected), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // The real username may have arrived via the HELLO handshake.
                peerName = AirWaveBle.nameFor(peerAddress)
                renderHeader()
            }
        }
    }

}
