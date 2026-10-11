package com.airwave.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/** v4.1 app-lock gate. Launched by SplashActivity when AppLock.shouldLock(). */
class LockActivity : AppCompatActivity() {

    private var pin = ""
    private lateinit var dots: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setGravity(Gravity.CENTER)
            setBackgroundColor(0xFF082032.toInt())
            setPadding(48, 64, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "AirWave"; textSize = 30f
            setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        })
        dots = TextView(this).apply {
            textSize = 34f; setTextColor(0xFFFF4C29.toInt()); gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        root.addView(dots)
        val grid = GridLayout(this).apply {
            columnCount = 3
        }
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫").forEach { label ->
            val b = Button(this).apply {
                text = label; textSize = 24f
                setOnClickListener { onKey(label) }
            }
            b.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(14, 14, 14, 14)
            }
            grid.addView(b)
        }
        root.addView(grid)
        setContentView(root)
        updateDots()
        if (AppLock.useBiometric(this)) promptBiometric()
    }

    private fun onKey(label: String) {
        when (label) {
            "C" -> pin = ""
            "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
            else -> if (pin.length < 4) pin += label
        }
        updateDots()
        if (pin.length == 4) {
            if (AppLock.checkPin(this, pin)) unlock()
            else {
                pin = ""
                updateDots()
                Toast.makeText(this, R.string.pin_4_digits, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDots() {
        dots.text = "●".repeat(pin.length) + "○".repeat(4 - pin.length)
    }

    private fun promptBiometric() {
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) { unlock() }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock))
                .setNegativeButtonText(getString(android.R.string.cancel))
                .build()
        )
    }

    private fun unlock() {
        AppLock.unlocked = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Deprecated("back must not skip the lock")
    override fun onBackPressed() {
        // locked: ignore
    }
}
