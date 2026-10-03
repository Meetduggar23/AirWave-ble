package com.airwave.app

import android.os.Bundle
import android.widget.Toast
import com.airwave.app.databinding.ActivityQrBinding

class QrActivity : BaseActivity() {

    private lateinit var binding: ActivityQrBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val name = intent.getStringExtra("qr_name")
            ?: Prefs.name.ifBlank { "AirWave" }
        val payload = intent.getStringExtra("qr_payload")
            ?: "AIRWAVE|${Prefs.name.ifBlank { "AirWave" }}"

        binding.qrTitle.text = intent.getStringExtra("qr_title") ?: getString(R.string.my_qr)
        binding.qrNote.text = intent.getStringExtra("qr_note") ?: getString(R.string.qr_note)
        binding.qrName.text = name

        val bmp = QrGenerator.generate(payload)
        if (bmp != null) {
            binding.qrImage.setImageBitmap(bmp)
        } else {
            Toast.makeText(this, "QR failed", Toast.LENGTH_SHORT).show()
        }
    }
}
