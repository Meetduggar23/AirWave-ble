package com.airwave.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import com.airwave.app.databinding.ActivityMainBinding
import com.airwave.app.databinding.ItemChatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity(), AirWaveBle.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatsAdapter: ChatConvAdapter

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_ENABLE_BT = 2
        private const val REQ_NOTIF = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First run: 4-page onboarding (3 animated tutorial pages + name entry).
        if (!Prefs.onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        routeFromNotification(intent)

        AirWaveBle.setName(Prefs.name)
        if (Prefs.name.isBlank()) askForName()

        binding.bluetoothToggle.isChecked = AirWaveBle.isOnline()
        binding.bluetoothToggle.setOnCheckedChangeListener { _, checked ->
            if (checked) turnOnline() else {
                AirWaveBle.goOffline()
                refreshStatus()
                refreshChats()
            }
        }

        binding.findButton.setOnClickListener {
            if (!AirWaveBle.isOnline()) {
                Toast.makeText(this, getString(R.string.turn_bluetooth_on_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, NearbyActivity::class.java))
        }

        binding.identityCard.setOnClickListener {
            startActivity(Intent(this, IdentityActivity::class.java))
        }
        binding.settingsCard.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.qrButton.setOnClickListener {
            startActivity(Intent(this, QrActivity::class.java))
        }

        // Drawer
        binding.menuButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.navHome.setOnClickListener { closeDrawer() }
        binding.navNearby.setOnClickListener {
            closeDrawer()
            binding.findButton.performClick()
        }
        binding.navScanQr.setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, QrScanActivity::class.java))
        }
        binding.navIdentity.setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, IdentityActivity::class.java))
        }
        binding.navMyQr.setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, QrActivity::class.java))
        }
        binding.navSettings.setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.navAbout.setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.navHistory.setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Chats list
        chatsAdapter = ChatConvAdapter()
        binding.chatsList.adapter = chatsAdapter
        binding.chatsList.setOnItemClickListener { _, _, position, _ ->
            val summary = chatsAdapter.getItem(position)
            if (summary.isGroup) {
                startActivity(Intent(this, GroupChatActivity::class.java).apply {
                    putExtra("group_id", summary.groupId)
                    putExtra("is_host", AirWaveBle.hostingGroupId == summary.groupId)
                })
            } else {
                startActivity(Intent(this, ChatActivity::class.java).apply {
                    putExtra("peer_name", summary.title)
                    putExtra("peer_address", summary.peerAddress)
                })
            }
        }
    }

    private fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    /** v3.2.5: tapping a message notification jumps straight into that chat. */
    private fun routeFromNotification(intent: Intent?) {
        val convId = intent?.getStringExtra(NotificationHelper.EXTRA_CONV_ID) ?: return
        val canonical = AirWaveBle.canonicalConvId(convId)
        if (canonical.startsWith("group:")) {
            val gid = canonical.removePrefix("group:")
            startActivity(Intent(this, GroupChatActivity::class.java).apply {
                putExtra("group_id", gid)
                putExtra("is_host", AirWaveBle.hostingGroupId == gid)
            })
        } else {
            val address = canonical.removePrefix("${AirWaveBle.DM}:")
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra("peer_name", AirWaveBle.nameFor(address))
                putExtra("peer_address", address)
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFromNotification(intent)
        refreshChats()
    }

    override fun onResume() {
        super.onResume()
        AirWaveBle.listener = this
        AirWaveBle.setName(Prefs.name)
        binding.greeting.text = getString(R.string.hello_user, Prefs.name.ifBlank { "AirWave" })
        binding.identityName.text = Prefs.name.ifBlank { getString(R.string.tap_to_set_name) }
        binding.identityInitial.text = Prefs.name.firstOrNull()?.uppercase() ?: "A"
        AvatarUtil.tintAvatar(binding.identityAvatar, AvatarUtil.myColor())
        binding.drawerName.text = Prefs.name.ifBlank { getString(R.string.tap_to_set_name) }
        binding.drawerInitial.text = Prefs.name.firstOrNull()?.uppercase() ?: "A"
        AvatarUtil.tintAvatar(binding.drawerAvatar, AvatarUtil.myColor())
        binding.bluetoothToggle.isChecked = AirWaveBle.isOnline()
        refreshStatus()
        refreshChats()
    }

    private fun refreshStatus() {
        val online = AirWaveBle.isOnline()
        binding.bluetoothState.text = getString(if (online) R.string.ready else R.string.off)
        binding.bluetoothDot.setImageResource(if (online) R.drawable.dot_green else R.drawable.dot_red)
        binding.connectionState.text = AirWaveBle.connectionSummary()
        val connected = online && AirWaveBle.connectionSummary() != getString(R.string.not_connected)
        binding.connectionDot.setImageResource(if (connected) R.drawable.dot_green else R.drawable.dot_red)
    }

    private fun refreshChats() {
        val summaries = AirWaveBle.conversationSummaries()
        chatsAdapter.setSummaries(summaries)
        binding.chatsEmpty.visibility = if (summaries.isEmpty()) View.VISIBLE else View.GONE
        val total = AirWaveBle.totalUnread()
        binding.chatsBadge.visibility = if (total > 0) View.VISIBLE else View.GONE
        binding.chatsBadge.text = total.toString()
    }

    private fun turnOnline() {
        if (!hasPermissions()) {
            requestBlePermissions()
            binding.bluetoothToggle.isChecked = false
            return
        }
        if (!AirWaveBle.isBluetoothOn()) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            binding.bluetoothToggle.isChecked = false
            return
        }
        AirWaveBle.goOnline()
        refreshStatus()
        maybeAskNotificationPermission()
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && Prefs.notificationsEnabled &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    // ---------------- name ----------------

    private fun askForName() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.enter_name)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.welcome_title))
            .setMessage(getString(R.string.welcome_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.enter_name), Toast.LENGTH_SHORT).show()
                    askForName()
                } else {
                    Prefs.name = name
                    AirWaveBle.setName(name)
                    onResume()
                }
            }
            .show()
    }

    // ---------------- permissions ----------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestBlePermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            turnOnline()
            binding.bluetoothToggle.isChecked = true
        } else if (requestCode == REQ_PERMISSIONS) {
            Toast.makeText(this, getString(R.string.grant_permissions), Toast.LENGTH_LONG).show()
            binding.bluetoothToggle.isChecked = false
        }
    }

    @Deprecated("BT enable prompt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT) {
            if (AirWaveBle.isBluetoothOn()) {
                AirWaveBle.goOnline()
                binding.bluetoothToggle.isChecked = true
            } else {
                binding.bluetoothToggle.isChecked = false
            }
            refreshStatus()
        }
    }

    // ---------------- chats list ----------------

    private inner class ChatConvAdapter : BaseAdapter() {
        private var summaries: List<AirWaveBle.ConvSummary> = emptyList()
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun setSummaries(s: List<AirWaveBle.ConvSummary>) {
            summaries = s
            notifyDataSetChanged()
        }

        override fun getCount() = summaries.size
        override fun getItem(position: Int) = summaries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = convertView?.tag as? ItemChatBinding
                ?: ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    .also { it.root.tag = it }
            val s = getItem(position)
            // v3.0: muted chats show a 🔇 prefix; groups use their tinted avatar.
            b.chatName.text =
                if (AirWaveBle.isMuted(s.convId)) "\uD83D\uDD07 ${s.title}" else s.title
            b.chatInitial.text = s.title.firstOrNull()?.uppercase() ?: "?"
            val color = if (s.isGroup && s.groupId != null) {
                AirWaveBle.groupAvatarColor(s.groupId)
            } else {
                AvatarUtil.colorFor(s.title)
            }
            AvatarUtil.tintAvatar(b.chatAvatar, color)
            b.chatSnippet.text = s.snippet
            b.chatTime.text = timeFmt.format(Date(s.time))
            b.chatBadge.visibility = if (s.unread > 0) View.VISIBLE else View.GONE
            b.chatBadge.text = s.unread.toString()
            return b.root
        }
    }

    // ---------------- AirWaveBle.Listener ----------------

    override fun onStatus(text: String) = runOnUiThread { refreshStatus() }
    override fun onConnectionChanged(address: String, connected: Boolean) =
        runOnUiThread { refreshStatus() }
    override fun onMessage(convId: String, msg: AirWaveBle.ChatMessage) =
        runOnUiThread { refreshChats() }
    override fun onMessageUpdated(convId: String) = runOnUiThread { refreshChats() }
    override fun onUnreadChanged() = runOnUiThread { refreshChats() }

    override fun onDestroy() {
        // v3.2.5: never leave a dead activity registered as the BLE listener.
        if (AirWaveBle.listener === this) AirWaveBle.listener = null
        super.onDestroy()
    }
}
