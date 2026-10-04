package com.airwave.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.google.zxing.integration.android.IntentIntegrator

/** Scans an AirWave QR code (person or group) and jumps straight into the chat. */
class QrScanActivity : BaseActivity() {

    companion object {
        private const val REQ_CAMERA = 50
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startScan()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA
            )
        }
    }

    private fun startScan() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(getString(R.string.scan_qr_prompt))
            .setBeepEnabled(false)
            // v3.2.6: portrait scanner (default CaptureActivity is landscape).
            .setCaptureActivity(PortraitScannerActivity::class.java)
            .initiateScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan()
            } else {
                Toast.makeText(this, getString(R.string.camera_needed), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    @Deprecated("zxing scanner result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val contents = result.contents
        if (contents == null) {
            finish()
            return
        }
        handlePayload(contents)
    }

    private fun openChat(peer: AirWaveBle.Peer) {
        AirWaveBle.connect(peer.address)
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra("peer_name", peer.name)
            putExtra("peer_address", peer.address)
        })
        finish()
    }

    private fun handlePayload(payload: String) {
        val parts = payload.split('|')
        when {
            parts.size == 2 && parts[0] == "AIRWAVE" -> {
                val name = parts[1]
                val matches = AirWaveBle.peersList()
                    .filter { it.name.equals(name, ignoreCase = true) }
                when {
                    matches.size == 1 -> openChat(matches[0])
                    matches.size > 1 -> {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.choose_peer))
                            .setItems(
                                matches.map { "${it.name} (${it.address})" }.toTypedArray()
                            ) { _, which -> openChat(matches[which]) }
                            .setOnCancelListener { finish() }
                            .show()
                    }
                    else -> {
                        Toast.makeText(
                            this, getString(R.string.peer_not_found, name), Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            }
            parts.size == 3 && parts[0] == "AIRWAVE_GROUP" -> {
                val groupId = parts[1]
                val peer = AirWaveBle.peersList().firstOrNull { it.groupId == groupId }
                if (peer != null) {
                    AirWaveBle.joinGroup(peer)
                    startActivity(Intent(this, GroupChatActivity::class.java).apply {
                        putExtra("group_id", groupId)
                        putExtra("is_host", false)
                    })
                    finish()
                } else {
                    Toast.makeText(
                        this, getString(R.string.peer_not_found, groupId), Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
            else -> {
                Toast.makeText(this, getString(R.string.qr_invalid), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
