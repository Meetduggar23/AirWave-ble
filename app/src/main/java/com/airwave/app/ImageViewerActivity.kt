package com.airwave.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.airwave.app.databinding.ActivityImageViewerBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v3.0 full-screen preview of a received or sent image, with optional save. */
class ImageViewerActivity : BaseActivity() {

    private lateinit var b: ActivityImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val convId = intent.getStringExtra("convId") ?: ""
        val msgId = intent.getStringExtra("msgId") ?: ""
        val msg = AirWaveBle.messageById(convId, msgId)
        val path = msg?.imagePath
        if (msg == null || path == null) {
            finish()
            return
        }

        val bmp = try {
            BitmapFactory.decodeFile(path)
        } catch (_: Exception) {
            null
        }
        if (bmp == null) {
            Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        b.fullImage.setImageBitmap(bmp)
        b.imageSender.text = msg.sender
        b.imageTime.text = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
            .format(Date(msg.time))
        if (msg.caption.isNotBlank()) {
            b.imageCaption.visibility = View.VISIBLE
            b.imageCaption.text = msg.caption
        }
        b.backButton.setOnClickListener { finish() }
        b.saveButton.setOnClickListener { saveImage(File(path)) }
    }

    private fun saveImage(src: File) {
        try {
            val out = File(cacheDir, "exports").apply { mkdirs() }
            val dst = File(out, "airwave_${System.currentTimeMillis()}.jpg")
            src.copyTo(dst, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dst)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.save_image)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
