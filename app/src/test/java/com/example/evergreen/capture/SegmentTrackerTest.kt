package com.example.evergreen.capture

import com.example.evergreen.data.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Times are on a fake monotonic clock in milliseconds, as
 * SystemClock.elapsedRealtime() would report them. Wall-clock values are the
 * same numbers offset by [WALL_BASE], which is all the tracker needs.
 */
class SegmentTrackerTest {

    private val closed = mutableListOf<ClosedSegment>()
    private val tracker = SegmentTracker(PACKAGE) { closed += it }

    // --- Driving helpers ----------------------------------------------------

    private fun metadata(
        title: String,
        at: Long,
        durationMs: Long = THREE_MINUTES,
        serviceTrackId: String? = "spotify:track:$title",
    ) = tracker.onMetadata(
        TrackInfo(
            title = title,
            artist = "Test Artist",
            album = "Test Album",
            durationMs = durationMs,
            serviceTrackId = serviceTrackId,
        ),
        nowElapsedRealtimeMs = at,
        nowWallMs = WALL_BASE + at,
    )

    private fun state(
        positionMs: Long,
        at: Long,
        playing: Boolean = true,
        speed: Float = if (playing) 1.0f else 0.0f,
    ) = tracker.onPlaybackState(
        PositionSample(positionMs, at, speed, playing),
        nowElapsedRealtimeMs = at,
        nowWallMs = WALL_BASE + at,
    )

    /** Closes everything so that held-back segments are emitted. */
    private fun finish(at: Long): List<ClosedSegment> {
        tracker.onSessionEnd(nowElapsedRealtimeMs = at, nowWallMs = WALL_BASE + at)
        return closed
    }

    // --- Basics -------------------------------------------------------------

    @Test
    fun `first metadata opens a segment and emits nothing`() {
        metadata("A", at = 0)

        assertTrue(closed.isEmpty())
        assertEquals("A", tracker.currentTrack?.title)
    }

    @Test
    fun `identical metadata callbacks are ignored`() {
        // onMetadataChanged re-fires with byte-identical content up to ten times
        // a second during transitions. De-duplication compares the content, not
        // the number of callbacks.
        assertTrue(metadata("A", at = 0))
        assertFalse(metadata("A", at = 100))
        assertFalse(metadata("A", at = 200))
        assertFalse(metadata("A", at = 300))

        state(1_000, at = 1_000)
        val segments = finish(at = 2_000)

        assertEquals(1, segments.size)
    }

    @Test
    fun `a track played through records the whole track as listened`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(175_000, at = 175_000)
        metadata("B", at = 180_000)

        val segments = finish(at = 180_500)

