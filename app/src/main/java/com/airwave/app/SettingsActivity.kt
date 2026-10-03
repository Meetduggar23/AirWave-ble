package com.airwave.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.airwave.app.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.themeRow.setOnClickListener { chooseTheme() }
        binding.languageRow.setOnClickListener { chooseLanguage() }
        binding.aboutRow.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.clearDataRow.setOnClickListener { confirmClear() }

        binding.notificationsSwitch.isChecked = Prefs.notificationsEnabled
        binding.notificationsSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.notificationsEnabled = checked
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }

        binding.discoverableSwitch.isChecked = Prefs.discoverable
        binding.discoverableSwitch.setOnCheckedChangeListener { _, checked ->
            AirWaveBle.setDiscoverable(checked)
            val msg = if (checked) getString(R.string.discoverable_on) else getString(R.string.discoverable_off)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.themeValue.text = ThemeRepo.nameFor(Prefs.themeMode)
        binding.languageValue.text = when (Prefs.language) {
            "hi" -> "हिन्दी"
            "bn" -> "বাংলা"
            "mr" -> "मराठी"
            "te" -> "తెలుగు"
            "ta" -> "தமிழ்"
            "gu" -> "ગુજરાતી"
            else -> "English"
        }
    }

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
                    restartApp()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * v3.2.6: relaunch the whole task so EVERY screen picks up the new
     * locale via attachBaseContext. recreate() alone only refreshed the
     * Settings screen and left the rest of the app in the old language.
     */
    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.clear_confirm))
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                AirWaveBle.clearSession()
                Toast.makeText(this, getString(R.string.session_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
