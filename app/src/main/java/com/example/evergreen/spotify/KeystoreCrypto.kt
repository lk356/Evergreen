package com.example.evergreen.spotify

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption using a key held in the Android Keystore.
 *
 * The key material never leaves the device's secure hardware: this code can ask
 * the Keystore to encrypt and decrypt, but cannot read the key itself. Anything
 * that copies the stored bytes off the device gets ciphertext it has no way to
 * open.
 *
 * Deliberately **not** `androidx.security:security-crypto` — that library is
 * deprecated and unmaintained. This is the same job done directly against the
 * platform, small enough to read in one sitting.
 *
 * Used by [TokenStore] for the long-lived tokens and by [PendingAuthStore] for
 * the PKCE verifier that has to survive process death mid-login.
 */
internal object KeystoreCrypto {

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // [version][iv length][iv][ciphertext]. The version byte is what makes a
        // future change of algorithm readable rather than silent corruption.
        val out = ByteArray(2 + iv.size + ciphertext.size)
        out[0] = BLOB_VERSION
        out[1] = iv.size.toByte()
        iv.copyInto(out, 2)
        ciphertext.copyInto(out, 2 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.size > 2 && bytes[0] == BLOB_VERSION) { "unrecognised encrypted blob" }
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

    private fun generateKey(): SecretKey =
        // StrongBox is a separate security chip; the Pixel has one, the NW-A307
        // very likely does not. Ask for it, accept the standard Keystore when it
        // is unavailable — still hardware-isolated, still non-extractable, just
        // not on a dedicated chip.
        try {
            buildKey(strongBox = true)
        } catch (e: Exception) {
            Log.i(TAG, "StrongBox unavailable, using the standard Keystore", e)
            // A failed StrongBox attempt can leave a half-created entry behind.
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }
            buildKey(strongBox = false)
        }

    private fun buildKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // The default, stated explicitly so nobody "optimises" it away: GCM
            // is only safe with a fresh IV per message.
            .setRandomizedEncryptionRequired(true)
            // Deliberately NOT setUserAuthenticationRequired: like sync runs from
            // WorkManager, usually with the screen locked. Requiring an unlock
            // would make background sync work only while the user is watching.
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private const val TAG = "KeystoreCrypto"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "evergreen_spotify_tokens_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val BLOB_VERSION: Byte = 1
}
