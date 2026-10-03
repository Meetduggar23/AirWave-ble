package com.airwave.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun apply(context: Context): Context {
        val lang = context.getSharedPreferences("airwave_prefs", Context.MODE_PRIVATE)
            .getString("language", "en") ?: "en"
        if (lang == "en") return context
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
