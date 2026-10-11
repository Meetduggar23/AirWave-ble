package com.airwave.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.airwave.app.databinding.ActivityChatBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : BaseActivity(), AirWaveBle.Listener {

    private companion object {
        const val REQ_VOICE = 61
    }

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

    // ---- v4.0 voice notes ----
    private var voiceRecorder: MediaRecorder? = null
    private var voicePath: String? = null
    private var voiceStartMs = 0L
    private val recordTick = object : Runnable {
        override fun run() {
            val s = ((System.currentTimeMillis() - voiceStartMs) / 1000).toInt()
            binding.recordTimer.text = VoiceNotes.fmt(s)
            if (s >= VoiceNotes.MAX_SEC) stopVoiceRecord(send = true)
            else binding.recordTimer.postDelayed(this, 500)
        }
    }

    // ---- v4.1: files / forward / seen ----
    private var pendingForwardId: String? = null
    private var lastSeenSent = 0L

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            val info = cacheIncomingFile(uri) ?: return@registerForActivityResult
            AirWaveBle.sendFile(convId, info.first, info.second, info.third)
        }

    private val forwardPick =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val to = res.data?.getStringExtra("to_conv")
                val mid = pendingForwardId
                if (to != null && mid != null) AirWaveBle.forwardMessage(convId, mid, to)
            }
            pendingForwardId = null
        }

    // v3.0: gallery picker + RSSI polling
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        AirWaveBle.sendImage(convId, bmp, "")
                        render()
                    } else {
                        Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
            }
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
        // v4.0: threads are keyed by stable contact id, not BLE address.
        // Callers pass "contact_id"; derive it when missing (e.g. old flows).
        val contactId = intent.getStringExtra("contact_id")
            ?: intent.getStringExtra("conv_id")?.let { AirWaveBle.contactOf(it) }
            ?: AirWaveBle.ensureContact(peerAddress, peerName)
        convId = AirWaveBle.dmConvFor(contactId)

        renderHeader()
        binding.backButton.setOnClickListener { finish() }
        binding.overflowButton.setOnClickListener { showOverflow(it) }
        binding.replyClose.setOnClickListener { clearReply() }
        // v3.0
        binding.imageButton.setOnClickListener { pickImage.launch("image/*") }
        // Adapter must be created before any adapter.* access below.
        adapter = MessageAdapter(this)
        // v4.0 voice notes
        binding.micButton.setOnClickListener { onMicTap() }
        binding.recordStop.setOnClickListener { stopVoiceRecord(send = true) }
        adapter.onVoiceClick = { msg -> onVoiceClick(msg) }
        // v4.1
        binding.imageButton.setOnClickListener { showAttachChooser() }
        adapter.onFileClick = { msg -> openFile(msg) }
        adapter.onPollVote = { msg, idx ->
            val pid = msg.pollId
            if (pid != null) AirWaveBle.votePoll(convId, pid, idx)
        }
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
        // v4.0: lazy reconnect — only dial when the peer is actually nearby.
        // Opening an old chat while the peer is offline shows history with an
        // "offline" status instead of a doomed connection attempt.
        if (peerAddress.isNotEmpty() && !AirWaveBle.hasLinkTo(peerAddress) &&
            AirWaveBle.isPeerDiscovered(peerAddress)
        ) {
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
        // NOTE: do NOT disconnect the GATT client here — the link must survive
        // leaving the chat screen, otherwise reopening the chat can never send.
        // Explicit disconnect stays available in the chat menu.
        // v3.2.5: release the shared listener slot so a destroyed activity
        // can never swallow message callbacks meant for another screen.
        if (AirWaveBle.listener == this) AirWaveBle.listener = null
        // v4.0: abandon any in-flight recording and stop playback.
        if (voiceRecorder != null) stopVoiceRecord(send = false)
        VoiceNotes.Player.stop()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_VOICE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecord()
            } else {
                Toast.makeText(this, getString(R.string.mic_needed), Toast.LENGTH_SHORT).show()
            }
        }
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
        // v4.1: E2E badge + read receipt for the latest incoming message.
        if (AirWaveBle.isE2e(convId)) {
            binding.peerStatus.text =
                "${binding.peerStatus.text} · ${getString(R.string.e2e_on)}"
        }
        sendSeenIfNeeded()
    }

    // ---------------- v4.0 voice notes ----------------

    private fun onMicTap() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE
            )
            return
        }
        startVoiceRecord()
    }

    private fun startVoiceRecord() {
        val path = VoiceNotes.newPath(this)
        val r = VoiceNotes.startRecording(this, path) ?: run {
            Toast.makeText(this, getString(R.string.voice_failed), Toast.LENGTH_SHORT).show()
            return
        }
        voiceRecorder = r
        voicePath = path
        voiceStartMs = System.currentTimeMillis()
        binding.recordBar.visibility = View.VISIBLE
        binding.inputRow.visibility = View.GONE
        binding.recordTimer.post(recordTick)
    }

    private fun stopVoiceRecord(send: Boolean) {
        binding.recordTimer.removeCallbacks(recordTick)
        VoiceNotes.stopRecording(voiceRecorder)
        voiceRecorder = null
        binding.recordBar.visibility = View.GONE
        binding.inputRow.visibility = View.VISIBLE
        val path = voicePath
        voicePath = null
        if (send && path != null) {
            val secs = VoiceNotes.durationSec(path)
            if (secs < 1) {
                File(path).delete()
                Toast.makeText(this, getString(R.string.voice_too_short), Toast.LENGTH_SHORT).show()
            } else {
                AirWaveBle.sendVoiceNote(convId, path, secs)
            }
        } else if (path != null) {
            File(path).delete()
        }
    }

    private fun onVoiceClick(msg: AirWaveBle.ChatMessage) {
        val id = msg.voiceId ?: return
        VoiceNotes.Player.toggle(
            id, msg.voicePath,
            onTick = { pos, dur -> adapter.setVoiceState(id, true, pos, dur) },
            onDone = { adapter.setVoiceState(null, false, 0, 0) }
        )
    }

    // ---------------- v4.1: attach chooser / files / forward / disappearing / polls ----------------

    private fun showAttachChooser() {
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(getString(R.string.attach_photo), getString(R.string.attach_file))
            ) { _, w ->
                if (w == 0) pickImage.launch("image/*") else pickFile.launch("*/*")
            }
            .show()
    }

    /** Copy a content:// URI into cache so BLE can read it. Returns (path, name, mime). */
    private fun cacheIncomingFile(uri: Uri): Triple<String, String, String>? {
        return try {
            val cr = contentResolver
            val name = cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "file"
            val mime = cr.getType(uri) ?: "*/*"
            val tmp = File(cacheDir, "send_${System.currentTimeMillis()}_$name")
            cr.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { ins.copyTo(it) }
            }
            Triple(tmp.absolutePath, name, mime)
        } catch (_: Exception) {
            null
        }
    }

    private fun openFile(msg: AirWaveBle.ChatMessage) {
        val f = msg.filePath?.let { File(it) }?.takeIf { it.exists() } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, msg.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, getString(R.string.open_file)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.file_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDisappearingDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.disappearing))
            .setItems(
                arrayOf(
                    getString(R.string.disappear_off),
                    getString(R.string.disappear_24h),
                    getString(R.string.disappear_7d)
                )
            ) { _, w ->
                AirWaveBle.setDisappearing(
                    convId,
                    when (w) {
                        1 -> 86400
                        2 -> 604800
                        else -> 0
                    }
                )
            }
            .show()
    }

    private fun showPollDialog() {
        val q = EditText(this).apply { hint = getString(R.string.poll_question) }
        val o = EditText(this).apply {
            hint = getString(R.string.poll_options_hint)
            minLines = 3
        }
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(q); addView(o)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.create_poll))
            .setView(lay)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AirWaveBle.createPoll(convId, q.text.toString(), o.text.toString().lines())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** v4.1: send a read receipt for the latest incoming message. */
    private fun sendSeenIfNeeded() {
        val latest = AirWaveBle.messages(convId)
            .filter { !it.mine && !it.system }
            .maxOfOrNull { it.time } ?: return
        if (latest != lastSeenSent) {
            lastSeenSent = latest
            AirWaveBle.sendSeen(convId, latest)
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
        // v4.1: forward
        items += getString(R.string.forward) to {
            pendingForwardId = msg.id
            forwardPick.launch(Intent(this, ForwardActivity::class.java))
        }
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
            val body = when {
                it.isPoll -> "📊 ${it.question}"
                it.isFile -> "📎 ${it.fileName}"
                it.isVoice -> "🎤 ${VoiceNotes.fmt(it.voiceDurationSec)}"
                it.isImage -> "📷 ${it.caption.ifBlank { "Photo" }}"
                else -> it.text
            }
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
                    m.isPoll -> "📊 [poll] ${m.question}"
                    m.isFile -> "📎 [file] ${m.fileName}"
                    m.isVoice -> "🎤 [voice note ${VoiceNotes.fmt(m.voiceDurationSec)}]"
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
        val contactId = AirWaveBle.contactOf(convId)
        val blocked = contactId?.let { AirWaveBle.isContactBlocked(it) }
            ?: AirWaveBle.isBlocked(peerAddress)
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
        val contactId = AirWaveBle.contactOf(convId)
        val blocked = contactId?.let { AirWaveBle.isContactBlocked(it) }
            ?: AirWaveBle.isBlocked(peerAddress)
        if (contactId != null) AirWaveBle.setContactBlocked(contactId, !blocked)
        else AirWaveBle.setBlocked(peerAddress, !blocked)
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

    /** v4.1: DM key derived — refresh the badge. */
    override fun onE2eChanged(convId: String) {
        if (convId == this.convId) runOnUiThread { render() }
    }

    override fun onConnectionChanged(address: String, connected: Boolean) {
        if (address != peerAddress) return
        runOnUiThread {
            if (!connected) {
                Toast.makeText(this, getString(R.string.peer_disconnected), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // The real username may have arrived via the HELLO handshake.
                peerName = AirWaveBle.nameFor(address)
                renderHeader()
            }
        }
    }
}
