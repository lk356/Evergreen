package com.example.evergreen.scoring

import com.example.evergreen.data.EndReason
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Change B of scoring freshness: several very short listens in a row are
 * browsing, and a skip inside one is discounted rather than counted in full.
 * The rule as approved by the user is in `docs/PLAN.md`, Status.
 */
class BrowsingRunsTest {

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val hour = 60 * 60 * 1000L
    private val duration = 200_000L
    private val scorer = TrackScorer(ZoneId.of("Europe/London"))
    private val runsOn = ScoringParameters(rapidRunMinSkips = 3, rapidRunMaxListenMs = 5_000, rapidRunSkipWeight = 0.25)

    private fun listen(at: Long, ms: Long, timeline: String = WALKMAN, commentary: Boolean = false) =
        TimelineListen(timeline, at, at + ms, ms, commentary)

    /** Back-to-back listens of the given lengths, a second apart, from [start]. */
    private fun sequence(start: Long, vararg lengths: Long, timeline: String = WALKMAN): List<TimelineListen> {
        var at = start
        return lengths.map { ms -> listen(at, ms, timeline).also { at += ms + 1_000 } }
    }

    private fun find(listens: List<TimelineListen>, parameters: ScoringParameters = runsOn) =
        BrowsingRuns.find(listens, parameters.rapidRunMinSkips, parameters.rapidRunMaxListenMs)

    // --- Finding runs --------------------------------------------------------

    @Test
    fun `three flicks in a row are a run and two are not`() {
        assertEquals(1, find(sequence(now, 2_000, 2_000, 2_000)).size)
        assertEquals(0, find(sequence(now, 2_000, 2_000)).size)
    }

    @Test
    fun `off finds nothing whatever the timeline holds`() {
        assertEquals(emptyList<BrowsingWindow>(), find(sequence(now, 1_000, 1_000, 1_000, 1_000), ScoringParameters()))
    }

    @Test
    fun `a listen longer than the limit ends a run`() {
        // Flick, flick, a real listen, flick, flick: two runs of two, so none.
        val windows = find(sequence(now, 2_000, 2_000, 60_000, 2_000, 2_000))
        assertEquals(0, windows.size)
    }

    @Test
    fun `a gap of more than thirty seconds ends a run`() {
        val first = sequence(now, 2_000, 2_000)
        val afterPause = sequence(first.last().endedAtMs + 31_000, 2_000)
        assertEquals("31 s apart: two pieces of two and one", 0, find(first + afterPause).size)

        val afterGlance = sequence(first.last().endedAtMs + 29_000, 2_000)
        assertEquals("29 s apart: one run of three", 1, find(first + afterGlance).size)
    }

    @Test
    fun `DJ commentary neither breaks a run nor counts toward one`() {
        val flicks = sequence(now, 2_000, 2_000)
        // Twenty seconds of the DJ talking, then one more flick. The gap from
        // the last flick to the next one is over thirty seconds, but the DJ
        // filled it, so it is not a pause.
        val talk = listen(flicks.last().endedAtMs + 1_000, 20_000, commentary = true)
        val more = listen(talk.endedAtMs + 15_000, 2_000)
        assertEquals(1, find(flicks + talk + more).size)
        // And commentary is not itself a flick: two flicks around it are two.
        assertEquals(0, find(listOf(flicks.first(), talk, listen(talk.endedAtMs + 1_000, 2_000))).size)
    }

    @Test
    fun `each device is its own timeline and the import is another`() {
        // Interleaved flicks from two timelines, each with only two of its
        // own in a row.
        val walkman = sequence(now, 2_000, 2_000)
        val import = sequence(now + 500, 2_000, 2_000, timeline = IMPORT)
        assertEquals(0, find(walkman + import).size)
        // Three on the import alone are a run on the import.
        val windows = find(sequence(now, 2_000, 2_000, 2_000, timeline = IMPORT))
        assertEquals(IMPORT, windows.single().timeline)
    }

    @Test
    fun `the same timeline in any order finds the same windows`() {
        val timeline = sequence(now, 2_000, 2_000, 2_000, 90_000, 1_000, 1_000, 1_000, 1_000)
        assertEquals(find(timeline), find(timeline.shuffled(kotlin.random.Random(3))))
        assertEquals(2, find(timeline).size)
    }

