package com.example.evergreen.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All times here are on a fake monotonic clock, in milliseconds, exactly as
 * SystemClock.elapsedRealtime() would report them on a device.
 */
class SkipDetectorTest {

    private fun track(
        title: String,
        durationMs: Long = THREE_MINUTES,
        artist: String = "Test Artist",
        album: String = "Test Album",
    ) = TrackInfo(title = title, artist = artist, album = album, durationMs = durationMs)

    private fun playing(positionMs: Long, atMs: Long) =
        PositionSample(positionMs, atMs, speed = 1.0f, isPlaying = true)

    private fun paused(positionMs: Long, atMs: Long) =
        PositionSample(positionMs, atMs, speed = 0.0f, isPlaying = false)

    @Test
    fun `first track produces no verdict`() {
        val detector = SkipDetector()
        assertNull(detector.onTrackChanged(track("A"), nowElapsedRealtimeMs = 1_000))
    }

    @Test
    fun `track played to the end is completed`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A"), 0)
        // Last state update arrives near the end of the track.
        detector.onPositionSample(playing(positionMs = 175_000, atMs = 175_000))

        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 180_000)

        assertNotNull(verdict)
        assertEquals(Outcome.COMPLETED, verdict!!.outcome)
        assertEquals("A", verdict.track.title)
    }

    @Test
    fun `track abandoned early is skipped`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A"), 0)
        detector.onPositionSample(playing(positionMs = 10_000, atMs = 10_000))

        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 12_000)

        assertEquals(Outcome.SKIPPED, verdict!!.outcome)
        assertEquals(12_000L, verdict.playedMs)
    }

    @Test
    fun `position is extrapolated forward from a stale-but-fresh-enough sample`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A"), 0)
        // Reading taken at 100s says we were 100s in. Track changes 20s later
        // with no further updates: we should infer 120s played, not 100s.
        detector.onPositionSample(playing(positionMs = 100_000, atMs = 100_000))

        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 120_000)

        assertEquals(120_000L, verdict!!.playedMs)
    }

    @Test
    fun `paused playback does not advance the estimated position`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A"), 0)
        detector.onPositionSample(paused(positionMs = 30_000, atMs = 30_000))

        // Sat paused for a long time, then the user changed track.
        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 600_000)

        assertEquals(Outcome.SKIPPED, verdict!!.outcome)
        assertEquals(30_000L, verdict.playedMs)
    }

    @Test
    fun `playback speed scales the extrapolation`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A"), 0)
        detector.onPositionSample(
            PositionSample(positionMs = 10_000, atElapsedRealtimeMs = 10_000, speed = 2.0f, isPlaying = true),
        )

        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 20_000)

        // 10s of wall clock at 2x = 20s of track, on top of the 10s snapshot.
        assertEquals(30_000L, verdict!!.playedMs)
    }

    @Test
    fun `estimated position never exceeds the track duration`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A", durationMs = 60_000), 0)
        detector.onPositionSample(playing(positionMs = 55_000, atMs = 55_000))

        // Extrapolating naively would give 75s for a 60s track.
        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 75_000)

        assertEquals(60_000L, verdict!!.playedMs)
        assertEquals(1.0, verdict.fraction!!, 0.001)
        assertEquals(Outcome.COMPLETED, verdict.outcome)
    }

    @Test
    fun `no position reading yields unknown`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A"), 0)
        // Metadata arrived but no playback state ever did.

        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 5_000)

        assertEquals(Outcome.UNKNOWN, verdict!!.outcome)
        assertNull(verdict.playedMs)
        assertTrue(verdict.reason.contains("no position reading"))
    }

    @Test
    fun `missing duration yields unknown`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A", durationMs = 0), 0)
        detector.onPositionSample(playing(positionMs = 30_000, atMs = 30_000))

        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 31_000)

        assertEquals(Outcome.UNKNOWN, verdict!!.outcome)
        assertTrue(verdict.reason.contains("duration unknown"))
    }

    @Test
    fun `stale position reading while playing yields unknown rather than a guess`() {
        val detector = SkipDetector(stalePositionMs = 30_000)
        detector.onTrackChanged(track("A"), 0)
        detector.onPositionSample(playing(positionMs = 5_000, atMs = 5_000))

        // Callbacks went quiet for five minutes; extrapolating would be fiction.
        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 305_000)

        assertEquals(Outcome.UNKNOWN, verdict!!.outcome)
        assertTrue(verdict.reason.contains("stale"))
    }

    @Test
    fun `repeated metadata for the same track is not a track change`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A"), 0)
        detector.onPositionSample(playing(positionMs = 5_000, atMs = 5_000))

        assertNull(detector.onTrackChanged(track("A"), nowElapsedRealtimeMs = 6_000))
    }

    @Test
    fun `duration arriving late is adopted for the current track`() {
        val detector = SkipDetector()
        // Spotify often publishes metadata without a duration first.
        detector.onTrackChanged(track("A", durationMs = 0), 0)
        detector.onTrackChanged(track("A", durationMs = THREE_MINUTES), 500)
        detector.onPositionSample(playing(positionMs = 175_000, atMs = 175_000))

        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 176_000)

        assertEquals(Outcome.COMPLETED, verdict!!.outcome)
    }

    @Test
    fun `completion threshold boundary counts as completed`() {
        val detector = SkipDetector(completionFraction = 0.90)
        detector.onTrackChanged(track("A", durationMs = 100_000), 0)
        detector.onPositionSample(playing(positionMs = 90_000, atMs = 90_000))

        val verdict = detector.onTrackChanged(track("B"), nowElapsedRealtimeMs = 90_000)

        assertEquals(Outcome.COMPLETED, verdict!!.outcome)
    }

    @Test
    fun `state resets between tracks so one verdict cannot leak into the next`() {
        val detector = SkipDetector()
        detector.onTrackChanged(track("A"), 0)
        detector.onPositionSample(playing(positionMs = 175_000, atMs = 175_000))
        detector.onTrackChanged(track("B"), 176_000)

        // B gets no position sample of its own.
        val verdict = detector.onTrackChanged(track("C"), nowElapsedRealtimeMs = 180_000)

        assertEquals(Outcome.UNKNOWN, verdict!!.outcome)
        assertEquals("B", verdict.track.title)
    }

    private companion object {
        const val THREE_MINUTES = 180_000L
    }
}