        val a = segments.first()
        assertEquals(EndReason.TRACK_CHANGE, a.endReason)
        assertEquals(THREE_MINUTES, a.listenedMs)
        assertEquals(THREE_MINUTES, a.endPositionMs)
    }

    @Test
    fun `a track abandoned early records only what was heard`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(10_000, at = 10_000)
        metadata("B", at = 12_000)

        val a = finish(at = 12_500).first()

        assertEquals(12_000L, a.listenedMs)
        assertEquals(EndReason.TRACK_CHANGE, a.endReason)
        // No verdict is recorded. Whether 12s of 180s counts as a skip is a
        // Phase 3 question, and this row deliberately does not answer it.
    }

    // --- Replays: the Phase 0 correctness bug -------------------------------

    @Test
    fun `restarting mid-track closes one segment and opens another`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(60_000, at = 60_000)
        // User presses "previous", restarting the track.
        state(118, at = 60_500)
        state(5_000, at = 65_500)

        val segments = finish(at = 66_000)

        assertEquals(2, segments.size)
        val first = segments[0]
        assertEquals(EndReason.POSITION_JUMP_BACK, first.endReason)
        assertEquals(60_500L, first.endPositionMs)
        assertEquals(60_500L, first.listenedMs)
        assertEquals(60_500L, first.endJumpFromMs)
        assertEquals(118L, first.endJumpToMs)

        val second = segments[1]
        assertEquals(118L, second.startPositionMs)
        assertEquals(EndReason.SESSION_END, second.endReason)
    }

    @Test
    fun `a repeat-one loop is recorded as a near-complete listen, not a skip`() {
        // The exact Phase 0 failure: a track looped at 98.9% used to be written
        // as a single 3% SKIP.
        metadata("A", at = 0, durationMs = SHORT_TRACK)
        state(0, at = 0)
        state(107_800, at = 107_800)
        // Loops back to the start with no metadata change.
        state(1, at = 108_700)

        val segments = finish(at = 110_000)

        val loopedListen = segments.first()
        assertEquals(EndReason.POSITION_JUMP_BACK, loopedListen.endReason)
        // Position reached is what distinguishes a loop from a mid-track restart.
        val fractionReached = loopedListen.endPositionMs.toDouble() / SHORT_TRACK
        assertTrue("reached $fractionReached, expected ~99%", fractionReached > 0.97)
        assertTrue(loopedListen.listenedMs > 107_000)
    }

    @Test
    fun `pressing previous twice stores the short fragment without labelling it`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(45_000, at = 45_000)
        // First press: restarts A.
        state(0, at = 45_500)
        state(1_000, at = 46_500)
        // Second press, about a second later: moves to the previous track.
        metadata("Z", at = 46_600)

        val segments = finish(at = 47_000)

        assertEquals(3, segments.size)
        assertEquals(45_500L, segments[0].listenedMs)
        assertEquals(EndReason.POSITION_JUMP_BACK, segments[0].endReason)

        val fragment = segments[1]
        assertEquals(EndReason.TRACK_CHANGE, fragment.endReason)
        assertEquals(1_100L, fragment.listenedMs)
        // The fragment is stored as its own row. Nothing here calls it a skip;
        // the first listen of 45.5s is preserved alongside it rather than lost.
    }

    // --- Seeking ------------------------------------------------------------

    @Test
    fun `seeking to the end then auto-advancing is not a complete listen`() {
        metadata("A", at = 0, durationMs = SHORT_TRACK)
        state(0, at = 0)
        state(3_800, at = 3_800)
        // Seek forward to near the end.
        state(105_000, at = 4_000)
        // Let it run out and advance.
        state(108_000, at = 7_000)
        metadata("B", at = 8_000)

        val a = finish(at = 8_500).first()

        // Position reached is high...
        assertTrue(a.endPositionMs > 108_000)
        // ...but almost none of the track was actually heard, so nothing can
        // mistake this for a completed play.
        assertTrue("listened ${a.listenedMs}", a.listenedMs < 10_000)
        assertEquals(1, a.forwardSeekCount)
        assertTrue(a.seekedForwardMs > 100_000)
    }

    @Test
    fun `a forward seek is excluded from listened time`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(10_000, at = 10_000)
        state(100_000, at = 10_100)
        state(105_000, at = 15_100)

        val a = finish(at = 15_200).first()

        // 10s before the seek plus 5s after it, not the 105s of position span.
        assertEquals(15_200L, a.listenedMs)
        assertEquals(1, a.forwardSeekCount)
    }

    @Test
    fun `eighty milliseconds of backwards jitter does not start a new segment`() {
        // Measured on device with no user action at all: reported=128ms then
        // reported=52ms.
        metadata("A", at = 0)
        state(128, at = 1_000)
        state(52, at = 1_010)
        state(5_000, at = 6_000)

        val segments = finish(at = 6_100)

        assertEquals(1, segments.size)
        assertEquals(EndReason.SESSION_END, segments[0].endReason)
    }

    @Test
    fun `a ten second scrub back is cut but merges again at a Phase 3 threshold`() {
        // Capture cuts at every real backwards jump, because cutting finely can
        // be undone and cutting coarsely cannot. Whether a ten-second scrub back
        // counts as a replay is decided later, from the recorded jump size.
        metadata("A", at = 0)
        state(0, at = 0)
        state(60_000, at = 60_000)
        state(50_000, at = 60_100)
        state(55_000, at = 65_100)

        val segments = finish(at = 65_200)

        assertEquals(2, segments.size)
        val jump = segments[0]
        assertEquals(EndReason.POSITION_JUMP_BACK, jump.endReason)
        assertEquals(60_100L, jump.endJumpFromMs)
        assertEquals(50_000L, jump.endJumpToMs)
        // The jump size is 10.1s, so any Phase 3 rule with a threshold above
        // that folds these two rows back into a single listen.
        assertEquals(10_100L, jump.endJumpFromMs!! - jump.endJumpToMs!!)
    }

    // --- Ordering and lifecycle --------------------------------------------

    @Test
    fun `a track change seen as a jump to zero is corrected, not recorded twice`() {
        // At a real track change the session sometimes publishes the new track's
        // position before its metadata, which is indistinguishable from a jump
        // back to the start until the metadata lands a moment later.
        metadata("A", at = 0)
        state(0, at = 0)
        state(170_000, at = 170_000)
        state(0, at = 171_000)
        metadata("B", at = 171_100)

        val segments = finish(at = 171_200)

        assertEquals(2, segments.size)
        val a = segments[0]
        assertEquals(EndReason.TRACK_CHANGE, a.endReason)
        assertNull(a.endJumpFromMs)
        assertEquals(171_000L, a.listenedMs)
        assertEquals("B", segments[1].track.title)
    }

    @Test
    fun `a finished segment is written even if playback stops right after`() {
        // Found on device: a track change at 02:19:36.3 followed by playback
        // stopping a second later left the finished segment held in hand and
        // unwritten. Nothing further was ever heard, so waiting for the next
        // segment to accrue listening would have waited forever.
        metadata("A", at = 0)
        state(0, at = 0)
        state(60_000, at = 60_000)
        metadata("B", at = 60_100)
        state(0, at = 60_200)
        // ...and playback stops here. No more callbacks arrive.

        assertTrue("segment should still be amendable", closed.isEmpty())

        tracker.flushPendingIfSettled(
            nowWallMs = WALL_BASE + 60_200 + CaptureThresholds.AMEND_WINDOW_MS + 1,
        )

        assertEquals(1, closed.size)
        assertEquals("A", closed[0].track.title)
        assertEquals(60_100L, closed[0].listenedMs)
    }

    @Test
    fun `settling early does not release a segment that can still be amended`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(170_000, at = 170_000)
        state(0, at = 171_000) // looks like a jump back

        // Well inside the amend window: releasing now would write a jump back
        // that the metadata change arriving next would have corrected.
        tracker.flushPendingIfSettled(nowWallMs = WALL_BASE + 171_100)
        assertTrue(closed.isEmpty())

        metadata("B", at = 171_200)
        val segments = finish(at = 171_300)

        assertEquals(2, segments.size)
        assertEquals(EndReason.TRACK_CHANGE, segments[0].endReason)
    }

    @Test
    fun `a late-arriving duration is adopted for the current track`() {
        // Spotify often publishes metadata without a duration first.
        metadata("A", at = 0, durationMs = 0)
        metadata("A", at = 500, durationMs = THREE_MINUTES)
        state(0, at = 500)
        state(175_000, at = 175_500)

        val a = finish(at = 176_000).first()

        assertEquals(THREE_MINUTES, a.trackDurationMs)
    }

    @Test
    fun `position readings do not leak from one track into the next`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(175_000, at = 175_000)
        metadata("B", at = 176_000)
        // B gets no readings of its own before the session ends.

        val segments = finish(at = 180_000)

        assertEquals(2, segments.size)
        val b = segments[1]
        assertEquals("B", b.track.title)
        assertEquals(0L, b.listenedMs)
        assertEquals(EndReason.UNKNOWN, b.endReason)
    }

    @Test
    fun `a session ending closes the open segment`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(30_000, at = 30_000)

        val segments = finish(at = 30_100)

        assertEquals(1, segments.size)
        assertEquals(EndReason.SESSION_END, segments[0].endReason)
        assertEquals(30_100L, segments[0].listenedMs)
    }

    @Test
    fun `a segment with no readings at all is closed as unknown`() {
        metadata("A", at = 0)

        val segments = finish(at = 5_000)

        assertEquals(1, segments.size)
        assertEquals(EndReason.UNKNOWN, segments[0].endReason)
        assertEquals(0L, segments[0].listenedMs)
    }

    @Test
    fun `going quiet for a long time closes the segment as stale`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(30_000, at = 30_000, playing = false)

        tracker.onIdleCheck(
            nowElapsedRealtimeMs = 30_000 + CaptureThresholds.STALE_CLOSE_MS + 1,
            nowWallMs = WALL_BASE + 30_000 + CaptureThresholds.STALE_CLOSE_MS + 1,
        )

        assertEquals(1, closed.size)
        assertEquals(EndReason.STALE, closed[0].endReason)
        // Paused the whole time, so nothing extra was invented.
        assertEquals(30_000L, closed[0].listenedMs)
    }

    @Test
    fun `how stale the final reading was is recorded on the segment`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(5_000, at = 5_000)
        metadata("B", at = 65_000)

        val a = finish(at = 65_100).first()

        assertEquals(60_000L, a.lastSampleAgeMs)
        assertTrue(
            "a 60s gap should be flagged as beyond the stale threshold",
            a.lastSampleAgeMs > CaptureThresholds.STALE_POSITION_MS,
        )
    }

    @Test
    fun `paused time is not counted as listening`() {
        metadata("A", at = 0)
        state(0, at = 0)
        state(30_000, at = 30_000)
        state(30_000, at = 30_100, playing = false)

        val a = finish(at = 630_000).first()

        // Ten minutes paused adds nothing.
        //
        // Note this is 30_000 and not 30_100: the pause reading arrived 100ms
        // after the previous one but carried the same position, so the position
        // cross-check refused to corroborate that last 100ms and it was not
        // counted. Under-counting by a fraction of a second is the deliberate
        // trade-off — the rule always errs downwards rather than inventing time.
        assertEquals(30_000L, a.listenedMs)
    }

    // --- The Step 0 capture, end to end -------------------------------------

    @Test
    fun `the Step 0 capture produces three listens instead of one false skip`() {
        // Replays the capture recorded in docs/PLAN.md: a track played to 98.9%,
        // looped, seeked into the middle, restarted, heard for four more
        // seconds, then skipped on. Phase 0 recorded this as a single SKIP at
        // 3% — a deliberately looped song scored as a rejection.
        metadata("Metadata", at = 0, durationMs = SHORT_TRACK, serviceTrackId = SPOTIFY_URI)
        state(103_200, at = 0)
        state(107_800, at = 4_600)
        state(107_800, at = 4_900, playing = false, speed = 0.0f) // BUFFERING
        state(1, at = 5_400) // loop
        state(38_500, at = 7_700) // forward seek
        state(118, at = 9_600) // restart
        state(4_000, at = 13_600)
        state(4_000, at = 13_700, playing = false)
        metadata("Next Song", at = 32_500, durationMs = THREE_MINUTES)

        val segments = finish(at = 32_600)

        assertEquals(4, segments.size)

        val a = segments[0]
        assertEquals(EndReason.POSITION_JUMP_BACK, a.endReason)
        assertTrue("A reached ${a.endPositionMs}", a.endPositionMs > 107_000)
        assertEquals(SPOTIFY_URI, a.track.serviceTrackId)

        val b = segments[1]
        assertEquals(EndReason.POSITION_JUMP_BACK, b.endReason)
        assertEquals(1, b.forwardSeekCount)
        assertTrue("B listened ${b.listenedMs}", b.listenedMs in 2_000..5_000)
        assertTrue("B reached ${b.endPositionMs}", b.endPositionMs in 38_000..42_000)

        val c = segments[2]
        assertEquals(EndReason.TRACK_CHANGE, c.endReason)
        assertTrue("C listened ${c.listenedMs}", c.listenedMs in 3_500..4_200)
        assertTrue("C reached ${c.endPositionMs}", c.endPositionMs < 5_000)

        // The essential property: three listens of the looped track, the first
        // of them essentially complete — not one row saying "skipped at 3%".
        assertEquals(3, segments.count { it.track.title == "Metadata" })
        assertNotNull(segments.firstOrNull { it.endPositionMs > 107_000 })
    }

    private companion object {
        const val PACKAGE = "com.spotify.music"
        const val WALL_BASE = 1_758_000_000_000L
        const val THREE_MINUTES = 180_000L
        const val SHORT_TRACK = 109_000L
        const val SPOTIFY_URI = "spotify:track:4h3K5he5ItFqJB1MVHMKJV"
    }
}