    // --- Scoring inside a run ------------------------------------------------

    private var nextId = 1L

    private fun segment(trackId: Long, at: Long, ms: Long, device: String = WALKMAN) = ListeningSegmentEntity(
        id = nextId++,
        trackId = trackId,
        serviceTrackRowId = trackId * 10,
        appPackage = "com.spotify.music",
        deviceId = device,
        startedAtMs = at,
        endedAtMs = at + ms,
        startPositionMs = 0L,
        endPositionMs = ms,
        listenedMs = ms,
        trackDurationMs = duration,
        forwardSeekCount = 0,
        seekedForwardMs = 0L,
        endReason = EndReason.TRACK_CHANGE,
        endJumpFromMs = null,
        endJumpToMs = null,
        lastSampleAgeMs = 100L,
        createdAt = at + ms,
        source = SegmentSource.CAPTURE,
    )

    private fun input(trackId: Long, segments: List<ListeningSegmentEntity>) = TrackScoringInput(
        trackId = trackId,
        segments = segments,
        serviceRows = mapOf(trackId * 10 to ServiceTrackInfo("spotify:track:t$trackId", duration)),
        canonicalDurationMs = duration,
        likeEvents = emptyList(),
    )

    /** The timeline of [segments], every track, as the service would load it. */
    private fun windowsFor(segments: List<ListeningSegmentEntity>, parameters: ScoringParameters = runsOn) =
        BrowsingRuns.find(
            segments.map { TimelineListen(it.deviceId, it.startedAtMs, it.endedAtMs, it.listenedMs, false) },
            parameters.rapidRunMinSkips,
            parameters.rapidRunMaxListenMs,
        )

    /**
     * The prompt's ten-song flick-back: ten songs played in full, then
     * flicked back through to find the first one, which then plays.
     *
     * Every flick is a song heard within the last two hours, so every one is
     * a revisit and costs nothing, whatever the weight. Today each would be a
     * full rejection, −0.5, on a song just played to the end.
     */
    @Test
    fun `a ten song flick-back costs the songs passed nothing`() {
        val all = mutableListOf<ListeningSegmentEntity>()
        var at = now - 3 * hour
        (1L..10L).forEach { track -> all += segment(track, at, duration); at += duration + 1_000 }
        (10L downTo 2L).forEach { track -> all += segment(track, at, 2_000); at += 3_000 }
        all += segment(1L, at, duration)

        val windows = windowsFor(all)
        assertEquals(1, windows.size)
        (2L..10L).forEach { track ->
            val breakdown = scorer.score(input(track, all.filter { it.trackId == track }), runsOn, now, browsingWindows = windows)
            val flick = breakdown.listens.last()
            assertEquals(ContributionNote.BROWSING_REVISIT, flick.note)
            assertEquals(0.0, flick.playEquivalents, 0.0)
            assertEquals("the curve still says rejection", -0.5, flick.rawPlayEquivalents, 0.0)
        }
        // And with runs off, the same rows are nine rejections.
        val off = scorer.score(input(5L, all.filter { it.trackId == 5L }), ScoringParameters(), now)
        assertEquals(ContributionNote.SCORED, off.listens.last().note)
        assertEquals(-0.5, off.listens.last().playEquivalents, 0.0)
    }

    @Test
    fun `a forward flick through new songs takes the run weight`() {
        val all = (1L..5L).map { track -> segment(track, now - hour + track * 3_000, 2_000) }
        val windows = windowsFor(all)
        val breakdown = scorer.score(input(3L, all.filter { it.trackId == 3L }), runsOn, now, browsingWindows = windows)
        val flick = breakdown.listens.single()
        assertEquals(ContributionNote.BROWSING, flick.note)
        assertEquals(-0.5 * 0.25, flick.playEquivalents, 1e-12)
    }

    @Test
    fun `a weight of one is exactly a full rejection`() {
        val all = (1L..5L).map { track -> segment(track, now - hour + track * 3_000, 2_000) }
        val full = runsOn.copy(rapidRunSkipWeight = 1.0)
        val windows = windowsFor(all, full)
        val on = scorer.score(input(3L, all.filter { it.trackId == 3L }), full, now, browsingWindows = windows)
        val off = scorer.score(input(3L, all.filter { it.trackId == 3L }), ScoringParameters(), now)
        assertEquals(off.score, on.score, 0.0)
    }

