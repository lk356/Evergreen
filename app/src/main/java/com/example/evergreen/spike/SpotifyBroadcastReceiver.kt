package com.example.evergreen.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Listens for Spotify's "Device Broadcast Status" intents and dumps every extra
 * they carry.
 *
 * The point of this class in Phase 0 is reconnaissance, not capture: we want to
 * see whether these broadcasts still include the track URI (historically the
 * `id` extra, e.g. `spotify:track:...`). If they do, matching in Phase 2 can use
 * an exact ID instead of guessing from artist and title.
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

        SpikeLog.add(
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
