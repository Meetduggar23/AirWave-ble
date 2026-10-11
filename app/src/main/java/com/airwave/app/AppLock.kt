package com.airwave.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/** v4.1 app lock: 4-digit PIN (SHA-256 + salt) + optional biometric. */
object AppLock {
    private const val PREF = "airwave_lock"
    private const val K_ENABLED = "enabled"
    private const val K_HASH = "pin_hash"
    private const val K_SALT = "pin_salt"
    private const val K_BIO = "biometric"

    @Volatile
    var unlocked = false

    /**
     * Once encrypted storage fails on a device, stick to plain prefs for the
     * rest of the process so every read/write hits the same store.
     */
    @Volatile
    private var useFallback = false

    private fun prefs(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        if (useFallback) return app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return try {
            val mk = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, PREF, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // AndroidKeyStore unavailable on this device/ROM (work profile,
            // dual/cloned apps, locked keystore...). A lock that crashes the
            // app is worse than a lock without hardware encryption.
            useFallback = true
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, false)
    fun useBiometric(ctx: Context): Boolean = prefs(ctx).getBoolean(K_BIO, false)
    fun setBiometric(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(K_BIO, v).apply()
    }

    fun setPin(ctx: Context, pin: String) {
        require(pin.length == 4)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_HASH, Base64.encodeToString(sha(pin, salt), Base64.NO_WRAP))
            .putBoolean(K_ENABLED, true)
            .apply()
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString(K_SALT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val want = p.getString(K_HASH, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return sha(pin, salt).contentEquals(want)
    }

    fun disable(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        unlocked = false
    }

    /** Never throws: a lock failure must never kill the app launch. */
    fun shouldLock(ctx: Context): Boolean = try {
        isEnabled(ctx) && !unlocked
    } catch (_: Exception) {
        false
    }

    private fun sha(pin: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(pin.toByteArray())
        return md.digest()
    }
}
