package com.example.evergreen.capture

import com.example.evergreen.data.EndReason
import com.example.evergreen.data.PlaybackType
import kotlin.math.min

/**
 * A finished play segment, as a set of plain facts.
 *
 * Deliberately free of Room and Android types: [SegmentTracker] is pure Kotlin
 * so the whole of the capture logic can be exercised in fast JVM unit tests.
 */
data class ClosedSegment(
    val track: TrackInfo,
    val appPackage: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val listenedMs: Long,
    val trackDurationMs: Long,
    val forwardSeekCount: Int,
    val seekedForwardMs: Long,
    val endReason: EndReason,
    val endJumpFromMs: Long?,
    val endJumpToMs: Long?,
    val lastSampleAgeMs: Long,
    /**
     * What the session last said about whose volume it was, while this segment
     * was open. Null when the session never reported one.
     *
     * **The latest reading, not a sticky one.** Until Phase 5c step 7 this was
     * `REMOTE` if *any* reading during the segment was, on the argument that a
     * partly-somebody-else's listen is not a clean local one. Measured, that
     * argument cost 83% of a real session: a handoff happens mid-track, so a
     * segment routinely spans a local stretch and a remote stretch, and the
     * sticky value condemned the whole row on *both* devices. The row now
     * carries the last thing the session actually said, and whether the listen
     * counts is decided from a signal that turned out to be worth something —
     * see [producedAudio].
     */
    val playbackType: PlaybackType?,

    /**
     * Whether **this device** produced media audio at any point while this
     * segment was open, from [ActiveAudioMonitor]. Null when nothing was
     * observed.
     *
     * **True if any reading was true**, which is the opposite direction from
     * the stickiness this field replaces and deliberately so. A device that
     * made sound for part of a listen did make sound; the value that matters to
     * scoring is whether it made *none at all*, because that is what a phantom
     * looks like. It also rides out the gap where the player tears one
     * `AudioTrack` down and builds the next one, which a reading taken at close
     * time would fall into.
     */
    val producedAudio: Boolean?,
)

/**
 * Turns a stream of media-session callbacks into play segments.
 *
 * A **segment** is a continuous stretch of listening to one track. It ends at a
 * track change, at a backwards position jump, when the session goes away, or
 * when it has been silent long enough to be worth saving. Nothing is written
 * per callback: the media session fires several a second during transitions, so
 * callbacks are collapsed in memory and only a finished segment is emitted.
 *
 * The tracker records what it saw and nothing more. It never decides whether a
 * track was completed or skipped, or whether a jump backwards counted as a
 * replay — those are conclusions, and they are Phase 3's to draw.
 *
 * Not thread-safe: drive it from a single thread (the media-session callbacks
 * all arrive on one Handler).
 */
