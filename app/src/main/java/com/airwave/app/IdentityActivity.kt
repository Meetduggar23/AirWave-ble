package com.airwave.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.airwave.app.databinding.ActivityIdentityBinding

class IdentityActivity : BaseActivity() {

    private lateinit var binding: ActivityIdentityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIdentityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.changeNameButton.setOnClickListener { changeName() }
        binding.qrButton2.setOnClickListener {
            startActivity(Intent(this, QrActivity::class.java))
        }

        buildColorRow()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val name = Prefs.name.ifBlank { getString(R.string.unnamed) }
        binding.identityName.text = name
        binding.identityInitial.text = name.firstOrNull()?.uppercase() ?: "?"
        AvatarUtil.tintAvatar(binding.identityAvatar, AvatarUtil.myColor())
        highlightSelected()
    }

    private fun buildColorRow() {
        binding.colorRow.removeAllViews()
        AvatarUtil.AVATAR_COLORS.forEachIndexed { index, color ->
            val dot = View(this).apply {
                background = getDrawable(R.drawable.bg_badge)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                tag = index
                setOnClickListener {
                    Prefs.avatarColor = index
                    AvatarUtil.tintAvatar(binding.identityAvatar, AvatarUtil.myColor())
                    highlightSelected()
                }
            }
            // v3.2.7 (A9): density-scaled — matches the onboarding swatches
            // instead of raw 96px that shrank on high-density screens.
            val d = resources.displayMetrics.density
            val lp = LinearLayout.LayoutParams(
                (34 * d).toInt(), (34 * d).toInt()
            ).apply { setMargins((6 * d).toInt(), 0, (6 * d).toInt(), 0) }
            binding.colorRow.addView(dot, lp)
        }
    }

    private fun highlightSelected() {
        for (i in 0 until binding.colorRow.childCount) {
            val dot = binding.colorRow.getChildAt(i)
            val selected = (dot.tag as? Int) == Prefs.avatarColor
            dot.alpha = if (selected) 1f else 0.45f
            dot.scaleX = if (selected) 1.15f else 1f
            dot.scaleY = if (selected) 1.15f else 1f
        }
    }

    private fun changeName() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(Prefs.name)
            hint = getString(R.string.enter_name)
            // v3.2.7 (A9): density-scaled instead of raw pixels.
            val d = resources.displayMetrics.density
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.change_name))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    Prefs.name = name
                    AirWaveBle.setName(name)
                    render()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
