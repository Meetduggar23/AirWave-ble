package com.airwave.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * v4.0 fixed: embedded scanner.
 * The old IntentIntegrator external-activity flow (initiateScan +
 * onActivityResult) failed on several devices. The camera now runs inside
 * this activity via DecoratedBarcodeView; results come through
 * BarcodeCallback — no onActivityResult involved.
 */
class QrScanActivity : BaseActivity() {

    companion object {
        private const val REQ_CAMERA = 50
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private var torchOn = false
    private var handled = false

    private val callback = BarcodeCallback { result: BarcodeResult? ->
        val text = result?.text
        if (!handled && !text.isNullOrBlank()) {
            handled = true
            barcodeView.pause()
            handlePayload(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)
        barcodeView = findViewById(R.id.barcodeView)
        barcodeView.decodeContinuous(callback)
        findViewById<View>(R.id.torchButton).setOnClickListener {
            torchOn = !torchOn
            if (torchOn) barcodeView.setTorchOn() else barcodeView.setTorchOff()
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA
            )
        }
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) {
            Toast.makeText(this, getString(R.string.camera_needed), Toast.LENGTH_SHORT).show()
            finish()
        }
        // Granted: onResume() restarts the preview.
    }

    private fun openChat(peer: AirWaveBle.Peer) {
        // v4.0: stable contact — reopens the existing thread if there is one.
        val contactId = AirWaveBle.ensureContact(peer.address, peer.name)
        AirWaveBle.connect(peer.address)
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra("contact_id", contactId)
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
