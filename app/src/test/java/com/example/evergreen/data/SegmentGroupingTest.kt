package com.example.evergreen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merging is the proof that capture's fine-grained cutting is reversible: the
 * same stored rows answer "was that a replay?" differently depending on the
 * threshold applied, with nothing needing to be recorded again.
 */
class SegmentGroupingTest {

    private var nextId = 1L

    private fun segment(
        trackId: Long = 1,
        listenedMs: Long = 10_000,
        startPositionMs: Long = 0,
        endPositionMs: Long = 10_000,
        endReason: EndReason = EndReason.TRACK_CHANGE,
        jumpFromMs: Long? = null,
        jumpToMs: Long? = null,
    ) = ListeningSegmentEntity(
        id = nextId++,
        trackId = trackId,
        serviceTrackRowId = null,
        appPackage = "com.spotify.music",
        deviceId = "test-device",
        startedAtMs = 1_000 * nextId,
        endedAtMs = 1_000 * nextId + 500,
        startPositionMs = startPositionMs,
        endPositionMs = endPositionMs,
        listenedMs = listenedMs,
        trackDurationMs = 180_000,
        forwardSeekCount = 0,
        seekedForwardMs = 0,
        endReason = endReason,
        endJumpFromMs = jumpFromMs,
        endJumpToMs = jumpToMs,
        lastSampleAgeMs = 0,
        createdAt = 0,
    )

    /** Two rows for one track, separated by a ten-second scrub back. */
    private fun scrubBackPair(): List<ListeningSegmentEntity> {
        val first = segment(
            listenedMs = 60_000,
            endPositionMs = 60_000,
            endReason = EndReason.POSITION_JUMP_BACK,
            jumpFromMs = 60_000,
            jumpToMs = 50_000,
        )
        val second = segment(listenedMs = 5_000, startPositionMs = 50_000, endPositionMs = 55_000)
        return listOf(second, first) // newest first, as the DAO returns them
    }

    @Test
    fun `an empty history merges to nothing`() {
        assertEquals(emptyList<MergedSegment>(), SegmentGrouping.merge(emptyList(), 30_000))
    }

    @Test
    fun `a ten second scrub back is one listen under a thirty second threshold`() {
        val merged = SegmentGrouping.merge(scrubBackPair(), replayThresholdMs = 30_000)

        assertEquals(1, merged.size)
        val listen = merged.single()
        assertTrue(listen.isMerged)
        // Listened time adds up across the merge...
        assertEquals(65_000L, listen.listenedMs)
        // ...and the listen spans from the first start to the last end.
        assertEquals(0L, listen.startPositionMs)
        assertEquals(55_000L, listen.endPositionMs)
    }

    @Test
    fun `the same rows are two listens under a five second threshold`() {
        val merged = SegmentGrouping.merge(scrubBackPair(), replayThresholdMs = 5_000)

        assertEquals(2, merged.size)
        assertTrue(merged.none { it.isMerged })
    }

    @Test
    fun `a null threshold treats every recorded jump as a replay`() {
        val merged = SegmentGrouping.merge(scrubBackPair(), replayThresholdMs = null)

        assertEquals(2, merged.size)
    }

    @Test
    fun `segments for different tracks never merge`() {
        val first = segment(
            trackId = 1,
            endReason = EndReason.POSITION_JUMP_BACK,
            jumpFromMs = 60_000,
            jumpToMs = 59_000,
        )
        val second = segment(trackId = 2)

        val merged = SegmentGrouping.merge(listOf(second, first), replayThresholdMs = 600_000)

        assertEquals(2, merged.size)
    }

    @Test
    fun `a track change never merges into the next listen`() {
        val first = segment(endReason = EndReason.TRACK_CHANGE)
        val second = segment()

        val merged = SegmentGrouping.merge(listOf(second, first), replayThresholdMs = 600_000)

        assertEquals(2, merged.size)
    }

    @Test
    fun `a full loop stays separate even under a generous threshold`() {
        // Looping at 98.9% is a jump of nearly the whole track, so no sane
        // threshold folds it away.
        val looped = segment(
            listenedMs = 107_800,
            endPositionMs = 107_800,
            endReason = EndReason.POSITION_JUMP_BACK,
            jumpFromMs = 107_800,
            jumpToMs = 1,
        )
        val afterLoop = segment(listenedMs = 4_000, endPositionMs = 4_000)

        val merged = SegmentGrouping.merge(listOf(afterLoop, looped), replayThresholdMs = 30_000)

        assertEquals(2, merged.size)
        assertFalse(merged.any { it.isMerged })
    }

    @Test
    fun `three rows chained by small jumps fold into a single listen`() {
        val first = segment(
            listenedMs = 30_000,
            endReason = EndReason.POSITION_JUMP_BACK,
            jumpFromMs = 30_000,
            jumpToMs = 28_000,
        )
        val second = segment(
            listenedMs = 20_000,
            endReason = EndReason.POSITION_JUMP_BACK,
            jumpFromMs = 48_000,
            jumpToMs = 45_000,
        )
        val third = segment(listenedMs = 10_000, endPositionMs = 55_000)

        val merged = SegmentGrouping.merge(listOf(third, second, first), replayThresholdMs = 5_000)

        assertEquals(1, merged.size)
        assertEquals(60_000L, merged.single().listenedMs)
        assertEquals(3, merged.single().rows.size)
    }

    @Test
    fun `seek counts add up across a merge`() {
        val first = segment(
            endReason = EndReason.POSITION_JUMP_BACK,
            jumpFromMs = 60_000,
            jumpToMs = 58_000,
        ).copy(forwardSeekCount = 2, seekedForwardMs = 20_000)
        val second = segment().copy(forwardSeekCount = 1, seekedForwardMs = 5_000)

        val merged = SegmentGrouping.merge(listOf(second, first), replayThresholdMs = 10_000).single()

        assertEquals(3, merged.forwardSeekCount)
        assertEquals(25_000L, merged.seekedForwardMs)
    }

    @Test
    fun `listened fraction and position reached are derived separately`() {
        val looped = segment(
            listenedMs = 3_000,
            endPositionMs = 178_000,
            endReason = EndReason.TRACK_CHANGE,
        )

        val merged = SegmentGrouping.merge(listOf(looped), replayThresholdMs = 30_000).single()

        // Seek-to-end: position reached is ~99% but almost nothing was heard.
        assertTrue(merged.positionReachedFraction!! > 0.98)
        assertTrue(merged.listenedFraction!! < 0.02)
    }
}
