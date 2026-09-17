package com.example.evergreen.spotify

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores Spotify tokens encrypted with a key that never leaves the device's
 * secure hardware.
 *
 * The key lives in the Android Keystore. The app can ask it to encrypt and
 * decrypt but cannot read the key material itself, so the ciphertext sitting in
 * SharedPreferences is useless to anything that copies the file off the device.
 *
 * Deliberately **not** `androidx.security:security-crypto` — that library is
 * deprecated and no longer maintained. This does the same job directly against
 * the platform APIs, in about eighty lines.
 *
 * Deliberately **not** in the Room database either. Keeping tokens outside it
 * means any future database export or debug dump physically cannot contain a
 * credential.
 *
 * The file is already excluded from cloud backup and device-to-device transfer
 * by `data_extraction_rules.xml`. That matters: a Keystore key does not survive
 * a transfer to a new device, so a restored backup would hold ciphertext no key
 * can open. [load] treats that as "not connected" rather than crashing.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Reads the stored tokens, or null if there are none.
     *
     * Returns null rather than throwing when the ciphertext cannot be decrypted.
     * That happens for real reasons outside the user's control — the Keystore
     * key was invalidated, the data came from another device — and in every one
     * of them the right answer is "log in again", not a crash on launch.
     */
    fun load(): SpotifyTokens? {
        val blob = prefs.getString(KEY_TOKENS, null) ?: return null
        val json = try {
            JSONObject(decrypt(blob))
        } catch (e: Exception) {
            // Never log the blob itself, only that it failed to open.
            Log.w(TAG, "stored tokens could not be decrypted; treating as disconnected", e)
            clear(expired = false)
            return null
        }
        return SpotifyTokens(
            accessToken = json.getString(FIELD_ACCESS),
            refreshToken = json.getString(FIELD_REFRESH),
            accessTokenExpiresAtMs = json.getLong(FIELD_ACCESS_EXPIRES),
            authorizedAtMs = json.getLong(FIELD_AUTHORIZED_AT),
            grantedScopes = json.optString(FIELD_SCOPES, ""),
        )
    }

    fun save(tokens: SpotifyTokens) {
        val json = JSONObject()
            .put(FIELD_ACCESS, tokens.accessToken)
            .put(FIELD_REFRESH, tokens.refreshToken)
            .put(FIELD_ACCESS_EXPIRES, tokens.accessTokenExpiresAtMs)
            .put(FIELD_AUTHORIZED_AT, tokens.authorizedAtMs)
            .put(FIELD_SCOPES, tokens.grantedScopes)
        prefs.edit()
            .putString(KEY_TOKENS, encrypt(json.toString()))
            .remove(KEY_EXPIRED_AT)
            .apply()
    }

    /**
     * Forgets the tokens.
     *
     * [expired] distinguishes the two reasons this happens. A refresh token that
     * died of old age leaves a marker so the UI can say "reconnect Spotify"
     * instead of the much less helpful "not connected", which is what the user
     * would otherwise see after six months of everything working.
     */
    fun clear(expired: Boolean, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().apply {
            remove(KEY_TOKENS)
            if (expired) putLong(KEY_EXPIRED_AT, nowMs) else remove(KEY_EXPIRED_AT)
        }.apply()
    }

    /** True once the user has ever completed a login on this install. */
    fun connectionState(nowMs: Long = System.currentTimeMillis()): SpotifyConnectionState {
        val tokens = load()
        if (tokens == null) {
            val expiredAt = prefs.getLong(KEY_EXPIRED_AT, 0L)
            return if (expiredAt > 0L) {
                SpotifyConnectionState.Expired(expiredAt)
            } else {
                SpotifyConnectionState.NotConnected
            }
        }
        val deadline = tokens.refreshTokenExpiresAtMs
        return when {
            // The deadline is knowable without asking Spotify, so a dead
            // connection is reported as dead before any request is attempted.
            nowMs >= deadline -> SpotifyConnectionState.Expired(deadline)
            nowMs >= deadline - SpotifyTokens.RECONNECT_WARNING_MS ->
                SpotifyConnectionState.ExpiringSoon(
                    authorizedAtMs = tokens.authorizedAtMs,
                    refreshTokenExpiresAtMs = deadline,
                    grantedScopes = tokens.grantedScopes,
                )
            else -> SpotifyConnectionState.Connected(
                authorizedAtMs = tokens.authorizedAtMs,
                refreshTokenExpiresAtMs = deadline,
                grantedScopes = tokens.grantedScopes,
            )
        }
    }

    // --- Crypto -------------------------------------------------------------

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // [version][iv length][iv][ciphertext]. The version byte is what makes a
        // future change of algorithm readable rather than a silent corruption.
        val out = ByteArray(2 + iv.size + ciphertext.size)
        out[0] = BLOB_VERSION
        out[1] = iv.size.toByte()
        iv.copyInto(out, 2)
        ciphertext.copyInto(out, 2 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.size > 2 && bytes[0] == BLOB_VERSION) { "unrecognised token blob" }
        val ivLength = bytes[1].toInt()
        require(ivLength in 1..bytes.size - 2) { "bad iv length" }

        val iv = bytes.copyOfRange(2, 2 + ivLength)
        val ciphertext = bytes.copyOfRange(2 + ivLength, bytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        // StrongBox is a separate security chip; the Pixel has one, the NW-A307
        // very likely does not. Ask for it, accept the software-backed Keystore
        // when it is unavailable — still hardware-isolated, still
        // non-extractable, just not on a dedicated chip.
        return try {
            buildKey(strongBox = true)
        } catch (e: Exception) {
            Log.i(TAG, "StrongBox unavailable, using the standard Keystore", e)
            // A failed StrongBox attempt can leave a half-created entry behind.
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }
            buildKey(strongBox = false)
        }
    }

    private fun buildKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Randomised encryption is the default and is required for GCM to be
            // safe; stated explicitly so nobody "optimises" it away later.
            .setRandomizedEncryptionRequired(true)
            // Deliberately NOT setUserAuthenticationRequired: like sync runs from
            // WorkManager, often with the screen locked. Requiring an unlock
            // would turn background sync into a feature that only works while
            // the user is looking at the phone.
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val TAG = "TokenStore"
        const val PREFS_NAME = "spotify_tokens"
        const val KEY_TOKENS = "tokens"
        const val KEY_EXPIRED_AT = "expired_at"

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "evergreen_spotify_tokens_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val BLOB_VERSION: Byte = 1

        const val FIELD_ACCESS = "access_token"
        const val FIELD_REFRESH = "refresh_token"
        const val FIELD_ACCESS_EXPIRES = "access_expires_at"
        const val FIELD_AUTHORIZED_AT = "authorized_at"
        const val FIELD_SCOPES = "scopes"
    }
}
