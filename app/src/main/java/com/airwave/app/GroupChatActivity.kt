package com.airwave.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.airwave.app.databinding.ActivityGroupChatBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GroupChatActivity : BaseActivity(), AirWaveBle.Listener {

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

    // v3.0: gallery picker
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
        binding.sendButton.setOnClickListener { send() }
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
            sb.appendLine("AirWave group export — ${AirWaveBle.groupDisplayName(groupId)}")
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
        popup.menu.add(0, 2, 0, getString(R.string.leave_group))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmClearChat()
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
            val admin = AirWaveBle.groupRoleOf(groupId, name) == "admin"
            val chip = TextView(this).apply {
                text = if (admin) "\uD83D\uDC51 $name" else name
                setTextColor(context.attrColor(com.google.android.material.R.attr.colorOnSurface))
                textSize = 12f
                setPadding(20, 10, 20, 10)
                background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                gravity = Gravity.CENTER
            }
            if (amIAdmin() && name != AirWaveBle.myName) {
                chip.setOnLongClickListener { showMemberAdminDialog(name); true }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 12, 0) }
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
        super.onDestroy()
    }
}
