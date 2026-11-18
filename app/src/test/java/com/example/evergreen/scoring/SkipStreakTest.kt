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

/**
 * Change D of scoring freshness: a song skipped on several days in a row
 * costs more each day, and one day can cost at most `dailyCapSkips` skips.
 */
class SkipStreakTest {

    /** 2026-09-17T12:00:00Z, so `now - k days` is midday London time. */
    private val now = 1_789_128_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val duration = 200_000L
    private val scorer = TrackScorer(ZoneId.of("Europe/London"))
    private val streaks = ScoringParameters(skipStreakGrowth = 2.0, skipStreakMaxMultiplier = 4.0)
    private var nextId = 1L

    private fun segment(at: Long, ms: Long) = ListeningSegmentEntity(
        id = nextId++, trackId = 1L, serviceTrackRowId = 10L,
        appPackage = "com.spotify.music", deviceId = "walkman",
        startedAtMs = at - ms, endedAtMs = at, startPositionMs = 0L, endPositionMs = ms,
        listenedMs = ms, trackDurationMs = duration, forwardSeekCount = 0, seekedForwardMs = 0L,
        endReason = EndReason.TRACK_CHANGE, endJumpFromMs = null, endJumpToMs = null,
        lastSampleAgeMs = 100L, createdAt = at, source = SegmentSource.CAPTURE,
    )

    private fun skip(daysAgo: Int, minute: Int = 0) = segment(now - daysAgo * day + minute * 60_000L, 2_000)
    private fun play(daysAgo: Int, minute: Int = 0) = segment(now - daysAgo * day + minute * 60_000L, duration)

    private fun score(
        segments: List<ListeningSegmentEntity>,
        parameters: ScoringParameters = streaks,
        likes: List<LikeEventRecord> = emptyList(),
        dj: List<DjWindow> = emptyList(),
    ) = scorer.score(
        TrackScoringInput(1L, segments, mapOf(10L to ServiceTrackInfo("spotify:track:a", duration)), duration, likes),
        parameters, now, dj,
    )

    private fun ScoreBreakdown.values() = listens.map { it.playEquivalents }

    /** The prompt's named case. */
    @Test
    fun `skip, skip, full listen, skip resets the streak`() {
        val breakdown = score(listOf(skip(4), skip(3), play(2), skip(1)))
        assertEquals(listOf(-0.5, -1.0, 1.0, -0.5), breakdown.values())
        val second = breakdown.listens[1].adjustments.single()
        assertEquals(AdjustmentKind.SKIP_STREAK, second.kind)
        assertEquals(2, second.streakDays)
        assertEquals(2.0, second.multiplier!!, 0.0)
        assertTrue("the first skip day costs what it always did", breakdown.listens[0].adjustments.isEmpty())
    }

    @Test
    fun `the multiplier grows each skip day and stops at the maximum`() {
        val breakdown = score((5 downTo 1).map { skip(it) })
        assertEquals(listOf(-0.5, -1.0, -2.0, -2.0, -2.0), breakdown.values())
    }

    @Test
    fun `days count, not skips`() {
        // Five skips in one evening are one skip day.
        val breakdown = score((0 until 5).map { skip(2, minute = it * 10) } + skip(1))
        assertEquals(List(5) { -0.5 } + -1.0, breakdown.values())
    }

    @Test
    fun `a day with nothing but forgiven skips leaves the streak alone`() {
        // Day 3 is a DJ day with the DJ weight at 0: its skip is forgiven to
        // 0, so the day is neither kind and the streak carries over.
        val djDay = now - 2 * day
        val dj = listOf(DjWindow(djDay - 60 * 60_000, djDay + 60 * 60_000))
        val breakdown = score(listOf(skip(3), skip(2), skip(1)), dj = dj)
        assertEquals(listOf(-0.5, 0.0, -1.0), breakdown.values())
    }

    @Test
    fun `a neutral listen leaves the streak alone`() {
        val half = segment(now - 2 * day, duration / 2)
        assertEquals(listOf(-0.5, 0.0, -1.0), score(listOf(skip(3), half, skip(1))).values())
    }

