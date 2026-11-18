package com.example.evergreen.scoring

import com.example.evergreen.data.EndReason
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneId

/**
 * Change C of scoring freshness: a skip during a DJ session counts for
 * `djSkipWeight` of a rejection, instead of nothing. Revises decision 2g at
 * the user's request; at 0 the decision stands exactly as it was.
 */
class DjSkipWeightTest {

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val hour = 60 * 60 * 1000L
    private val duration = 200_000L
    private val scorer = TrackScorer(ZoneId.of("Europe/London"))
    private val start = now - hour
    private val dj = listOf(DjWindow(start - 60_000, start + 20 * 60_000))
    private var nextId = 1L

    private fun segment(trackId: Long, at: Long, ms: Long) = ListeningSegmentEntity(
        id = nextId++, trackId = trackId, serviceTrackRowId = trackId * 10,
        appPackage = "com.spotify.music", deviceId = "walkman",
        startedAtMs = at, endedAtMs = at + ms, startPositionMs = 0L, endPositionMs = ms,
        listenedMs = ms, trackDurationMs = duration, forwardSeekCount = 0, seekedForwardMs = 0L,
        endReason = EndReason.TRACK_CHANGE, endJumpFromMs = null, endJumpToMs = null,
        lastSampleAgeMs = 100L, createdAt = at + ms, source = SegmentSource.CAPTURE,
    )

    private fun input(trackId: Long, segments: List<ListeningSegmentEntity>) = TrackScoringInput(
        trackId = trackId, segments = segments,
        serviceRows = mapOf(trackId * 10 to ServiceTrackInfo("spotify:track:t$trackId", duration)),
        canonicalDurationMs = duration, likeEvents = emptyList(),
    )

    private fun lone(parameters: ScoringParameters) =
        scorer.score(input(9L, listOf(segment(9L, start + 5 * 60_000, 20_000))), parameters, now, dj)
            .listens.single()

    /** The prompt's named case: a lone DJ skip after 20 seconds. */
    @Test
    fun `a lone DJ skip after twenty seconds counts for the weight`() {
        val half = lone(ScoringParameters(djSkipWeight = 0.5))
        assertEquals(ContributionNote.DJ_SKIP, half.note)
        assertEquals(-0.25, half.playEquivalents, 1e-12)
        assertEquals(-0.5, half.rawPlayEquivalents, 0.0)

        val full = lone(ScoringParameters(djSkipWeight = 1.0))
        assertEquals(-0.5, full.playEquivalents, 0.0)
    }

    @Test
    fun `at zero a DJ skip is forgiven exactly as before`() {
        val off = lone(ScoringParameters())
        assertEquals(ContributionNote.DJ_SUPPRESSED, off.note)
        assertEquals(0.0, off.playEquivalents, 0.0)
        assertFalse(ScoringParameters().toJson().contains("djSkipWeight"))
    }

    @Test
    fun `a DJ pick played through is untouched by the weight`() {
        val played = scorer.score(
            input(3L, listOf(segment(3L, start + 60_000, duration))),
            ScoringParameters(djSkipWeight = 1.0), now, dj,
        ).listens.single()
        assertEquals(ContributionNote.SCORED, played.note)
        assertEquals(1.0, played.playEquivalents, 0.0)
    }

    /**
     * Browsing first, and instead: a genre block flicked past in a DJ session
     * takes the run's discount only, never the run's and the DJ's multiplied
     * together.
     */
    @Test
    fun `a genre block in a DJ session takes the run weight and not both`() {
        val parameters = ScoringParameters(
            rapidRunMinSkips = 3, rapidRunMaxListenMs = 5_000, rapidRunSkipWeight = 0.25, djSkipWeight = 0.5,
        )
        val block = (1L..4L).map { track -> segment(track, start + track * 3_000, 2_000) }
        val windows = BrowsingRuns.find(
            block.map { TimelineListen(it.deviceId, it.startedAtMs, it.endedAtMs, it.listenedMs, false) },
            parameters.rapidRunMinSkips, parameters.rapidRunMaxListenMs,
        )
        val flick = scorer.score(input(2L, block.filter { it.trackId == 2L }), parameters, now, dj, windows)
            .listens.single()
        assertEquals(ContributionNote.BROWSING, flick.note)
        assertEquals(-0.5 * 0.25, flick.playEquivalents, 1e-12)
    }

    @Test
    fun `the weight round-trips and is range-checked`() {
        val on = ScoringParameters(djSkipWeight = 0.5)
        assertEquals(on, ScoringParameters.fromJson(on.toJson()))
        assertEquals(1, ScoringParameters(djSkipWeight = -0.1).problems().size)
        assertEquals(1, ScoringParameters(djSkipWeight = 1.1).problems().size)
    }
}