class SegmentTracker(
    private val appPackage: String,
    private val onSegmentClosed: (ClosedSegment) -> Unit,
) {

    private class OpenSegment(
        var track: TrackInfo,
        val startedAtMs: Long,
        var startPositionMs: Long,
        var startPositionKnown: Boolean,
        /** True when this segment began at a backwards jump, not a track change. */
        val openedByJumpBack: Boolean,
        /** Whatever the session had most recently reported when this opened. */
        var playbackType: PlaybackType?,
        /** Whatever the audio monitor had most recently reported when this opened. */
        var producedAudio: Boolean?,
    ) {
        var listenedMs = 0L
        var lastKnownPositionMs = startPositionMs
        var forwardSeekCount = 0
        var seekedForwardMs = 0L

        /**
         * Folds one reading in. The latest wins; a null reading is "the session
         * did not say this time" and changes nothing.
         *
         * Not sticky any more. See [ClosedSegment.playbackType] for the 83%
         * that the sticky version cost.
         */
        fun observePlaybackType(type: PlaybackType?) {
            if (type == null) return
            playbackType = type
        }

        /**
         * Folds one audio reading in. True wins and stays: the question this
         * field answers is "did this device make any sound during the listen",
         * and one moment of sound is a yes for the whole segment.
         */
        fun observeProducedAudio(producing: Boolean) {
            if (producedAudio != true) producedAudio = producing
        }
    }

    private var open: OpenSegment? = null
    private var lastSample: PositionSample? = null
    private var lastFingerprint: String? = null

    /**
     * The session's most recent playback type, carried across segments.
     *
     * Held here rather than read at close time because the value describes the
     * *listening*, not the moment the row was written — and because it is not
     * durable: measured on 2026-09-21, Spotify keeps `REMOTE` set through a
     * pause and for at least 108 s after it, then clears it when the Connect
     * session tears down. A reading taken after that says `LOCAL` about a
     * listen that was not.
     */
    private var currentPlaybackType: PlaybackType? = null

    /**
     * Whether this device was last seen producing media audio, carried across
     * segments for the same reason [currentPlaybackType] is: a segment opening
     * on the next callback has to know, and the monitor only speaks on a change.
     *
     * Null until the first reading arrives.
     */
    private var currentProducedAudio: Boolean? = null

    /**
     * The most recently closed segment, held back from the database for a
     * moment so it can still be corrected — see
     * [CaptureThresholds.AMEND_WINDOW_MS]. Also serves as the write batching
     * the NW-A307 wants: nothing touches storage on a callback.
     */
    private var pending: ClosedSegment? = null

    /** Best estimate of the current position, for the debug screen. */
    fun estimatedPositionMs(nowElapsedRealtimeMs: Long): Long? {
        val sample = lastSample ?: return null
        val duration = open?.track?.durationMs ?: 0L
        return PositionEstimator.estimate(sample, nowElapsedRealtimeMs, duration)
    }

    val currentTrack: TrackInfo? get() = open?.track

    // --- Inputs -------------------------------------------------------------

    /**
     * New metadata arrived.
     *
     * Returns false when the callback was a duplicate and was ignored, which is
     * the common case: `onMetadataChanged` re-fires with byte-identical content
     * up to ten times a second during transitions.
     */
    fun onMetadata(track: TrackInfo, nowElapsedRealtimeMs: Long, nowWallMs: Long): Boolean {
        val fingerprint = track.fingerprint()
        if (fingerprint == lastFingerprint) return false
        lastFingerprint = fingerprint
        flushPendingIfSettled(nowWallMs)

        val current = open
        if (current == null) {
            openSegment(track, nowWallMs, startPositionMs = 0L, startKnown = false, byJumpBack = false)
            return true
        }

        if (current.track.isSameTrackAs(track)) {
            // Same track, refreshed metadata. Duration and the service id often
            // arrive a beat after the title does, so adopt them if they are new.
            current.track = current.track.copy(
                durationMs = if (track.hasDuration) track.durationMs else current.track.durationMs,
                serviceTrackId = track.serviceTrackId ?: current.track.serviceTrackId,
                title = track.title ?: current.track.title,
                artist = track.artist ?: current.track.artist,
                album = track.album ?: current.track.album,
            )
            return true
        }

        // A genuine track change.
        if (amendPendingForTrackChange(nowWallMs)) {
            // The previous "jump back" was really this track change arriving
            // out of order. The stub segment it opened is discarded.
            open = null
            lastSample = null
        }

        val stillOpen = open
        if (stillOpen != null) {
            val sample = lastSample
            val endPosition = sample
                ?.let { PositionEstimator.estimate(it, nowElapsedRealtimeMs, stillOpen.track.durationMs) }
                ?: stillOpen.lastKnownPositionMs
            if (sample != null) accrue(stillOpen, sample, nowElapsedRealtimeMs, corroboratingPositionMs = null)
            closeSegment(
                segment = stillOpen,
                reason = EndReason.TRACK_CHANGE,
                endPositionMs = endPosition,
                nowWallMs = nowWallMs,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            )
        }

        // The old track's position readings say nothing about the new track.
        lastSample = null
        openSegment(track, nowWallMs, startPositionMs = 0L, startKnown = false, byJumpBack = false)
        return true
    }

    /**
     * The session reported whose volume it is using.
     *
     * Arrives from `MediaController.Callback.onAudioInfoChanged`, and is primed
     * once when the watcher starts so a segment opening immediately still knows.
     * A null reading is ignored rather than stored: "the session did not say
     * this time" is not evidence that it became local.
     *
     * Recorded, never acted on. Whether a remote segment counts is a judgement
     * and it belongs to scoring; see [PlaybackType].
     */
    fun onPlaybackType(type: PlaybackType?) {
        if (type == null) return
        currentPlaybackType = type
        open?.observePlaybackType(type)
    }

    /**
     * This device started or stopped producing media audio.
     *
     * Arrives from [ActiveAudioMonitor], which is device-wide rather than
     * per-session: one monitor feeds every watcher, because "is this device
     * making a sound" has one answer however many sessions are open.
     *
     * Recorded, never acted on — and this one *cannot* be acted on here even in
     * principle. "This device made no sound" is not by itself a phantom: if no
     * other device claims the same listen, it is a listen. That comparison needs
     * the other device's facts, which capture does not have and scoring does.
     */
    fun onActiveAudio(producing: Boolean) {
        currentProducedAudio = producing
        open?.observeProducedAudio(producing)
    }

    /** A new playback-state reading arrived. */
    fun onPlaybackState(sample: PositionSample, nowElapsedRealtimeMs: Long, nowWallMs: Long) {
        flushPendingIfSettled(nowWallMs)
        val current = open
        if (current == null) {
            // Nothing to attribute this to yet; remember it for when metadata lands.
            lastSample = sample
            return
        }

        val previous = lastSample
        if (previous == null) {
            if (!current.startPositionKnown) {
                current.startPositionMs = sample.positionMs
                current.lastKnownPositionMs = sample.positionMs
                current.startPositionKnown = true
            }
            lastSample = sample
            return
        }

        val duration = current.track.durationMs
        val expected = PositionEstimator.estimate(previous, sample.atElapsedRealtimeMs, duration)
        val jump = sample.positionMs - expected

        if (jump <= -CaptureThresholds.POSITION_JUMP_BACK_CUT_MS) {
            // Everything up to the moment of the jump was genuinely heard. There
            // is no usable position cross-check across a jump, so accrue on the
            // clock alone.
            accrue(current, previous, sample.atElapsedRealtimeMs, corroboratingPositionMs = null)
            closeSegment(
                segment = current,
                reason = EndReason.POSITION_JUMP_BACK,
                endPositionMs = expected,
                nowWallMs = nowWallMs,
                nowElapsedRealtimeMs = sample.atElapsedRealtimeMs,
                jumpFromMs = expected,
                jumpToMs = sample.positionMs,
            )
            openSegment(
                track = current.track,
                nowWallMs = nowWallMs,
                startPositionMs = sample.positionMs,
                startKnown = true,
                byJumpBack = true,
            )
            lastSample = sample
            return
        }

        accrue(current, previous, sample.atElapsedRealtimeMs, corroboratingPositionMs = sample.positionMs)

        if (jump >= CaptureThresholds.FORWARD_SEEK_CUT_MS) {
            current.forwardSeekCount++
            current.seekedForwardMs += jump
        }

        current.lastKnownPositionMs = sample.positionMs
        lastSample = sample
    }

    /** The media session went away (e.g. Spotify was force-stopped). */
    fun onSessionEnd(nowElapsedRealtimeMs: Long, nowWallMs: Long) {
        finish(EndReason.SESSION_END, nowElapsedRealtimeMs, nowWallMs)
    }

    /**
     * Periodic nudge. Closes a segment that has gone quiet for a long time so
     * that it is not lost if the process dies with it still open.
     */
    fun onIdleCheck(nowElapsedRealtimeMs: Long, nowWallMs: Long) {
        flushPendingIfSettled(nowWallMs)
        val current = open ?: return
        val sample = lastSample ?: return
        if (nowElapsedRealtimeMs - sample.atElapsedRealtimeMs < CaptureThresholds.STALE_CLOSE_MS) return
        val endPosition = PositionEstimator.estimate(
            sample,
            sample.atElapsedRealtimeMs,
            current.track.durationMs,
        )
        closeSegment(
            segment = current,
            reason = EndReason.STALE,
            endPositionMs = endPosition,
            nowWallMs = nowWallMs,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
        open = null
        lastSample = null
        flushPending()
    }

    /** Closes whatever is open and writes everything out. */
    fun finish(reason: EndReason, nowElapsedRealtimeMs: Long, nowWallMs: Long) {
        val current = open
        if (current != null) {
            val sample = lastSample
            val endPosition = sample
                ?.let { PositionEstimator.estimate(it, nowElapsedRealtimeMs, current.track.durationMs) }
                ?: current.lastKnownPositionMs
            if (sample != null) {
                accrue(current, sample, nowElapsedRealtimeMs, corroboratingPositionMs = null)
            }
            closeSegment(
                segment = current,
                reason = if (sample == null) EndReason.UNKNOWN else reason,
                endPositionMs = endPosition,
                nowWallMs = nowWallMs,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            )
        }
        open = null
        lastSample = null
        lastFingerprint = null
        flushPending()
    }

    // --- Internals ----------------------------------------------------------

    /**
     * Adds the listening time between [from] and [untilElapsedMs] to [segment].
     *
     * One rule covers every awkward case: take the **smaller** of what the clock
     * says was played and what the position actually advanced by.
     *
     * | Situation          | Smaller term | Effect                          |
     * |--------------------|--------------|---------------------------------|
     * | normal playback    | they agree   | correct                         |
     * | forward seek       | the clock    | the jumped-over part is excluded |
     * | playback stalls    | the position | no time is invented             |
     * | ~80 ms of jitter   | the position | loses up to 80 ms, never adds   |
     *
     * It always errs downwards. Under-counting is recoverable; inventing plays
     * is not, and a scoring model built on invented events is worse than one
     * built on gaps it knows about.
     *
     * [corroboratingPositionMs] is null when no comparable reading exists — over
     * a backwards jump, or when closing a segment with no newer reading. The
     * clock term is then used alone; it is still bounded by the track duration,
     * because the estimate is clamped there.
     */
    private fun accrue(
        segment: OpenSegment,
        from: PositionSample,
        untilElapsedMs: Long,
        corroboratingPositionMs: Long?,
    ) {
        val duration = segment.track.durationMs
        val expected = PositionEstimator.estimate(from, untilElapsedMs, duration)
        val byClock = (expected - from.positionMs).coerceAtLeast(0L)
        val gained = if (corroboratingPositionMs == null) {
            byClock
        } else {
            min(byClock, (corroboratingPositionMs - from.positionMs).coerceAtLeast(0L))
        }
        segment.listenedMs += gained
    }

    private fun openSegment(
        track: TrackInfo,
        nowWallMs: Long,
        startPositionMs: Long,
        startKnown: Boolean,
        byJumpBack: Boolean,
    ) {
        open = OpenSegment(
            track = track,
            startedAtMs = nowWallMs,
            startPositionMs = startPositionMs,
            startPositionKnown = startKnown,
            openedByJumpBack = byJumpBack,
            playbackType = currentPlaybackType,
            producedAudio = currentProducedAudio,
        )
    }

    private fun closeSegment(
        segment: OpenSegment,
        reason: EndReason,
        endPositionMs: Long,
        nowWallMs: Long,
        nowElapsedRealtimeMs: Long,
        jumpFromMs: Long? = null,
        jumpToMs: Long? = null,
    ) {
        val sampleAge = lastSample
            ?.let { (nowElapsedRealtimeMs - it.atElapsedRealtimeMs).coerceAtLeast(0L) }
            ?: 0L

        val closed = ClosedSegment(
            track = segment.track,
            appPackage = appPackage,
            startedAtMs = segment.startedAtMs,
            endedAtMs = nowWallMs,
            startPositionMs = segment.startPositionMs,
            endPositionMs = endPositionMs,
            listenedMs = segment.listenedMs,
            trackDurationMs = segment.track.durationMs,
            forwardSeekCount = segment.forwardSeekCount,
            seekedForwardMs = segment.seekedForwardMs,
            endReason = reason,
            endJumpFromMs = jumpFromMs,
            endJumpToMs = jumpToMs,
            lastSampleAgeMs = sampleAge,
            playbackType = segment.playbackType,
            producedAudio = segment.producedAudio,
        )

        flushPending()
        pending = closed
        open = null
    }

    /**
     * Handles the case where a track change was preceded by the new track's
     * position reading, which looks exactly like a jump back to zero.
     *
     * Returns true when the held-back segment was corrected, meaning the caller
     * should throw away the stub segment that the false jump opened.
     */
    private fun amendPendingForTrackChange(nowWallMs: Long): Boolean {
        val held = pending ?: return false
        val stub = open ?: return false
        if (!stub.openedByJumpBack) return false
        if (held.endReason != EndReason.POSITION_JUMP_BACK) return false
        if (stub.listenedMs > 0L) return false
        if (nowWallMs - stub.startedAtMs > CaptureThresholds.AMEND_WINDOW_MS) return false

        pending = held.copy(
            endReason = EndReason.TRACK_CHANGE,
            endJumpFromMs = null,
            endJumpToMs = null,
            endedAtMs = nowWallMs,
        )
        return true
    }

    /**
     * Writes the held-back segment out once it can no longer be amended.
     *
     * Holding a *finished* segment any longer than the amend window is pure
     * risk: if the process dies while it is still in hand, a real listen is
     * lost. This is driven by elapsed time rather than by how much of the next
     * segment has been heard, because playback frequently stops right after a
     * track change — in which case no further listening ever accrues and the
     * held segment would wait indefinitely. Found exactly that way on device.
     *
     * Callers should also poke this from a timer, since when playback stops the
     * callbacks stop with it. See [SessionWatcher].
     */
    fun flushPendingIfSettled(nowWallMs: Long) {
        if (pending == null) return
        val stub = open
        if (stub != null && nowWallMs - stub.startedAtMs <= CaptureThresholds.AMEND_WINDOW_MS) return
        flushPending()
    }

    private fun flushPending() {
        val held = pending ?: return
        pending = null
        onSegmentClosed(held)
    }
}
