package com.example.evergreen.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The position-handling tests carried over from the Phase 0 `SkipDetector`.
 *
 * They cover the four behaviours that Phase 0 proved on device and that any
 * consumer of media-session position data has to get right: extrapolate from
 * the snapshot, scale by playback speed, freeze while paused, and clamp to the
 * track duration.
 *
 * All times are on a fake monotonic clock, in milliseconds, exactly as
 * SystemClock.elapsedRealtime() would report them on a device.
 */
class PositionEstimatorTest {

    private fun playing(positionMs: Long, atMs: Long) =
        PositionSample(positionMs, atMs, speed = 1.0f, isPlaying = true)

    private fun paused(positionMs: Long, atMs: Long) =
        PositionSample(positionMs, atMs, speed = 0.0f, isPlaying = false)

    @Test
    fun `position is extrapolated forward from the snapshot`() {
        // A reading taken at 100s said we were 100s in. Twenty seconds later
        // with no further updates, we are 120s in, not still 100s.
        val estimate = PositionEstimator.estimate(
            playing(positionMs = 100_000, atMs = 100_000),
            nowElapsedRealtimeMs = 120_000,
            durationMs = THREE_MINUTES,
        )

        assertEquals(120_000L, estimate)
    }

    @Test
    fun `paused playback does not advance the estimated position`() {
        // Sat paused for ten minutes; the position has not moved.
        val estimate = PositionEstimator.estimate(
            paused(positionMs = 30_000, atMs = 30_000),
            nowElapsedRealtimeMs = 600_000,
            durationMs = THREE_MINUTES,
        )

        assertEquals(30_000L, estimate)
    }

    @Test
    fun `playback speed scales the extrapolation`() {
        // 10s of wall clock at 2x is 20s of track, on top of the 10s snapshot.
        val estimate = PositionEstimator.estimate(
            PositionSample(positionMs = 10_000, atElapsedRealtimeMs = 10_000, speed = 2.0f, isPlaying = true),
            nowElapsedRealtimeMs = 20_000,
            durationMs = THREE_MINUTES,
        )

        assertEquals(30_000L, estimate)
    }

    @Test
    fun `buffering reports zero speed and so contributes nothing`() {
        // Step 0 finding: BUFFERING arrives with speed=0.0, which means the
        // existing scaling already excludes it without a special case.
        val estimate = PositionEstimator.estimate(
            PositionSample(positionMs = 50_000, atElapsedRealtimeMs = 50_000, speed = 0.0f, isPlaying = true),
            nowElapsedRealtimeMs = 55_000,
            durationMs = THREE_MINUTES,
        )

        assertEquals(50_000L, estimate)
    }

    @Test
    fun `estimated position never exceeds the track duration`() {
        // Extrapolating naively would give 75s for a 60s track.
        val estimate = PositionEstimator.estimate(
            playing(positionMs = 55_000, atMs = 55_000),
            nowElapsedRealtimeMs = 75_000,
            durationMs = 60_000,
        )

        assertEquals(60_000L, estimate)
    }

    @Test
    fun `an unknown duration disables clamping rather than clamping to zero`() {
        val estimate = PositionEstimator.estimate(
            playing(positionMs = 55_000, atMs = 55_000),
            nowElapsedRealtimeMs = 75_000,
            durationMs = 0,
        )

        assertEquals(75_000L, estimate)
    }

    @Test
    fun `estimated position is never negative`() {
        // A clock that appears to run backwards must not produce a negative
        // position; coerce rather than propagate the nonsense.
        val estimate = PositionEstimator.estimate(
            playing(positionMs = 1_000, atMs = 10_000),
            nowElapsedRealtimeMs = 5_000,
            durationMs = THREE_MINUTES,
        )

        assertEquals(1_000L, estimate)
    }

    @Test
    fun `advanced is zero while paused and the elapsed amount while playing`() {
        assertEquals(
            0L,
            PositionEstimator.advancedMs(paused(positionMs = 1_000, atMs = 0), nowElapsedRealtimeMs = 5_000),
        )
        assertEquals(
            5_000L,
            PositionEstimator.advancedMs(playing(positionMs = 1_000, atMs = 0), nowElapsedRealtimeMs = 5_000),
        )
    }

    private companion object {
        const val THREE_MINUTES = 180_000L
    }
}
