package com.airwave.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.airwave.app.databinding.ActivityGroupChatBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GroupChatActivity : BaseActivity(), AirWaveBle.Listener {

    private companion object {
        const val REQ_VOICE = 61
    }

    private lateinit var binding: ActivityGroupChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var convId: String
    private lateinit var groupId: String
    private var isHost: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val clearTypingRunnable = Runnable { binding.typingText.visibility = View.GONE }
    private var lastTypingSent = 0L
    private var typingStateSent = false
    private var pendingReply: AirWaveBle.ReplyInfo? = null

    // ---- v4.0 voice notes ----
    private var voiceRecorder: MediaRecorder? = null
    private var voicePath: String? = null
    private var voiceStartMs = 0L
    private var holdVoice = false
    private val recordTick = object : Runnable {
        override fun run() {
            val s = ((System.currentTimeMillis() - voiceStartMs) / 1000).toInt()
            binding.recordTimer.text = VoiceNotes.fmt(s)
            if (s >= VoiceNotes.MAX_SEC) stopVoiceRecord(send = true)
            else binding.recordTimer.postDelayed(this, 500)
        }
    }

    // ---- v4.1: files / forward ----
    private var pendingForwardId: String? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            val info = cacheIncomingFile(uri) ?: return@registerForActivityResult
            if (!AirWaveBle.sendFile(convId, info.first, info.second, info.third)) {
                Toast.makeText(this, getString(R.string.file_failed), Toast.LENGTH_SHORT).show()
            }
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

    // v3.0: gallery picker
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("group_id") ?: ""
        isHost = intent.getBooleanExtra("is_host", false)
        convId = AirWaveBle.groupConv(groupId)

        renderGroupHeader()
        // v3.2.5: back just navigates away — the BLE link and membership stay
        // alive. Leaving the group stays in the overflow menu (confirmLeave).
        binding.backButton.setOnClickListener { finish() }
        binding.groupQrButton.setOnClickListener { showGroupQr() }
        binding.overflowButton.setOnClickListener { showOverflow(it) }
        binding.replyClose.setOnClickListener { clearReply() }
        // Attach button (paperclip): the chooser is set further below.
        binding.imageButton.setImageResource(R.drawable.ic_attach)
        // Adapter must be created before any adapter.* access below.
        adapter = MessageAdapter(this)
        // Voice is now hold-to-record on the send button; hide the old mic key.
        binding.micButton.visibility = View.GONE
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

        adapter.showTicks = false
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
        // Hold the send button (empty text) to record a voice note; release
        // for a Send / Cancel choice. A normal tap still sends typed text.
        binding.sendButton.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (binding.messageEdit.text.isNullOrEmpty()) {
                        holdVoice = true
                        onMicTap()
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (holdVoice) {
                        holdVoice = false
                        finishHoldRecord()
                        true
                    } else false
                }
                MotionEvent.ACTION_CANCEL -> {
                    holdVoice = false
                    if (voiceRecorder != null) stopVoiceRecord(send = false)
                    true
                }
                else -> false
            }
        }
        binding.sendButton.setOnClickListener { send() }
        updateSendIcon()
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
    }

    /** v3.0: renamable group title + tinted avatar + my role. */
    private fun renderGroupHeader() {
        val name = AirWaveBle.groupDisplayName(groupId)
        binding.groupTitle.text = name
        val admin = AirWaveBle.amIAdmin(groupId)
        binding.groupSubtitle.text = getString(
            if (admin) R.string.role_admin else R.string.role_member
        )
        binding.groupInitial.text = name.firstOrNull()?.uppercase() ?: "G"
        AvatarUtil.tintAvatar(binding.groupAvatar, AirWaveBle.groupAvatarColor(groupId))
    }

    private fun amIAdmin(): Boolean = AirWaveBle.amIAdmin(groupId)

    override fun onResume() {
        super.onResume()
        AirWaveBle.listener = this
        AirWaveBle.foregroundConv = convId
        AirWaveBle.markRead(convId)
        // v4.1: lazy reconnect like 1-to-1 chats — redial the host when the
        // link dropped but the host is still nearby. Hosts need no link.
        if (!isHost) {
            val host = AirWaveBle.groupHostAddress
            if (!host.isNullOrEmpty() && !AirWaveBle.hasLinkTo(host) &&
                AirWaveBle.isPeerDiscovered(host)
            ) {
                AirWaveBle.connect(host)
            }
        }
        renderGroupHeader()
        render()
        renderMembers(AirWaveBle.groupMembers())
    }


    override fun onPause() {
        super.onPause()
        AirWaveBle.foregroundConv = null
        handler.removeCallbacks(clearTypingRunnable)
        if (typingStateSent) {
            AirWaveBle.sendTyping(convId, false)
            typingStateSent = false
        }
    }

    private val typingWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            updateSendIcon()
            val typing = !s.isNullOrEmpty()
            val now = System.currentTimeMillis()
            if (typing != typingStateSent || now - lastTypingSent > 2500) {
                typingStateSent = typing
                lastTypingSent = now
                AirWaveBle.sendTyping(convId, typing)
            }
        }
    }

    /** WhatsApp-style input key: mic when the box is empty, send once typing. */
    private fun updateSendIcon() {
        binding.sendButton.setImageResource(
            if (binding.messageEdit.text.isNullOrEmpty()) R.drawable.ic_mic else R.drawable.ic_send
        )
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

    /**
     * Hold-to-record release: stop the clip and offer Send / Cancel instead
     * of auto-sending like the tap-to-record flow.
     */
    private fun finishHoldRecord() {
        binding.recordTimer.removeCallbacks(recordTick)
        VoiceNotes.stopRecording(voiceRecorder)
        voiceRecorder = null
        binding.recordBar.visibility = View.GONE
        binding.inputRow.visibility = View.VISIBLE
        val path = voicePath
        voicePath = null
        if (path == null) return // e.g. permission was requested; nothing recorded
        val secs = VoiceNotes.durationSec(path)
        if (secs < 1) {
            File(path).delete()
            Toast.makeText(this, getString(R.string.voice_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Send voice note (${VoiceNotes.fmt(secs)})?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AirWaveBle.sendVoiceNote(convId, path, secs)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                File(path).delete()
            }
            .setOnCancelListener { File(path).delete() }
            .show()
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
            sb.appendLine("AirWave group export — ${AirWaveBle.groupDisplayName(groupId)}")
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
            val file = File(out, "airwave_group_${System.currentTimeMillis()}.txt")
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

    // ---------------- v3.0: group admin ----------------

    private fun showRenameGroup() {
        val input = EditText(this).apply {
            setText(AirWaveBle.groupDisplayName(groupId))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_group))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                AirWaveBle.sendGroupName(groupId, input.text.toString())
                renderGroupHeader()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAvatarColorPicker() {
        val labels = AvatarUtil.AVATAR_COLORS.indices.map { i ->
            "${getString(R.string.group_avatar_color)} ${i + 1}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_avatar_color))
            .setItems(labels) { _, which ->
                AirWaveBle.sendGroupAvatar(groupId, which)
                renderGroupHeader()
            }
            .show()
    }

    /** Long-press a member chip: promote/demote/remove (admins only). */
    private fun showMemberAdminDialog(name: String) {
        if (!amIAdmin() || name == AirWaveBle.myName) return
        val role = AirWaveBle.groupRoleOf(groupId, name)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += getString(
            if (role == "admin") R.string.demote_admin else R.string.make_admin
        ) to {
            AirWaveBle.sendRole(groupId, name, if (role == "admin") "member" else "admin")
            renderMembers(AirWaveBle.groupMembers())
        }
        items += getString(R.string.remove_member) to {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.remove_member_confirm, name))
                .setPositiveButton(getString(R.string.remove_member)) { _, _ ->
                    AirWaveBle.sendKick(groupId, name)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    // ---------------- overflow ----------------

    private fun showOverflow(anchor: View) {
        val popup = PopupMenu(this, anchor)
        if (amIAdmin()) {
            popup.menu.add(0, 4, 0, getString(R.string.rename_group))
            popup.menu.add(0, 5, 0, getString(R.string.group_avatar_color))
        }
        popup.menu.add(0, 1, 0, getString(R.string.clear_chat))
        val muted = AirWaveBle.isMuted(convId)
        popup.menu.add(0, 6, 0, getString(if (muted) R.string.unmute_chat else R.string.mute_chat))
        popup.menu.add(0, 7, 0, getString(R.string.export_chat))
        // v4.1: disappearing + polls
        val dz = AirWaveBle.disappearSecs(convId)
        popup.menu.add(
            0, 8, 0,
            getString(R.string.disappearing) + if (dz > 0) " (${dz / 86400}d)" else ""
        )
        popup.menu.add(0, 9, 0, getString(R.string.create_poll))
        popup.menu.add(0, 2, 0, getString(R.string.leave_group))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmClearChat()
                8 -> showDisappearingDialog()
                9 -> showPollDialog()
                2 -> confirmLeave()
                4 -> showRenameGroup()
                5 -> showAvatarColorPicker()
                6 -> toggleMute()
                7 -> exportChat()
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

    private fun showGroupQr() {
        startActivity(Intent(this, QrActivity::class.java).apply {
            putExtra("qr_payload", "AIRWAVE_GROUP|$groupId|${AirWaveBle.myName}")
            putExtra("qr_title", getString(R.string.group_qr_title))
            putExtra("qr_note", getString(R.string.group_qr_note))
            putExtra("qr_name", AirWaveBle.groupDisplayName(groupId))
        })
    }

    // ---------------- members ----------------

    private fun renderMembers(members: List<String>) {
        binding.membersRow.removeAllViews()
        binding.memberCount.text = getString(R.string.members_count, members.size)
        for (name in members) {
            // v3.0: admins get a crown; long-press opens admin actions (admins only).
            // v3.2.7 (A9/B10): density-scaled dp values instead of raw pixels;
            // 12/6 padding, 8dp gap, 32dp min height per the shape spec.
            val admin = AirWaveBle.groupRoleOf(groupId, name) == "admin"
            val density = resources.displayMetrics.density
            val chip = TextView(this).apply {
                text = if (admin) "\uD83D\uDC51 $name" else name
                setTextColor(context.attrColor(com.google.android.material.R.attr.colorOnSurface))
                textSize = 12f
                setPadding(
                    (12 * density).toInt(), (6 * density).toInt(),
                    (12 * density).toInt(), (6 * density).toInt()
                )
                minHeight = (32 * density).toInt()
                background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                gravity = Gravity.CENTER
            }
            if (amIAdmin() && name != AirWaveBle.myName) {
                chip.setOnLongClickListener { showMemberAdminDialog(name); true }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, (8 * density).toInt(), 0) }
            binding.membersRow.addView(chip, lp)
        }
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.leave_confirm))
            .setPositiveButton(getString(R.string.leave_group)) { _, _ ->
                AirWaveBle.leaveGroup()
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
            } else {
                binding.typingText.text = getString(R.string.is_typing, name)
                binding.typingText.visibility = View.VISIBLE
                handler.postDelayed(clearTypingRunnable, 4000)
            }
        }
    }

    override fun onMembersChanged(groupId: String, members: List<String>) {
        if (groupId == this.groupId) runOnUiThread { renderMembers(members) }
    }

    /** v3.0: group renamed, avatar or my role changed. */
    override fun onGroupChanged(groupId: String) {
        if (groupId == this.groupId) {
            runOnUiThread {
                renderGroupHeader()
                renderMembers(AirWaveBle.groupMembers())
            }
        }
    }

    /** v3.0: removed by an admin. */
    override fun onKicked(groupId: String) {
        if (groupId == this.groupId) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.kicked_from_group), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        // v3.2.5: never leave a dead activity registered as the BLE listener.
        if (AirWaveBle.listener === this) AirWaveBle.listener = null
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
}
