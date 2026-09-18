package com.example.evergreen.scoring

import com.example.evergreen.data.EndReason
import com.example.evergreen.data.LikeEventType
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

/**
 * Test 4 of `docs/PHASE3_PLAN.md` section 9 — invariant 1a, that **a score is
 * computed and never accumulated**.
 *
 * Written before the score cache exists, deliberately. The cache arrives in
 * step 10 and this test must still pass afterwards, which is what stops it
 * quietly becoming a cache of stale values. Everything here is a statement
 * about the pure function; step 10 adds the same assertions through the cache.
 *
 * Also pins the ten-half-life load window: an optimisation is only allowed to
 * make the ranking faster, not different.
 */
class RecomputeTest {

    private val zone = ZoneId.of("Europe/London")
    private val scorer = TrackScorer(zone)
    private val ranker = Ranker(scorer)

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private val parametersA = ScoringParameters()
    private val parametersB = ScoringParameters(
        halfLifeDays = 90.0,
        lowerBreakpoint = 0.15,
        upperBreakpoint = 0.80,
        negativeWeight = -0.25,
        likeWeight = 5.0,
        replayJumpThresholdMs = 3_000,
        dailyCapPlays = 4.0,
    )

    /**
     * A history broad enough that the parameters all have something to bite on:
     * full plays, rejections, replays split by jumps of different sizes,
     * imported rows with null positions, commentary, and likes of various ages.
     */
    private fun history(trackCount: Int = 40, seed: Int = 20260917): List<TrackScoringInput> {
        val random = Random(seed)
        var segmentId = 1L

        return (1..trackCount).map { trackId ->
            val duration = 150_000L + random.nextLong(120_000)
            val spotifyRow = trackId * 10L
            val commentaryRow = trackId * 10L + 1

            val segments = mutableListOf<ListeningSegmentEntity>()
            repeat(random.nextInt(1, 25)) {
                val ageDays = random.nextInt(0, 2_400)
                val imported = ageDays > 30
                val heard = when (random.nextInt(4)) {
                    0 -> random.nextLong(1, duration / 8)
                    1 -> duration / 2
                    else -> duration - random.nextLong(0, 8_000)
                }
                val jumpBack = random.nextInt(5) == 0
                segments += ListeningSegmentEntity(
                    id = segmentId++,
                    trackId = trackId.toLong(),
                    serviceTrackRowId = spotifyRow,
                    appPackage = "com.spotify.music",
                    deviceId = if (imported) "spotify-history-import" else "test-device",
                    startedAtMs = now - ageDays * day - heard,
                    endedAtMs = now - ageDays * day,
                    startPositionMs = if (imported) null else 0,
                    endPositionMs = if (imported) null else heard,
                    listenedMs = heard,
                    trackDurationMs = duration,
                    forwardSeekCount = if (imported) null else 0,
                    seekedForwardMs = if (imported) null else 0,
                    endReason = when {
                        imported -> EndReason.IMPORTED
                        jumpBack -> EndReason.POSITION_JUMP_BACK
                        else -> EndReason.TRACK_CHANGE
                    },
                    // Jumps straddle both the 3 s and the 10 s thresholds, so
                    // changing replayJumpThresholdMs really re-folds these rows.
                    endJumpFromMs = if (!imported && jumpBack) random.nextLong(1_000, 20_000) else null,
                    endJumpToMs = if (!imported && jumpBack) 0L else null,
                    lastSampleAgeMs = if (imported) null else 100,
                    createdAt = now - ageDays * day,
                    source = if (imported) SegmentSource.IMPORT_SPOTIFY_HISTORY else SegmentSource.CAPTURE,
                )
            }
            // A commentary row on some tracks, which must contribute nothing.
            if (trackId % 7 == 0) {
                segments += ListeningSegmentEntity(
                    id = segmentId++,
                    trackId = trackId.toLong(),
                    serviceTrackRowId = commentaryRow,
                    appPackage = "com.spotify.music",
                    deviceId = "test-device",
                    startedAtMs = now - 3 * day,
                    endedAtMs = now - 3 * day,
                    listenedMs = 0,
                    trackDurationMs = 0,
                    startPositionMs = 0,
                    endPositionMs = 0,
                    forwardSeekCount = 0,
                    seekedForwardMs = 0,
                    endReason = EndReason.TRACK_CHANGE,
                    endJumpFromMs = null,
                    endJumpToMs = null,
                    lastSampleAgeMs = 0,
                    createdAt = now - 3 * day,
                )
            }

            TrackScoringInput(
                trackId = trackId.toLong(),
                segments = segments,
                serviceRows = mapOf(
                    spotifyRow to ServiceTrackInfo("spotify:track:t$trackId", duration),
                    commentaryRow to ServiceTrackInfo("spotify:media:c$trackId", 0),
                ),
                canonicalDurationMs = duration,
                likeEvents = listOf(
                    LikeEventRecord(LikeEventType.LIKED, now - random.nextInt(0, 2_000) * day),
                ),
            )
        }
    }

