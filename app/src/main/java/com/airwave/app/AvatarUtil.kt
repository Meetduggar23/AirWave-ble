package com.airwave.app

import android.content.res.ColorStateList
import android.view.View

/** Per-user avatar colors, derived deterministically from a name. */
object AvatarUtil {

    val AVATAR_COLORS = intArrayOf(
        0xFFE57373.toInt(),
        0xFFFFB74D.toInt(),
        0xFFFFF176.toInt(),
        0xFF81C784.toInt(),
        0xFF4FC3F7.toInt(),
        0xFFBA68C8.toInt(),
        0xFFFF8A65.toInt(),
        0xFF90A4AE.toInt()
    )

    /** Stable color for any peer name (used for sender names + avatars). */
    fun colorFor(name: String): Int =
        AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size]

    /** The user's own chosen avatar color. */
    fun myColor(): Int {
        val i = Prefs.avatarColor % AVATAR_COLORS.size
        return AVATAR_COLORS[if (i < 0) i + AVATAR_COLORS.size else i]
    }

    /** Tint an avatar_circle background with the given color. */
    fun tintAvatar(view: View, color: Int) {
        view.backgroundTintList = ColorStateList.valueOf(color)
    }
}
