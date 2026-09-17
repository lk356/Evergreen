package com.example.evergreen.capture

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches one allowlisted media session and turns its callbacks into play
 * segments.
 *
 * One instance per session. The caller is responsible for only ever creating
 * these for packages on the allowlist — see [CaptureNotificationListenerService].
 *
 * This class does the Android-specific work only: read the callback, convert it
 * into plain data, hand it to [SegmentTracker]. All the judgement about what
 * constitutes a segment lives in the tracker, which has no Android types and is
 * unit tested.
 */
class SessionWatcher(
    private val controller: MediaController,
    onSegmentClosed: (ClosedSegment) -> Unit,
    private val onSessionDestroyedCallback: (SessionWatcher) -> Unit,
) : MediaController.Callback() {

    val packageName: String = controller.packageName

    private val tracker = SegmentTracker(packageName, onSegmentClosed)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    /**
     * Closes a segment that has gone quiet, so it is not lost if the process is
     * killed with it still open. One pending message at a time, rescheduled as
     * it fires — the NW-A307 has little to spare and this must stay close to
     * free.
     */
    private val idleCheck = object : Runnable {
        override fun run() {
            tracker.onIdleCheck(SystemClock.elapsedRealtime(), System.currentTimeMillis())
            handler.postDelayed(this, IDLE_CHECK_INTERVAL_MS)
        }
    }

    /**
     * Writes out a finished segment shortly after it closes.
     *
     * The tracker holds each closed segment for a moment in case it has to be
     * corrected, and normally the next callback releases it. But when playback
     * stops right after a track change the callbacks stop too, so something has
     * to come back and release it. Rescheduled after each callback, so it only
     * ever fires once playback has actually gone quiet.
     */
    private val settlePending = Runnable {
        tracker.flushPendingIfSettled(System.currentTimeMillis())
    }

    private fun scheduleSettle() {
        handler.removeCallbacks(settlePending)
        handler.postDelayed(settlePending, SETTLE_DELAY_MS)
    }

    fun start() {
        if (started) return
        started = true
        controller.registerCallback(this, handler)

        // Prime with whatever the session already holds, so we do not have to
        // wait for the next change to know what is playing. Metadata first: the
        // tracker cannot attribute a position reading until it knows the track.
        controller.metadata?.let { onMetadataChanged(it) }
        controller.playbackState?.let { onPlaybackStateChanged(it) }

        handler.postDelayed(idleCheck, IDLE_CHECK_INTERVAL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(idleCheck)
        handler.removeCallbacks(settlePending)
        controller.unregisterCallback(this)
        // Write out whatever was in flight rather than dropping it.
        tracker.finish(
            reason = com.example.evergreen.data.EndReason.SESSION_END,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            nowWallMs = System.currentTimeMillis(),
        )
    }

    override fun onMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) {
            CaptureLog.add(EventSource.METADATA, "metadata cleared", packageName = packageName)
            return
        }

        val track = TrackInfo(
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            serviceTrackId = serviceTrackIdOf(metadata),
        )

        val changed = tracker.onMetadata(
            track = track,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            nowWallMs = System.currentTimeMillis(),
        )
        scheduleSettle()

        // Only log real changes. Logging every callback would bury the
        // interesting lines under ten duplicates a second.
        if (changed) {
            CaptureLog.add(
                source = EventSource.METADATA,
                summary = track.describe(),
                detail = buildString {
                    append("album=").append(track.album ?: "(none)")
                    append(" duration=")
                    append(if (track.hasDuration) "${track.durationMs}ms" else "(unknown)")
                    append(" id=").append(track.serviceTrackId ?: "(none)")
                },
                packageName = packageName,
            )
        }
    }

    override fun onPlaybackStateChanged(state: PlaybackState?) {
        if (state == null) {
            CaptureLog.add(EventSource.STATE, "playback state cleared", packageName = packageName)
            return
        }

        val now = SystemClock.elapsedRealtime()
        // lastPositionUpdateTime is on the elapsedRealtime clock. It can be 0 if
        // the session never published one; fall back to now so that the sample
        // is at least self-consistent.
        val sampledAt = if (state.lastPositionUpdateTime > 0) state.lastPositionUpdateTime else now

        val sample = PositionSample(
            positionMs = state.position,
            atElapsedRealtimeMs = sampledAt,
            speed = state.playbackSpeed,
            isPlaying = state.state == PlaybackState.STATE_PLAYING,
        )

        tracker.onPlaybackState(sample, now, System.currentTimeMillis())
        scheduleSettle()

        CaptureLog.add(
            source = EventSource.STATE,
            summary = "${stateName(state.state)} at ${
                tracker.estimatedPositionMs(now)?.let { formatMs(it) } ?: "?"
            }",
            detail = buildString {
                append("reported=").append(state.position).append("ms")
                append(" speed=").append(state.playbackSpeed)
                append(" sampleAge=").append(now - sampledAt).append("ms")
            },
            packageName = packageName,
        )
    }

    override fun onSessionDestroyed() {
        CaptureLog.add(EventSource.SESSION, "session destroyed", packageName = packageName)
        handler.removeCallbacks(idleCheck)
        handler.removeCallbacks(settlePending)
        tracker.onSessionEnd(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        started = false
        onSessionDestroyedCallback(this)
    }

    /**
     * The service's own track identifier.
     *
     * Phase 0 confirmed Spotify publishes `spotify:track:...` here. Two keys can
     * carry it, so prefer whichever actually looks like a service URI rather
     * than assuming an order.
     */
    private fun serviceTrackIdOf(metadata: MediaMetadata): String? {
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
        return listOfNotNull(mediaId, mediaUri)
            .firstOrNull { it.startsWith("spotify:") }
            ?: mediaId
            ?: mediaUri
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

    private companion object {
        const val IDLE_CHECK_INTERVAL_MS = 5L * 60L * 1_000L

        /** Comfortably past [CaptureThresholds.AMEND_WINDOW_MS]. */
        const val SETTLE_DELAY_MS = 2_500L
    }
}
