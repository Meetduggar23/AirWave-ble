package com.airwave.app

import android.content.Context
import android.content.SharedPreferences

/** Simple session + settings storage. Chats themselves are never persisted. */
object Prefs {
    private const val FILE = "airwave_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(v) = prefs.edit().putString("name", v).apply()

    /**
     * 0 = system default, 1..21 = named themes (see ThemeRepo).
     * One-time migration: old builds used 1 = light, 2 = dark; those values now
     * mean Dracula/Portfolio Dark, so stale values are reset to System default.
     */
    var themeMode: Int
        get() {
            val v = prefs.getInt("theme_mode", 0)
            if (!prefs.getBoolean("theme_migrated_v31", false)) {
                prefs.edit().putBoolean("theme_migrated_v31", true).apply()
                if (v == 1 || v == 2) {
                    prefs.edit().putInt("theme_mode", 0).apply()
                    return 0
                }
            }
            return v
        }
        set(v) {
            prefs.edit().putInt("theme_mode", v)
                .putBoolean("theme_migrated_v31", true).apply()
        }

    var language: String
        get() = prefs.getString("language", "en") ?: "en"
        set(v) = prefs.edit().putString("language", v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) = prefs.edit().putBoolean("notifications", v).apply()

    var discoverable: Boolean
        get() = prefs.getBoolean("discoverable", true)
        set(v) = prefs.edit().putBoolean("discoverable", v).apply()

    /** Index into AvatarUtil.AVATAR_COLORS for the user's own avatar. */
    var avatarColor: Int
        get() = prefs.getInt("avatar_color", 0)
        set(v) = prefs.edit().putInt("avatar_color", v).apply()

    /** Bluetooth addresses of blocked peers. */
    var blocked: MutableSet<String>
        get() = prefs.getStringSet("blocked", emptySet())?.toMutableSet() ?: mutableSetOf()
        set(v) = prefs.edit().putStringSet("blocked", v.toSet()).apply()

    /** Conversation ids with notifications muted. */
    var mutedConvs: MutableSet<String>
        get() = prefs.getStringSet("muted", emptySet())?.toMutableSet() ?: mutableSetOf()
        set(v) = prefs.edit().putStringSet("muted", v.toSet()).apply()

    /** Whether the onboarding screens have been shown. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()
}