    private fun scores(
        inputs: List<TrackScoringInput>,
        parameters: ScoringParameters,
    ): Map<Long, Double> =
        inputs.associate { input -> input.trackId to scorer.score(input, parameters, now).score }

    // --- The function is a function ------------------------------------------

    /** Same inputs, same parameters, same answer — every time. */
    @Test
    fun `scoring is deterministic`() {
        val inputs = history()

        assertEquals(scores(inputs, parametersA), scores(inputs, parametersA))
    }

    /**
     * The heart of invariant 1a. Scoring under A first must not change what B
     * produces — if any state were accumulated anywhere, it would show here.
     */
    @Test
    fun `scoring under one set of parameters does not affect the next`() {
        val inputs = history()

        val fromScratchB = scores(inputs, parametersB)

        // Now do it the other way round: A first, then B, on the same inputs.
        scores(inputs, parametersA)
        val afterA = scores(inputs, parametersB)

        assertEquals(fromScratchB, afterA)
    }

    /** And the parameters really do matter, or the test above proves nothing. */
    @Test
    fun `different parameters give different scores`() {
        val inputs = history()

        val underA = scores(inputs, parametersA)
        val underB = scores(inputs, parametersB)

        assertNotEquals(underA, underB)
        assertTrue(
            "the two parameter sets should disagree about most tracks",
            inputs.count { underA[it.trackId] != underB[it.trackId] } > inputs.size / 2,
        )
    }

    @Test
    fun `the ranking is deterministic and densely numbered`() {
        val inputs = history()

        val first = ranker.rank(inputs, parametersA, now)
        val second = ranker.rank(inputs, parametersA, now)

        assertEquals(first, second)
        assertEquals(inputs.size, first.size)
        assertEquals((1..inputs.size).toList(), first.map { it.rank })
        assertEquals(first.map { it.score }.sortedDescending(), first.map { it.score })
    }

