package com.example.evergreen.spike

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches one allowlisted media session and writes what it sees to [SpikeLog].
 *
 * One instance per session. The caller is responsible for only ever creating
 * these for packages on the allowlist — see [SpikeNotificationListenerService].
 */
class SessionWatcher(
    private val controller: MediaController,
    private val onSessionDestroyedCallback: (SessionWatcher) -> Unit,
) : MediaController.Callback() {

    val packageName: String = controller.packageName
    private val detector = SkipDetector()
    private var started = false

    fun start() {
        if (started) return
        started = true
        controller.registerCallback(this, Handler(Looper.getMainLooper()))

        // Prime with whatever the session already holds, so we do not have to
        // wait for the next change to know what is playing. Metadata first:
        // the detector ignores position samples until it knows the track.
        controller.metadata?.let { onMetadataChanged(it) }
        controller.playbackState?.let { onPlaybackStateChanged(it) }
    }

    fun stop() {
        if (!started) return
        started = false
        controller.unregisterCallback(this)
    }

    override fun onMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) {
            SpikeLog.add(EventSource.METADATA, "metadata cleared", packageName = packageName)
            return
        }

        val track = TrackInfo(
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )

        // MEDIA_ID sometimes carries the service's own track identifier. Worth
        // logging: if Spotify populates it with a spotify:track: URI, that is a
        // second route to a stable ID besides the broadcast intents.
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

        val now = SystemClock.elapsedRealtime()
        val verdict = detector.onTrackChanged(track, now)

        SpikeLog.add(
            source = EventSource.METADATA,
            summary = track.describe(),
            detail = buildString {
                append("album=").append(track.album ?: "(none)")
                append(" duration=")
                append(if (track.hasDuration) "${track.durationMs}ms" else "(unknown)")
                append(" mediaId=").append(mediaId ?: "(none)")
            },
            packageName = packageName,
        )

        // The verdict describes the track that just ended, so log it after the
        // metadata line that announced the new one.
        if (verdict != null) {
            SpikeLog.add(
                source = EventSource.VERDICT,
                summary = "${verdict.track.describe()} -> ${verdict.outcome}",
                detail = verdict.describe(),
                packageName = packageName,
            )
        }
    }

    override fun onPlaybackStateChanged(state: PlaybackState?) {
        if (state == null) {
            SpikeLog.add(EventSource.STATE, "playback state cleared", packageName = packageName)
            return
        }

        val now = SystemClock.elapsedRealtime()
        // lastPositionUpdateTime is on the elapsedRealtime clock. It can be 0 if
        // the session never published one; fall back to now so the sample is at
        // least self-consistent.
        val sampledAt = if (state.lastPositionUpdateTime > 0) state.lastPositionUpdateTime else now

        val sample = PositionSample(
            positionMs = state.position,
            atElapsedRealtimeMs = sampledAt,
            speed = state.playbackSpeed,
            isPlaying = state.state == PlaybackState.STATE_PLAYING,
        )
        detector.onPositionSample(sample)

        val estimated = detector.estimatePositionMs(now)
        SpikeLog.add(
            source = EventSource.STATE,
            summary = "${stateName(state.state)} at ${estimated?.let { formatMs(it) } ?: "?"}",
            detail = buildString {
                append("reported=").append(state.position).append("ms")
                append(" estimated=").append(estimated ?: -1).append("ms")
                append(" speed=").append(state.playbackSpeed)
                append(" sampleAge=").append(now - sampledAt).append("ms")
            },
            packageName = packageName,
        )
    }

    override fun onSessionDestroyed() {
        SpikeLog.add(EventSource.SESSION, "session destroyed", packageName = packageName)
        onSessionDestroyedCallback(this)
    }

    private fun stateName(state: Int): String = when (state) {
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
        else -> "UNKNOWN($state)"
    }
}
