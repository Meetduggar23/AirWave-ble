package com.airwave.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class AirWaveApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.apply(base))
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        applyTheme()
        AirWaveBle.init(this)
    }

    companion object {
        fun applyTheme() {
            AppCompatDelegate.setDefaultNightMode(
                when (val mode = Prefs.themeMode) {
                    0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> if (ThemeRepo.isDark(mode)) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }
    }
}
