package com.airwave.app

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.airwave.app.databinding.ActivityOnboardingBinding

/** First-run: 3 animated tutorial pages + name entry. Shown once. Pages are white by design. */
class OnboardingActivity : BaseActivity() {

    private lateinit var b: ActivityOnboardingBinding
    private val dotViews = mutableListOf<View>()
    private val animators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupDots()
        updateNav()

        b.nextButton.setOnClickListener {
            if (b.pageFlipper.displayedChild < 3) {
                b.pageFlipper.showNext()
                updateNav()
            }
        }
        b.skipButton.setOnClickListener {
            b.pageFlipper.displayedChild = 3
            updateNav()
        }
        b.continueButton.setOnClickListener { submit() }
        b.nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
        // v3.2.6: language + theme shortcuts on the name page.
        b.langButton.setOnClickListener { chooseLanguage() }
        b.themeButton.setOnClickListener { chooseTheme() }
        // Restore page after a recreate (e.g. language change on page 4).
        // updateNav() must re-run so the lang/theme icons, Next/Skip and dots
        // match the restored page, not the default page 1.
        savedInstanceState?.getInt("page", 0)?.let {
            b.pageFlipper.displayedChild = it.coerceIn(0, 3)
            updateNav()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", b.pageFlipper.displayedChild)
    }

    override fun onResume() {
        super.onResume()
        startAnims()
    }

    override fun onPause() {
        animators.forEach { it.cancel() }
        animators.clear()
        super.onPause()
    }

    private fun setupDots() {
        val density = resources.displayMetrics.density
        val size = (8 * density).toInt()
        val margin = (4 * density).toInt()
        val dotBg = ContextCompat.getDrawable(this, R.drawable.dot_page)
        repeat(4) {
            val v = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(margin, 0, margin, 0)
            v.layoutParams = lp
            v.background = dotBg?.constantState?.newDrawable()?.mutate()
            b.dotsRow.addView(v)
            dotViews.add(v)
        }
        updateDots()
    }

    private fun updateDots() {
        val active = attrColor(com.google.android.material.R.attr.colorPrimary)
        val idle = Color.parseColor("#D5DBE1")
        val current = b.pageFlipper.displayedChild
        dotViews.forEachIndexed { i, v ->
            ViewCompat.setBackgroundTintList(v, ColorStateList.valueOf(if (i == current) active else idle))
        }
    }

    private fun updateNav() {
        val last = b.pageFlipper.displayedChild == 3
        b.nextButton.visibility = if (last) View.GONE else View.VISIBLE
        b.skipButton.visibility = if (last) View.INVISIBLE else View.VISIBLE
        // v3.2.6: language + theme icons only on the name page.
        b.langButton.visibility = if (last) View.VISIBLE else View.GONE
        b.themeButton.visibility = if (last) View.VISIBLE else View.GONE
        updateDots()
    }

    private fun loop(animator: ObjectAnimator, durationMs: Long, reverse: Boolean = true): ObjectAnimator {
        animator.duration = durationMs
        animator.repeatMode = if (reverse) ValueAnimator.REVERSE else ValueAnimator.RESTART
        animator.repeatCount = ValueAnimator.INFINITE
        animator.start()
        animators.add(animator)
        return animator
    }

    private fun startAnims() {
        // Page 1: chat icon floats up and down.
        loop(ObjectAnimator.ofFloat(b.animIcon1, "translationY", 0f, -28f), 1500)

        // Page 2: radar icon gently pulses.
        loop(ObjectAnimator.ofFloat(b.animIcon2, "scaleX", 1f, 1.15f), 1200)
        loop(ObjectAnimator.ofFloat(b.animIcon2, "scaleY", 1f, 1.15f), 1200)

        // Page 2: ring expands and fades out, repeatedly.
        loop(ObjectAnimator.ofFloat(b.pulseRing, "scaleX", 1f, 1.9f), 1700, reverse = false)
        loop(ObjectAnimator.ofFloat(b.pulseRing, "scaleY", 1f, 1.9f), 1700, reverse = false)
        loop(ObjectAnimator.ofFloat(b.pulseRing, "alpha", 0.7f, 0f), 1700, reverse = false)

        // Page 3: group icon gently pulses.
        loop(ObjectAnimator.ofFloat(b.animIcon3, "scaleX", 1f, 1.1f), 1400)
        loop(ObjectAnimator.ofFloat(b.animIcon3, "scaleY", 1f, 1.1f), 1400)
    }

    private fun submit() {
        val name = b.nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.name_required), Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.name = name
        Prefs.onboarded = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** v3.2.6: language picker on the name page. recreate() is enough here —
     *  onboarding is the only activity in the task on first run. */
    private fun chooseLanguage() {
        val codes = arrayOf("en", "hi", "bn", "mr", "te", "ta", "gu")
        val items = arrayOf("English", "हिन्दी", "বাংলা", "मराठी", "తెలుగు", "தமிழ்", "ગુજરાતી")
        val current = codes.indexOf(Prefs.language).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.language))
            .setSingleChoiceItems(items, current) { dialog, which ->
                dialog.dismiss()
                if (codes[which] != Prefs.language) {
                    Prefs.language = codes[which]
                    recreate()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** v3.2.6: theme picker on the name page. */
    private fun chooseTheme() {
        val items = Array(ThemeRepo.themes.size + 1) { i -> ThemeRepo.nameFor(i) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.theme))
            .setSingleChoiceItems(
                items,
                Prefs.themeMode.coerceIn(0, ThemeRepo.themes.size)
            ) { dialog, which ->
                Prefs.themeMode = which
                AirWaveApp.applyTheme()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
