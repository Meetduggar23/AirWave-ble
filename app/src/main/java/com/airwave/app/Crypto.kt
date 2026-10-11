package com.airwave.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v4.1 end-to-end encryption — 1-to-1 DMs only.
 * X25519 (BouncyCastle) key agreement during the HELLO handshake,
 * AES-256-GCM per payload. Groups are NOT encrypted in v4.1.
 *
 * Keystore hardening: on devices where the AndroidKeyStore misbehaves,
 * encrypted storage falls back to plain prefs and key generation falls
 * back to an in-memory key — the handshake must NEVER crash the app.
 */
object Crypto {
    private const val PREF = "airwave_e2e"
    private const val K_PRIV = "x25519_priv"
    private var bcAdded = false
    private val peerKeys = mutableMapOf<String, ByteArray>() // convId -> AES-256 key

    /** Once encrypted storage fails on a device, stick to plain prefs. */
    @Volatile
    private var useFallback = false

    /** In-memory key when storage is totally unusable (never persisted). */
    @Volatile
    private var ephemeralPriv: X25519PrivateKeyParameters? = null

    private fun ensureBC() {
        if (!bcAdded) {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
            bcAdded = true
        }
    }

    private fun securePrefs(ctx: Context): SharedPreferences {
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
            // Keystore unavailable on this device/ROM — same fallback as AppLock.
            useFallback = true
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    private fun myPrivate(ctx: Context): X25519PrivateKeyParameters {
        try {
            ensureBC()
        } catch (_: Exception) {
            // Non-fatal: the lightweight X25519 API below doesn't need the
            // JCA provider registration.
        }
        return try {
            val p = securePrefs(ctx)
            p.getString(K_PRIV, null)?.let {
                try {
                    return X25519PrivateKeyParameters(Base64.decode(it, Base64.NO_WRAP))
                } catch (_: Exception) {
                    /* regen below */
                }
            }
            val priv = X25519PrivateKeyParameters(SecureRandom())
            p.edit().putString(K_PRIV, Base64.encodeToString(priv.encoded, Base64.NO_WRAP)).apply()
            priv
        } catch (_: Exception) {
            // Storage totally unusable: in-memory key so the handshake never
            // crashes. E2E won't survive a restart, but chat keeps working.
            ephemeralPriv ?: X25519PrivateKeyParameters(SecureRandom())
                .also { ephemeralPriv = it }
        }
    }

    /** Never throws: returns "" when no key can be produced (peer skips E2E). */
    fun myPublicKeyB64(ctx: Context): String = try {
        Base64.encodeToString(myPrivate(ctx).generatePublicKey().encoded, Base64.NO_WRAP)
    } catch (_: Exception) {
        ""
    }

    /** Learn peer pubkey for a DM conv; derive and store the AES key. */
    fun rememberPeer(ctx: Context, convId: String, peerPubB64: String): Boolean {
        return try {
            ensureBC()
            val peerPub = X25519PublicKeyParameters(Base64.decode(peerPubB64, Base64.NO_WRAP))
            val agree = X25519Agreement()
            agree.init(myPrivate(ctx))
            val secret = ByteArray(agree.agreementSize)
            agree.calculateAgreement(peerPub, secret, 0)
            val mine = Base64.decode(myPublicKeyB64(ctx), Base64.NO_WRAP)
            val theirs = peerPub.encoded
            val (a, b) = if (mine.isBefore(theirs)) mine to theirs else theirs to mine
            val md = MessageDigest.getInstance("SHA-256")
            md.update(secret)
            md.update("AirWave-E2E-v1".toByteArray())
            md.update(a); md.update(b)
            peerKeys[convId] = md.digest()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ByteArray.isBefore(o: ByteArray): Boolean {
        for (i in 0 until minOf(size, o.size)) {
            val d = (this[i].toInt() and 0xFF) - (o[i].toInt() and 0xFF)
            if (d != 0) return d < 0
        }
        return size <= o.size
    }

    fun hasKey(convId: String): Boolean = peerKeys.containsKey(convId)
    fun forget(convId: String) { peerKeys.remove(convId) }

    fun encrypt(convId: String, plain: ByteArray): ByteArray? {
        val key = peerKeys[convId] ?: return null
        return try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            iv + c.doFinal(plain)
        } catch (_: Exception) {
            null
        }
    }

    fun decrypt(convId: String, blob: ByteArray): ByteArray? {
        val key = peerKeys[convId] ?: return null
        return try {
            if (blob.size < 13) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, blob, 0, 12))
            c.doFinal(blob, 12, blob.size - 12)
        } catch (_: Exception) {
            null
        }
    }
}
