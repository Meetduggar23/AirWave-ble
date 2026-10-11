package com.airwave.app

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID

/**
 * v4.0 voice notes. Recording is AAC in .m4a (mono 22kHz, 32kbps) —
 * small enough for the BLE chunk pipeline. Playback is a single shared
 * MediaPlayer: only one voice note plays at a time.
 */
object VoiceNotes {

    const val MAX_SEC = 30

    fun voiceDir(ctx: Context): File = File(ctx.filesDir, "voice").apply { mkdirs() }

    fun newPath(ctx: Context): String =
        File(voiceDir(ctx), "${UUID.randomUUID()}.m4a").absolutePath

    /** Starts recording to outPath. Returns null on failure. */
    fun startRecording(ctx: Context, outPath: String): MediaRecorder? {
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(22050)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(outPath)
            r.prepare()
            r.start()
            r
        } catch (_: Exception) {
            null
        }
    }

    fun stopRecording(r: MediaRecorder?) {
        try {
            r?.stop()
        } catch (_: Exception) {
        }
        try {
            r?.release()
        } catch (_: Exception) {
        }
    }

    /** Whole-second duration of an .m4a file; 0 on failure. */
    fun durationSec(path: String): Int {
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            val s = (mp.duration / 1000).coerceAtLeast(1)
            mp.release()
            s
        } catch (_: Exception) {
            0
        }
    }

    fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    /** Single-player for voice bubbles. */
    object Player {
        private var mp: MediaPlayer? = null
        private var playingId: String? = null
        private val handler = Handler(Looper.getMainLooper())
        private var tick: Runnable? = null

        fun isPlaying(id: String): Boolean =
            playingId == id && mp?.isPlaying == true

        fun toggle(
            id: String,
            path: String?,
            onTick: (posMs: Int, durMs: Int) -> Unit,
            onDone: () -> Unit
        ) {
            if (isPlaying(id)) {
                stop()
                onDone()
                return
            }
            stop()
            val p = path ?: return
            try {
                val m = MediaPlayer()
                m.setDataSource(p)
                m.prepare()
                playingId = id
                mp = m
                val dur = m.duration
                m.setOnCompletionListener { stop(); onDone() }
                m.start()
                tick = object : Runnable {
                    override fun run() {
                        val cur = try {
                            m.currentPosition
                        } catch (_: Exception) {
                            -1
                        }
                        if (cur >= 0 && m.isPlaying) {
                            onTick(cur, dur)
                            handler.postDelayed(this, 250)
                        }
                    }
                }
                handler.post(tick!!)
            } catch (_: Exception) {
                stop()
                onDone()
            }
        }

        fun stop() {
            tick?.let { handler.removeCallbacks(it) }
            tick = null
            try {
                mp?.stop()
            } catch (_: Exception) {
            }
            try {
                mp?.release()
            } catch (_: Exception) {
            }
            mp = null
            playingId = null
        }
    }
}