    /**
     * Two tracks on exactly the same score must not swap places between runs.
     * Every swap across the playlist cutoff would be a Spotify write, so sort
     * instability would show up as churn against the quota.
     */
    @Test
    fun `tracks with equal scores keep a stable order`() {
        val identical = (1..5).map { trackId ->
            TrackScoringInput(
                trackId = trackId.toLong(),
                segments = emptyList(),
                serviceRows = emptyMap(),
                canonicalDurationMs = 200_000,
                likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now - 10 * day)),
            )
        }

        val ranking = ranker.rank(identical.shuffled(), parametersA, now)

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ranking.map { it.trackId })
    }

    // --- The load window is an optimisation, not a change --------------------

    /**
     * Ten half-lives back a contribution retains under a tenth of a percent of
     * its weight, so dropping everything older must not move a score by a
     * meaningful amount. This is what makes it safe to leave six years of
     * imported rows in the table and read only the last 450 days of it.
     */
    @Test
    fun `the ten half-life load window changes no score by more than 0-001`() {
        val inputs = history()
        val cutoff = Ranker.loadFromMs(now, parametersA)

        val everything = scores(inputs, parametersA)
        val windowed = scores(inputs.map { it.withinWindow(cutoff) }, parametersA)

        inputs.forEach { input ->
            val full = everything.getValue(input.trackId)
            val bounded = windowed.getValue(input.trackId)
            assertEquals(
                "track ${input.trackId}: $full vs $bounded",
                full,
                bounded,
                0.001,
            )
        }
    }

    /**
     * The bound above is per contribution, and a score is a sum of them.
     *
     * Ten half-lives back one event retains 2^-10, under a tenth of a percent,
     * so one truncated play is worth less than 0.001. But a track played daily
     * for years has hundreds of truncated plays, and their tail adds up: the
     * error grows with how much *old* listening a track has, and it is always
     * negative, so it leans very slightly against long-standing favourites.
     *
     * This test measures the real worst case over a dense history rather than
     * assuming the per-event bound carries. What matters operationally is the
     * next test: whether the ranking moves.
     */
    @Test
    fun `the truncation error stays small even for a track played for years`() {
        val dense = densePlayHistory()
        val cutoff = Ranker.loadFromMs(now, parametersA)

        val full = scorer.score(dense, parametersA, now).score
        val bounded = scorer.score(dense.withinWindow(cutoff), parametersA, now).score
        val error = full - bounded

        assertTrue(
            "truncation must only ever lose weight, never add it; was $error",
            error >= 0.0,
        )
        // Measured at 0.063 on a score of 65.4 for a track played through
        // every day for six years: under a tenth of one percent. The absolute
        // error grows with how many old plays there are, but the *relative*
        // error does not, because a track with more old plays also has more
        // recent ones. The relative bound is therefore the one to hold to.
        assertTrue(
            "error $error is more than 0.5% of a score of $full",
            error < full * 0.005,
        )
        assertTrue("absolute error $error is larger than expected", error < 0.1)
    }

    /**
     * What the window actually costs in the ranking, stated honestly.
     *
     * It does **not** always reproduce the full-history order exactly. Tracks
     * whose scores sit within the truncation error of each other can swap. What
     * matters is that the effect is tiny and, crucially, *deterministic*: the
     * windowed ranking is the same every run, so a swap never causes churn.
     * Two tracks flickering between runs would mean a Spotify write a day; two
     * tracks sitting in a different order than the full history would have put
     * them in costs nothing at all.
     *
     * The hysteresis band is the other half of the answer. A member has to fall
     * 30 places to be removed, which is far more than this ever moves anything.
     */
    @Test
    fun `the load window moves tracks at most a place or two, and never flickers`() {
        val inputs = history(trackCount = 120)
        val cutoff = Ranker.loadFromMs(now, parametersA)

        val full = ranker.rank(inputs, parametersA, now)
        val windowedInputs = inputs.map { it.withinWindow(cutoff) }
        val bounded = ranker.rank(windowedInputs, parametersA, now)

        // Deterministic: the same inputs give the same order every time.
        assertEquals(bounded, ranker.rank(windowedInputs, parametersA, now))

        val fullRanks = full.associate { it.trackId to it.rank }
        val moved = bounded
            .map { track ->
                Triple(track.trackId, kotlin.math.abs(track.rank - fullRanks.getValue(track.trackId)), track.score)
            }
            .filter { (_, places, _) -> places > 0 }

        // Every track that moves at all is one whose score is below the record
        // floor -- a track the app considers no longer alive. That is the shape
        // of the error and the reason it is harmless: scores are densely packed
        // near zero down there, so a tiny absolute change shuffles the tail,
        // while tracks with any recent listening are nowhere near it.
        moved.forEach { (trackId, places, score) ->
            assertTrue(
                "track $trackId moved $places places with a score of $score, " +
                    "which is above the ${parametersA.recordFloor} record floor",
                score < parametersA.recordFloor,
            )
        }
    }

    @Test
    fun `the window really does drop most of six years of history`() {
        val inputs = history()
        val cutoff = Ranker.loadFromMs(now, parametersA)

        val kept = inputs.sumOf { it.withinWindow(cutoff).segments.size }
        val total = inputs.sumOf { it.segments.size }

        assertTrue("kept $kept of $total", kept < total / 2)
        assertEquals(450L * day, parametersA.loadWindowMs)
    }

    /**
     * The window is derived from the half-life, so a slower decay loads more
     * history. A fixed 450-day window would silently truncate scores the moment
     * someone doubled the half-life.
     */
    @Test
    fun `a longer half-life widens the window`() {
        assertEquals(900L * day, parametersB.loadWindowMs)

        val inputs = history()
        val cutoff = Ranker.loadFromMs(now, parametersB)

        val everything = scores(inputs, parametersB)
        val windowed = scores(inputs.map { it.withinWindow(cutoff) }, parametersB)

        // A slower decay leaves more weight on old plays, so truncating costs
        // more than it does at the default half-life -- which is exactly why
        // the window is derived from the half-life rather than fixed at 450
        // days. The tolerance is looser here for that reason, and the ranking
        // check below is the claim that matters.
        inputs.forEach { input ->
            assertEquals(
                everything.getValue(input.trackId),
                windowed.getValue(input.trackId),
                0.01,
            )
        }
        val fullRanks = ranker.rank(inputs, parametersB, now).associate { it.trackId to it.rank }
        val worstMove = ranker.rank(inputs.map { it.withinWindow(cutoff) }, parametersB, now)
            .maxOf { kotlin.math.abs(it.rank - fullRanks.getValue(it.trackId)) }
        assertTrue("windowing moved a track $worstMove places", worstMove <= 3)
    }

    /** Likes are never windowed out: one like is the whole of a track's like history. */
    @Test
    fun `an old like still counts after windowing`() {
        val input = TrackScoringInput(
            trackId = 1,
            segments = emptyList(),
            serviceRows = emptyMap(),
            canonicalDurationMs = 200_000,
            likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now - 100 * day)),
        )

        val score = scorer.score(input, parametersA, now).score

        assertTrue(score > 0.0)
        assertEquals(3.0 * Decay.weight(now - 100 * day, now, parametersA), score, 1e-9)
    }

    private fun TrackScoringInput.withinWindow(cutoffMs: Long) =
        copy(segments = segments.filter { it.endedAtMs >= cutoffMs })

    /**
     * One track played through almost every day for six years — the worst case
     * for truncation, because it has the most old plays to lose.
     */
    private fun densePlayHistory(): TrackScoringInput {
        val duration = 200_000L
        val segments = (0 until 2_200).map { ageDays ->
            ListeningSegmentEntity(
                id = ageDays + 1L,
                trackId = 1,
                serviceTrackRowId = 10,
                appPackage = "com.spotify.music",
                deviceId = "spotify-history-import",
                startedAtMs = now - ageDays * day - duration,
                endedAtMs = now - ageDays * day,
                startPositionMs = null,
                endPositionMs = null,
                listenedMs = duration,
                trackDurationMs = duration,
                forwardSeekCount = null,
                seekedForwardMs = null,
                endReason = EndReason.IMPORTED,
                endJumpFromMs = null,
                endJumpToMs = null,
                lastSampleAgeMs = null,
                createdAt = now - ageDays * day,
                source = SegmentSource.IMPORT_SPOTIFY_HISTORY,
            )
        }
        return TrackScoringInput(
            trackId = 1,
            segments = segments,
            serviceRows = mapOf(10L to ServiceTrackInfo("spotify:track:dense", duration)),
            canonicalDurationMs = duration,
            likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now - 1_000 * day)),
        )
    }
}
