package com.example.evergreen.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Listens for Spotify's "Device Broadcast Status" intents and logs what they
 * carry.
 *
 * **This never writes to the database.** The media session is the primary
 * source and the only one capture depends on: it does not require the user to
 * have left "Device Broadcast Status" switched on, and it keeps working for
 * Spotify Connect playback.
 *
 * It is kept for one narrow purpose. Step 0 found that broadcast
 * `metadatachanged` fires *only* on a real track change — never on a loop or a
 * restart — which makes it the most likely way to settle two questions still
 * open at the end of Phase 0: whether queue auto-advance can be told apart from
 * a manual "next", and what a session ending actually looks like. Because it
 * writes nothing, it cannot contaminate the recorded history while it is being
 * used to answer them.
 *
 * Note also that the two sources disagree on duration — the media session
 * rounds to the second (109000 ms) where the broadcast is exact (108518 ms) —
 * so figures from here must never be mixed into a media-session calculation.
 *
 * This is an undocumented Spotify feature that has changed without notice
 * before, so treat anything learned here as version-specific.
 */
class SpotifyBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // Only the Spotify-namespaced actions we asked for. The receiver has to
        // be exported to hear Spotify at all, so anything else that reaches us
        // is discarded here without inspecting its payload.
        if (action == null || action !in ACTIONS) return

        val extras = intent.extras
        val detail = if (extras == null || extras.isEmpty) {
            "(no extras)"
        } else {
            extras.keySet().joinToString(" ") { key ->
                @Suppress("DEPRECATION")
                val value = runCatching { extras.get(key) }.getOrNull()
                "$key=${value ?: "(null)"}"
            }
        }

        CaptureLog.add(
            source = EventSource.BROADCAST,
            summary = action.substringAfterLast('.'),
            detail = detail,
            packageName = SPOTIFY_PACKAGE,
        )
    }

    companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"

        const val ACTION_METADATA_CHANGED = "com.spotify.music.metadatachanged"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.spotify.music.playbackstatechanged"
        const val ACTION_QUEUE_CHANGED = "com.spotify.music.queuechanged"

        private val ACTIONS = setOf(
            ACTION_METADATA_CHANGED,
            ACTION_PLAYBACK_STATE_CHANGED,
            ACTION_QUEUE_CHANGED,
        )

        fun intentFilter(): IntentFilter = IntentFilter().apply {
            ACTIONS.forEach { addAction(it) }
        }
    }
}
