package com.example.evergreen.scoring

import com.example.evergreen.data.EndReason
import com.example.evergreen.data.LikeEventType
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

/**
 * Change A of scoring freshness: the higher a track's score already is, the
 * less a new play adds, so a binge cannot bank a score that takes months of
 * silence to fall.
 */
class ScoreCeilingTest {

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val duration = 200_000L
    private val scorer = TrackScorer(ZoneId.of("Europe/London"))
    private val ceiling8 = ScoringParameters(scoreCeiling = 8.0)
    private var nextId = 1L

    private fun segment(
        at: Long,
        ms: Long = duration,
        device: String = "walkman",
        id: Long = nextId++,
        originRowId: Long? = null,
    ) = ListeningSegmentEntity(
        id = id, trackId = 1L, serviceTrackRowId = 10L,
        appPackage = "com.spotify.music", deviceId = device,
        startedAtMs = at - ms, endedAtMs = at, startPositionMs = 0L, endPositionMs = ms,
        listenedMs = ms, trackDurationMs = duration, forwardSeekCount = 0, seekedForwardMs = 0L,
        endReason = EndReason.TRACK_CHANGE, endJumpFromMs = null, endJumpToMs = null,
        lastSampleAgeMs = 100L, createdAt = at, source = SegmentSource.CAPTURE,
        originRowId = originRowId,
    )

    private fun score(
        segments: List<ListeningSegmentEntity>,
        parameters: ScoringParameters,
        likes: List<LikeEventRecord> = emptyList(),
        atMs: Long = now,
    ) = scorer.score(
        TrackScoringInput(1L, segments, mapOf(10L to ServiceTrackInfo("spotify:track:a", duration)), duration, likes),
        parameters, atMs,
    )

    /**
     * The prompt's named case: a song binged, then abandoned.
     *
     * Two plays a day for thirty days, then nothing. Without the ceiling it
     * banks a score that takes months of silence to fall back under an
     * everyday favourite's; with it, the bank is held near the ceiling and the
     * same silence clears it in a fraction of the time.
     */
    @Test
    fun `a binged then abandoned song banks less and falls out sooner`() {
        val bingeEnd = now - 10 * day
        val binge = (0 until 30).flatMap { d ->
            listOf(segment(bingeEnd - d * day), segment(bingeEnd - d * day + 3_600_000))
        }
        // A steady favourite: one play every three days, still going.
        val steady = (0 until 40).map { segment(now - it * 3 * day) }

        val off = ScoringParameters()
        val bingeOff = score(binge, off).score
        val bingeOn = score(binge, ceiling8).score
        assertTrue("the ceiling holds the bank: $bingeOn", bingeOn <= 8.0)
        assertTrue("and well below what it was: $bingeOn against $bingeOff", bingeOn < bingeOff / 2)

        fun daysUntilBelow(segments: List<ListeningSegmentEntity>, parameters: ScoringParameters, line: Double): Int =
            (0..400).first { extra -> score(segments, parameters, atMs = now + extra * day).score < line }

        val line = score(steady, ceiling8).score
        val daysOff = daysUntilBelow(binge, off, line)
        val daysOn = daysUntilBelow(binge, ceiling8, line)
        assertTrue("falls under a steady favourite sooner: $daysOn days against $daysOff", daysOn < daysOff)
    }

    /**
     * Not "barely touched", which is what this test first claimed and what
     * the numbers refused: the brake is linear, so a song halfway to the
     * ceiling keeps only half of each new play. One play a fortnight scores
     * 3.74 without the ceiling and 2.98 with it at 8 — a fifth less. The
     * ceiling compresses the middle of the ranking too, not only the top,
     * which is worth knowing before choosing its value.
     */
    @Test
    fun `light listening loses about a fifth at a ceiling of eight`() {
        val light = (0 until 6).map { segment(now - it * 14 * day) }
        val off = score(light, ScoringParameters()).score
        val on = score(light, ceiling8).score
        assertEquals(3.74, off, 0.01)
        assertEquals(2.98, on, 0.01)
    }

