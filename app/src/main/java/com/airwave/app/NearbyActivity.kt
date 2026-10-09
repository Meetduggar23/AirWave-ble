package com.airwave.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import com.airwave.app.databinding.ActivityNearbyBinding
import com.airwave.app.databinding.ItemPeerBinding

class NearbyActivity : BaseActivity(), AirWaveBle.Listener {

    private lateinit var binding: ActivityNearbyBinding
    private lateinit var adapter: PeerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNearbyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.rescanButton.setOnClickListener {
            AirWaveBle.ensureScanning()
            refresh()
            Toast.makeText(this, getString(R.string.scanning), Toast.LENGTH_SHORT).show()
        }
        binding.scanQrButton.setOnClickListener {
            startActivity(Intent(this, QrScanActivity::class.java))
        }
        binding.emptyScanQr.setOnClickListener {
            startActivity(Intent(this, QrScanActivity::class.java))
        }

        adapter = PeerAdapter()
        binding.peersList.adapter = adapter
        binding.peersList.setOnItemClickListener { _, _, position, _ ->
            val peer = adapter.getItem(position)
            if (peer.groupId != null) {
                // Join their group
                AirWaveBle.joinGroup(peer)
                val intent = Intent(this, GroupChatActivity::class.java).apply {
                    putExtra("group_id", peer.groupId)
                    putExtra("is_host", false)
                }
                startActivity(intent)
            } else {
                // 1-to-1 chat
                AirWaveBle.connect(peer.address)
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("peer_name", peer.name)
                    putExtra("peer_address", peer.address)
                }
                startActivity(intent)
            }
        }

        binding.createGroupButton.setOnClickListener {
            if (!Prefs.discoverable) {
                Toast.makeText(
                    this,
                    getString(R.string.enable_discoverable_to_host),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val gid = AirWaveBle.startGroup()
            val intent = Intent(this, GroupChatActivity::class.java).apply {
                putExtra("group_id", gid)
                putExtra("is_host", true)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        AirWaveBle.listener = this
        AirWaveBle.ensureScanning()
        refresh()
    }

    override fun onDestroy() {
        // v3.2.7 (A5): never leave a dead activity registered as the BLE
        // listener — every other listener activity has this guard.
        if (AirWaveBle.listener === this) AirWaveBle.listener = null
        super.onDestroy()
    }

    private fun refresh() {
        val peers = AirWaveBle.peersList()
        adapter.setPeers(peers)
        binding.emptyView.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onPeerFound(peer: AirWaveBle.Peer) = runOnUiThread { refresh() }

    private inner class PeerAdapter : BaseAdapter() {
        private var peers: List<AirWaveBle.Peer> = emptyList()
        fun setPeers(p: List<AirWaveBle.Peer>) {
            peers = p
            notifyDataSetChanged()
        }

        override fun getCount() = peers.size
        override fun getItem(position: Int) = peers[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = convertView?.tag as? ItemPeerBinding
                ?: ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    .also { it.root.tag = it }
            val peer = getItem(position)
            b.peerName.text = peer.name
            b.peerAddress.text = peer.address
            b.peerInitial.text = peer.name.firstOrNull()?.uppercase() ?: "?"
            AvatarUtil.tintAvatar(b.peerInitial.parent as View, AvatarUtil.colorFor(peer.name))
            if (peer.groupId != null) {
                b.groupBadge.visibility = View.VISIBLE
                b.groupBadge.text = getString(R.string.group_badge, peer.groupId)
            } else {
                b.groupBadge.visibility = View.GONE
            }
            return b.root
        }
    }
}