    /**
     * The prompt's genre block skipped inside a DJ session. Browsing is
     * applied first and instead of the DJ rule, so the breakdown names one
     * reason; with the DJ weight still at 0 the block would have cost nothing
     * anyway, and that stays true of the songs outside the run.
     */
    @Test
    fun `a genre block skipped in a DJ session is browsing, not a DJ skip`() {
        val start = now - hour
        val dj = listOf(DjWindow(start - 60_000, start + 20 * 60_000))
        val block = (1L..4L).map { track -> segment(track, start + track * 3_000, 2_000) }
        val lone = segment(9L, start + 5 * 60_000, 20_000)
        val windows = windowsFor(block + lone)

        val inBlock = scorer.score(input(2L, block.filter { it.trackId == 2L }), runsOn, now, dj, windows)
        assertEquals(ContributionNote.BROWSING, inBlock.listens.single().note)

        // A lone skip after 20 seconds, in the same DJ session: not a run, so
        // the DJ rule decides, as it always has.
        val alone = scorer.score(input(9L, listOf(lone)), runsOn, now, dj, windows)
        assertEquals(ContributionNote.DJ_SUPPRESSED, alone.listens.single().note)
    }

    @Test
    fun `a full play inside a run's span is untouched`() {
        // Runs discount negatives only; a run never makes a play worth less.
        val flicks = (1L..3L).map { track -> segment(track, now - hour + track * 3_000, 2_000) }
        val windows = windowsFor(flicks)
        val played = segment(1L, flicks.last().endedAtMs + 1_000, duration)
        val breakdown = scorer.score(input(1L, listOf(flicks.first(), played)), runsOn, now, browsingWindows = windows)
        assertEquals(1.0, breakdown.listens.last().playEquivalents, 0.0)
        assertEquals(ContributionNote.SCORED, breakdown.listens.last().note)
    }

    @Test
    fun `a revisit needs the earlier listen on the same device`() {
        // Track 2 was played in full on the Pixel an hour ago; flicking past
        // it on the Walkman is a forward flick on the Walkman's timeline.
        val earlier = segment(2L, now - 2 * hour, duration, device = PIXEL)
        val flicks = (1L..3L).map { track -> segment(track, now - hour + track * 3_000, 2_000) }
        val windows = windowsFor(flicks + earlier)
        val breakdown = scorer.score(input(2L, listOf(earlier) + flicks.filter { it.trackId == 2L }), runsOn, now, browsingWindows = windows)
        assertEquals(ContributionNote.BROWSING, breakdown.listens.last().note)
    }

    // --- The settings --------------------------------------------------------

    @Test
    fun `off writes no keys and on writes all three`() {
        assertFalse(ScoringParameters().toJson().contains("rapidRun"))
        val json = runsOn.toJson()
        assertTrue(json, json.endsWith(""","rapidRunMinSkips":3,"rapidRunMaxListenMs":5000,"rapidRunSkipWeight":0.25}"""))
        assertEquals(runsOn, ScoringParameters.fromJson(json))
    }

    @Test
    fun `off has one form, whatever the ignored values were`() {
        val stray = ScoringParameters(rapidRunMinSkips = 0, rapidRunMaxListenMs = 900, rapidRunSkipWeight = 0.7)
        assertEquals(ScoringParameters(), stray.normalised())
        assertEquals(ScoringParameters().hash(), stray.hash())
        assertEquals(
            ScoringParameters(),
            ScoringParameters.fromJson(ScoringParameters().toJson().dropLast(1) + ""","rapidRunSkipWeight":0.7}"""),
        )
    }

    @Test
    fun `nonsense values are refused`() {
        assertEquals(1, runsOn.copy(rapidRunMinSkips = 1).problems().size)
        assertEquals(1, runsOn.copy(rapidRunSkipWeight = 1.5).problems().size)
        assertEquals(1, runsOn.copy(rapidRunMaxListenMs = 0).problems().size)
        assertEquals(emptyList<String>(), runsOn.problems())
    }

    private companion object {
        const val WALKMAN = "walkman"
        const val PIXEL = "pixel"
        const val IMPORT = "spotify-history-import"
    }
}