    @Test
    fun `a skip costs the full amount however high the score`() {
        val banked = (0 until 20).map { segment(now - 20 * day + it * day) }
        val skip = segment(now - day / 2, ms = 2_000)
        val breakdown = score(banked + skip, ceiling8)
        val last = breakdown.listens.last()
        assertEquals(-0.5, last.playEquivalents, 0.0)
        assertTrue(last.adjustments.isEmpty())
    }

    @Test
    fun `a reduced play says so, with what it was worth before`() {
        val breakdown = score(listOf(segment(now - 2 * day), segment(now - day)), ceiling8)
        val first = breakdown.listens.first()
        assertEquals("nothing banked yet, so the first play is whole", 1.0, first.playEquivalents, 0.0)
        val second = breakdown.listens.last()
        val adjustment = second.adjustments.single()
        assertEquals(AdjustmentKind.CEILING, adjustment.kind)
        assertEquals(1.0, adjustment.before, 0.0)
        // One play two days ago has decayed a little: 1 × 2^(-1/45).
        val running = Decay.weightForAge(day, ceiling8.halfLifeMs)
        assertEquals(1.0 - running / 8.0, adjustment.after, 1e-12)
    }

    @Test
    fun `a score below zero never makes a play worth more than a play`() {
        val skips = (0 until 6).map { segment(now - 3 * day + it * 60_000L, ms = 2_000) }
        val breakdown = score(skips + segment(now - day), ceiling8)
        assertEquals(1.0, breakdown.listens.last().playEquivalents, 0.0)
    }

    @Test
    fun `likes saturate too`() {
        val banked = (0 until 20).map { segment(now - 30 * day + it * day) }
        val like = LikeEventRecord(LikeEventType.LIKED, now - day)
        val breakdown = score(banked, ceiling8, likes = listOf(like))
        assertTrue(breakdown.likes.single().playEquivalents < 3.0)
    }

    /**
     * The prompt's named case: same-timestamp events arriving in different
     * orders. A listen recorded on both phones, ending on the same
     * millisecond, a like on that millisecond too, and rows whose local ids
     * differ between the devices holding them.
     */
    @Test
    fun `tied events give the same score whatever order they arrive in`() {
        val tie = now - 3 * day
        // As the Walkman holds them: its own rows by local id, the Pixel's by
        // the Pixel's original id.
        val walkman = listOf(
            segment(tie, id = 1L),
            segment(tie, ms = 2_000, device = "pixel", id = 2L, originRowId = 70L),
            segment(tie - day, id = 3L),
            segment(tie, ms = duration / 2, device = "pixel", id = 4L, originRowId = 71L),
        )
        // As the Pixel holds the same facts: the Pixel's rows under their own
        // ids, the Walkman's under new local ids pointing back at the originals.
        val pixel = listOf(
            segment(tie, id = 501L, originRowId = 1L),
            segment(tie, ms = 2_000, device = "pixel", id = 70L),
            segment(tie - day, id = 503L, originRowId = 3L),
            segment(tie, ms = duration / 2, device = "pixel", id = 71L),
        )
        val likes = listOf(LikeEventRecord(LikeEventType.LIKED, tie), LikeEventRecord(LikeEventType.UNLIKED, tie))
        val reference = score(walkman, ceiling8, likes).score

        repeat(20) { seed ->
            val random = Random(seed)
            assertEquals(reference, score(walkman.shuffled(random), ceiling8, likes.shuffled(random)).score, 0.0)
            assertEquals(reference, score(pixel.shuffled(random), ceiling8, likes.shuffled(random)).score, 0.0)
        }
    }

    @Test
    fun `off writes no key, and on round-trips`() {
        assertFalse(ScoringParameters().toJson().contains("scoreCeiling"))
        assertEquals(ceiling8, ScoringParameters.fromJson(ceiling8.toJson()))
        assertEquals(1, ScoringParameters(scoreCeiling = -1.0).problems().size)
    }
}
