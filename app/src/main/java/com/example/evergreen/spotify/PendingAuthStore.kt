package com.example.evergreen.spotify

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The state one login attempt needs in order to be completed. */
data class PendingAuth(
    val verifier: String,
    val state: String,
    val startedAtMs: Long,
)

/**
 * Holds the in-flight PKCE verifier and `state` across the trip to the browser.
 *
 * It has to survive process death: Android can kill this app while the user is
 * typing their Spotify password, and a verifier lost that way turns a successful
 * login into a failure the user has no way to interpret.
 *
 * Encrypted with the same Keystore key as the tokens. The verifier is only
 * valuable for the seconds it is outstanding, and the preferences file is
 * already private to the app, so this is belt and braces — but it is one line of
 * reuse, and the alternative is a plaintext secret on disk.
 */
class PendingAuthStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(pending: PendingAuth) {
        val json = JSONObject()
            .put(FIELD_VERIFIER, pending.verifier)
            .put(FIELD_STATE, pending.state)
            .put(FIELD_STARTED_AT, pending.startedAtMs)
        prefs.edit().putString(KEY_PENDING, KeystoreCrypto.encrypt(json.toString())).apply()
    }

    /**
     * Reads the pending attempt, or null if there is none or it has gone stale.
     *
     * The age limit bounds how long a redirect can be accepted after the attempt
     * that asked for it — a browser tab reopened days later should not complete
     * a login.
     */
    fun load(nowMs: Long = System.currentTimeMillis()): PendingAuth? {
        val blob = prefs.getString(KEY_PENDING, null) ?: return null
        val pending = try {
            val json = JSONObject(KeystoreCrypto.decrypt(blob))
            PendingAuth(
                verifier = json.getString(FIELD_VERIFIER),
                state = json.getString(FIELD_STATE),
                startedAtMs = json.getLong(FIELD_STARTED_AT),
            )
        } catch (e: Exception) {
            Log.w(TAG, "pending login could not be read; discarding", e)
            clear()
            return null
        }
        if (nowMs - pending.startedAtMs > MAX_AGE_MS) {
            clear()
            return null
        }
        return pending
    }

    fun clear() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    private companion object {
        const val TAG = "PendingAuthStore"
        const val PREFS_NAME = "spotify_pending_auth"
        const val KEY_PENDING = "pending"
        const val FIELD_VERIFIER = "verifier"
        const val FIELD_STATE = "state"
        const val FIELD_STARTED_AT = "started_at"

        /** Long enough for a password manager and a 2FA prompt, not for a day. */
        val MAX_AGE_MS: Long = TimeUnit.MINUTES.toMillis(30)
    }
}
