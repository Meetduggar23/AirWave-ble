package com.airwave.app

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator

/** Branded launch screen. Animated logo, then hands off to MainActivity. */
class SplashActivity : BaseActivity() {

    private val animators = mutableListOf<Animator>()
    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        playEntrance()

        handler.postDelayed({
            navigated = true
            val next = if (Prefs.onboarded) MainActivity::class.java else OnboardingActivity::class.java
            startActivity(Intent(this, next))
            finish()
        }, 1100)
    }

    /** v3.2.6: logo pops in with a glow ring pulse, wordmark + tagline fade up after. */
    private fun playEntrance() {
        // Logo: starts small and transparent, springs up.
        val logoX = ObjectAnimator.ofFloat(findViewById<View>(R.id.splashLogo), "scaleX", 0.4f, 1f)
        val logoY = ObjectAnimator.ofFloat(findViewById<View>(R.id.splashLogo), "scaleY", 0.4f, 1f)
        val logoA = ObjectAnimator.ofFloat(findViewById<View>(R.id.splashLogo), "alpha", 0f, 1f)
        val logoPop = AnimatorSet().apply {
            playTogether(logoX, logoY, logoA)
            duration = 520
            interpolator = OvershootInterpolator(1.6f)
        }

        // Glow ring behind the logo expands once, fading out.
        val glow = findViewById<View>(R.id.splashGlow)
        val glowSet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glow, "scaleX", 0.6f, 1.7f),
                ObjectAnimator.ofFloat(glow, "scaleY", 0.6f, 1.7f),
                ObjectAnimator.ofFloat(glow, "alpha", 0.8f, 0f)
            )
            duration = 900
            interpolator = DecelerateInterpolator()
        }

        // Wordmark + tagline rise into place after the logo lands.
        val name = findViewById<View>(R.id.splashName)
        name.translationY = 24f
        name.alpha = 0f
        val nameUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(name, "translationY", 24f, 0f),
                ObjectAnimator.ofFloat(name, "alpha", 0f, 1f)
            )
            duration = 380
            startDelay = 320
            interpolator = DecelerateInterpolator()
        }

        val tagline = findViewById<View>(R.id.splashTagline)
        tagline.translationY = 16f
        val tagUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(tagline, "translationY", 16f, 0f),
                ObjectAnimator.ofFloat(tagline, "alpha", 0f, 1f)
            )
            duration = 380
            startDelay = 480
            interpolator = DecelerateInterpolator()
        }

        AnimatorSet().apply {
            playTogether(logoPop, glowSet, nameUp, tagUp)
            start()
            animators.add(this)
        }
    }

    override fun onPause() {
        // v3.2.6: cancel animations when leaving the splash (matches onboarding pattern).
        animators.forEach { it.cancel() }
        animators.clear()
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // If we were paused mid-countdown, never strand the user on the splash.
        if (animators.isEmpty() && !navigated && !isFinishing) {
            handler.postDelayed({
                navigated = true
                val next = if (Prefs.onboarded) MainActivity::class.java else OnboardingActivity::class.java
                startActivity(Intent(this, next))
                finish()
            }, 400)
        }
    }
}
