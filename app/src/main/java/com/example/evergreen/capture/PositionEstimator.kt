package com.example.evergreen.capture

/**
 * Turns a position snapshot into an estimate of where playback is *now*.
 *
 * This is the part of the Phase 0 `SkipDetector` that proved correct on device
 * and is carried over unchanged in behaviour. Four things have to happen, and
 * leaving out any one of them produces wrong numbers:
 *
 *  1. **extrapolate** forward from the snapshot using the monotonic clock,
 *  2. **scale** by `playbackSpeed` — BUFFERING reports a speed of 0.0, so this
 *     also excludes buffering time for free,
 *  3. **freeze** while paused: a paused snapshot does not advance,
 *  4. **clamp** to the track duration, because extrapolation can otherwise run
 *     past the end of the track.
 */
object PositionEstimator {

    /** Where playback is at [nowElapsedRealtimeMs], given [sample]. */
    fun estimate(sample: PositionSample, nowElapsedRealtimeMs: Long, durationMs: Long): Long {
        val advanced = advancedMs(sample, nowElapsedRealtimeMs)
        val raw = sample.positionMs + advanced
        return if (durationMs > 0) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
    }

    /**
     * How far playback moved between [sample] and [nowElapsedRealtimeMs],
     * before clamping — zero while paused.
     */
    fun advancedMs(sample: PositionSample, nowElapsedRealtimeMs: Long): Long {
        if (!sample.isPlaying) return 0L
        val gap = (nowElapsedRealtimeMs - sample.atElapsedRealtimeMs).coerceAtLeast(0L)
        return (gap * sample.speed).toLong().coerceAtLeast(0L)
    }
}
