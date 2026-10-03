package com.airwave.app

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes

/** Resolves a theme attribute (e.g. R.attr.colorPrimary) to its color int on this context's theme. */
fun Context.attrColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/**
 * The 21 named color themes, in fixed order. Prefs.themeMode: 0 = System default,
 * 1..21 = index into [themes].
 */
object ThemeRepo {
    data class Theme(val name: String, @StyleRes val style: Int, val dark: Boolean)

    val themes: List<Theme> = listOf(
        // Dark
        Theme("Dracula", R.style.Theme_AirWave_Dracula, true),
        Theme("Portfolio Dark", R.style.Theme_AirWave_PortfolioDark, true),
        Theme("Dark 2026", R.style.Theme_AirWave_Dark2026, true),
        Theme("Abyss", R.style.Theme_AirWave_Abyss, true),
        Theme("Dark (Visual Studio)", R.style.Theme_AirWave_DarkVS, true),
        Theme("Dark Modern", R.style.Theme_AirWave_DarkModern, true),
        Theme("Dark+", R.style.Theme_AirWave_DarkPlus, true),
        Theme("Kimbie Dark", R.style.Theme_AirWave_KimbieDark, true),
        Theme("Monokai", R.style.Theme_AirWave_Monokai, true),
        Theme("Monokai Dimmed", R.style.Theme_AirWave_MonokaiDimmed, true),
        Theme("Red", R.style.Theme_AirWave_Red, true),
        Theme("Solarized Dark", R.style.Theme_AirWave_SolarizedDark, true),
        Theme("Tomorrow Night Blue", R.style.Theme_AirWave_TomorrowNightBlue, true),
        // Light
        Theme("Light 2026", R.style.Theme_AirWave_Light2026, false),
        Theme("Light (Visual Studio)", R.style.Theme_AirWave_LightVS, false),
        Theme("Light Modern", R.style.Theme_AirWave_LightModern, false),
        Theme("Light+", R.style.Theme_AirWave_LightPlus, false),
        Theme("Quiet Light", R.style.Theme_AirWave_QuietLight, false),
        Theme("Solarized Light", R.style.Theme_AirWave_SolarizedLight, false),
        // High Contrast
        Theme("Dark High Contrast", R.style.Theme_AirWave_DarkHC, true),
        Theme("Light High Contrast", R.style.Theme_AirWave_LightHC, false),
    )

    /** 0 -> base DayNight theme; 1..21 -> the named theme styles in order. */
    @StyleRes
    fun styleFor(themeMode: Int): Int =
        if (themeMode in 1..themes.size) themes[themeMode - 1].style
        else R.style.Theme_AirWave

    fun nameFor(themeMode: Int): String =
        if (themeMode in 1..themes.size) themes[themeMode - 1].name
        else "System default"

    fun isDark(themeMode: Int): Boolean =
        if (themeMode in 1..themes.size) themes[themeMode - 1].dark
        else false
}
