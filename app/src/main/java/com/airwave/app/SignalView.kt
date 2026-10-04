package com.airwave.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Tiny 4-bar BLE signal indicator for chat headers. Level 0 = hidden. */
class SignalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Int = 0
        set(v) {
            field = v.coerceIn(0, 4)
            invalidate()
        }

    /** Map an RSSI dBm reading to bars 1-4 (Int.MIN_VALUE = unknown -> 0). */
    fun setRssi(rssi: Int) {
        level = when {
            rssi == Int.MIN_VALUE -> 0
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            else -> 1
        }
    }

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // v3.2.6: follow the selected theme (was hardcoded accent orange in every theme).
        color = context.attrColor(com.google.android.material.R.attr.colorPrimary)
        style = Paint.Style.FILL
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val c = context.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        color = android.graphics.Color.argb(64,
            android.graphics.Color.red(c), android.graphics.Color.green(c),
            android.graphics.Color.blue(c))
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = width / 7f
        val bw = gap * 1.2f
        for (i in 0 until 4) {
            val h = height * (0.3f + 0.7f * (i + 1) / 4f)
            val left = gap * 0.4f + i * (bw + gap * 0.55f)
            canvas.drawRoundRect(left, height - h, left + bw, height.toFloat(), 2f, 2f,
                if (i < level) onPaint else offPaint)
        }
    }
}