    @Test
    fun `a like does not reset the streak`() {
        val likes = listOf(LikeEventRecord(LikeEventType.LIKED, now - 2 * day))
        assertEquals(listOf(-0.5, -1.0), score(listOf(skip(3), skip(2)), likes = likes).values())
    }

    @Test
    fun `the daily skip cap holds one evening to one skip`() {
        val capped = ScoringParameters(dailyCapSkips = 1.0)
        val breakdown = score((0 until 4).map { skip(1, minute = it) }, capped)
        assertEquals(listOf(-0.5, 0.0, 0.0, 0.0), breakdown.values())
        assertEquals(AdjustmentKind.SKIP_CAP, breakdown.listens[1].adjustments.single().kind)
        // And without it, four skips are four.
        assertEquals(-2.0, score((0 until 4).map { skip(1, minute = it) }, ScoringParameters()).values().sum(), 0.0)
    }

    @Test
    fun `the cap comes before the streak multiplier`() {
        // Day 2 of a streak, three skips: capped to one skip, then doubled.
        val both = streaks.copy(dailyCapSkips = 1.0)
        val breakdown = score(listOf(skip(2)) + (0 until 3).map { skip(1, minute = it) }, both)
        assertEquals(-0.5 - 1.0, breakdown.values().sum(), 1e-12)
        val trimmedThenDoubled = breakdown.listens[2].adjustments
        assertEquals(listOf(AdjustmentKind.SKIP_CAP), trimmedThenDoubled.map { it.kind })
        assertEquals(listOf(AdjustmentKind.SKIP_STREAK), breakdown.listens[1].adjustments.map { it.kind })
    }

    @Test
    fun `plays and skips have separate allowances`() {
        // A skip day cap does not eat into the plays allowance, or the other way.
        val capped = ScoringParameters(dailyCapSkips = 1.0)
        val breakdown = score(listOf(play(1, 0), skip(1, 10), play(1, 20), skip(1, 30)), capped)
        assertEquals(listOf(1.0, -0.5, 1.0, 0.0), breakdown.values())
    }

    @Test
    fun `a long silence starts a fresh streak`() {
        // Skipped three times, each two months apart: without forgetting that
        // would be x1, x2, x4; with a 45-day reset each is a first skip day.
        val spread = listOf(skip(130), skip(65), skip(1))
        assertEquals(listOf(-0.5, -0.5, -0.5), score(spread).values())
        assertEquals(listOf(-0.5, -1.0, -2.0), score(spread, streaks.copy(skipStreakResetDays = 0)).values())
        // Inside the reset, the streak builds as before.
        assertEquals(listOf(-0.5, -1.0), score(listOf(skip(40), skip(1))).values())
    }

    @Test
    fun `the reset counts from when the song was last heard at all`() {
        // A neutral listen in between keeps the streak alive across the gap.
        val half = segment(now - 60 * day, duration / 2)
        assertEquals(listOf(-0.5, 0.0, -1.0), score(listOf(skip(100), half, skip(20))).values())
    }

    @Test
    fun `off writes no keys and has one form`() {
        assertFalse(ScoringParameters().toJson().contains("Streak"))
        assertFalse(ScoringParameters().toJson().contains("dailyCapSkips"))
        assertEquals(ScoringParameters(), ScoringParameters(skipStreakMaxMultiplier = 9.0).normalised())
        val on = streaks.copy(dailyCapSkips = 1.5)
        assertEquals(on, ScoringParameters.fromJson(on.toJson()))
        assertTrue(on.toJson().contains(""""skipStreakGrowth":2.0,"skipStreakMaxMultiplier":4.0,"skipStreakResetDays":45"""))
        assertEquals(ScoringParameters(), ScoringParameters(skipStreakResetDays = 3).normalised())
    }

    @Test
    fun `nonsense values are refused`() {
        assertEquals(1, ScoringParameters(skipStreakGrowth = 0.5).problems().size)
        assertEquals(1, ScoringParameters(skipStreakGrowth = 2.0, skipStreakMaxMultiplier = 0.5).problems().size)
        assertEquals(1, ScoringParameters(dailyCapSkips = -1.0).problems().size)
        assertEquals(1, streaks.copy(skipStreakResetDays = -1).problems().size)
    }
}
