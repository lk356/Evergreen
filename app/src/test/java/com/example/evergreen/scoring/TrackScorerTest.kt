package com.example.evergreen.scoring

import com.example.evergreen.data.EndReason
import com.example.evergreen.data.LikeEventType
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Test 3 of `docs/PHASE3_PLAN.md` section 9, and the invariants the Phase 3
 * brief asks to be tested rather than merely honoured.
 */
class TrackScorerTest {

    private val zone = ZoneId.of("Europe/London")
    private val scorer = TrackScorer(zone)
    private val defaults = ScoringParameters()

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val duration = 200_000L

    private var nextId = 1L

    private fun segment(
        endedAtMs: Long,
        listenedMs: Long,
        durationMs: Long = duration,
        serviceRowId: Long? = SPOTIFY_ROW,
        endReason: EndReason = EndReason.TRACK_CHANGE,
        jumpFromMs: Long? = null,
        jumpToMs: Long? = null,
        source: SegmentSource = SegmentSource.CAPTURE,
    ) = ListeningSegmentEntity(
        id = nextId++,
        trackId = TRACK_ID,
        serviceTrackRowId = serviceRowId,
        appPackage = "com.spotify.music",
        deviceId = "test-device",
        startedAtMs = endedAtMs - listenedMs,
        endedAtMs = endedAtMs,
        startPositionMs = if (source == SegmentSource.CAPTURE) 0L else null,
        endPositionMs = if (source == SegmentSource.CAPTURE) listenedMs else null,
        listenedMs = listenedMs,
        trackDurationMs = durationMs,
        forwardSeekCount = if (source == SegmentSource.CAPTURE) 0 else null,
        seekedForwardMs = if (source == SegmentSource.CAPTURE) 0L else null,
        endReason = endReason,
        endJumpFromMs = jumpFromMs,
        endJumpToMs = jumpToMs,
        lastSampleAgeMs = if (source == SegmentSource.CAPTURE) 100L else null,
        createdAt = endedAtMs,
        source = source,
    )

    private fun input(
        segments: List<ListeningSegmentEntity> = emptyList(),
        likeEvents: List<LikeEventRecord> = emptyList(),
        serviceRows: Map<Long, ServiceTrackInfo> = mapOf(
            SPOTIFY_ROW to ServiceTrackInfo("spotify:track:aaa", duration),
            COMMENTARY_ROW to ServiceTrackInfo("spotify:media:bbb", 0),
        ),
        canonicalDurationMs: Long = duration,
    ) = TrackScoringInput(
        trackId = TRACK_ID,
        segments = segments,
        serviceRows = serviceRows,
        canonicalDurationMs = canonicalDurationMs,
        likeEvents = likeEvents,
    )

    private fun scoreOf(
        segments: List<ListeningSegmentEntity> = emptyList(),
        likeEvents: List<LikeEventRecord> = emptyList(),
        parameters: ScoringParameters = defaults,
        djWindows: List<DjWindow> = emptyList(),
        serviceRows: Map<Long, ServiceTrackInfo>? = null,
        canonicalDurationMs: Long = duration,
    ): ScoreBreakdown = scorer.score(
        input = serviceRows?.let {
            input(segments, likeEvents, it, canonicalDurationMs)
        } ?: input(segments, likeEvents, canonicalDurationMs = canonicalDurationMs),
        parameters = parameters,
        nowMs = now,
        djWindows = djWindows,
    )

    // --- The plan's named cases ----------------------------------------------

    @Test
    fun `one full play today is one`() {
        val result = scoreOf(listOf(segment(endedAtMs = now, listenedMs = duration)))

        assertEquals(1.0, result.score, TOLERANCE)
    }

    @Test
    fun `a like today is three`() {
        val result = scoreOf(likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now)))

        assertEquals(3.0, result.score, TOLERANCE)
    }

    @Test
    fun `a like from two years ago is almost nothing`() {
        val result = scoreOf(
            likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now - 730 * day)),
        )

        assertTrue("was ${result.score}", result.score < 0.001)
    }

    /**
     * Repeat-one overnight. Forty full plays in one night are worth two, not
     * forty — the cap from decisions 2e, with no loop detection needed.
     */
    @Test
    fun `forty loops in one night are worth two plays`() {
        val segments = (0 until 40).map { index ->
            segment(endedAtMs = now - index * 4 * 60_000L, listenedMs = duration)
        }

        val result = scoreOf(segments)

        // Not exactly 2.0: the forty plays span two and a half hours, and each
        // decays from its own moment. That is the decay working, not the cap
        // leaking — what matters is that it is two rather than forty.
        assertEquals(2.0, result.score, DECAY_WITHIN_A_DAY)
        assertTrue("forty plays must not score forty", result.score < 2.0)
    }

    /** The cap is per day, so the same track on two nights is worth four. */
    @Test
    fun `the cap resets each calendar day`() {
        val tonight = (0 until 40).map { segment(endedAtMs = now - it * 4 * 60_000L, listenedMs = duration) }
        val lastNight = (0 until 40).map {
            segment(endedAtMs = now - day - it * 4 * 60_000L, listenedMs = duration)
        }

        val result = scoreOf(tonight + lastNight)

        // Two plays today at full weight, two yesterday very slightly decayed.
        assertTrue("was ${result.score}", result.score > 3.9 && result.score < 4.0)
    }

    /**
     * The replay threshold is applied here rather than at capture, over rows
     * capture deliberately cut finely. Two rows joined by a 5 s jump are one
     * listen; by a 15 s jump, two.
     */
    @Test
    fun `a five second jump back is one listen and fifteen seconds is two`() {
        fun pair(jumpMs: Long) = listOf(
            segment(
                endedAtMs = now - 60_000,
                listenedMs = duration / 2,
                endReason = EndReason.POSITION_JUMP_BACK,
                jumpFromMs = jumpMs,
                jumpToMs = 0,
            ),
            segment(endedAtMs = now, listenedMs = duration / 2),
        )

        val joined = scoreOf(pair(5_000))
        val separate = scoreOf(pair(15_000))

        // Folded: 100% heard across one listen, a full play.
        assertEquals(1, joined.listens.size)
        assertEquals(1.0, joined.score, TOLERANCE)
        // Apart: two listens of 50% each, both neutral.
        assertEquals(2, separate.listens.size)
        assertEquals(0.0, separate.score, TOLERANCE)
    }

    @Test
    fun `commentary contributes nothing and says so`() {
        val result = scoreOf(
            listOf(
                segment(endedAtMs = now, listenedMs = 0, durationMs = 0, serviceRowId = COMMENTARY_ROW),
            ),
        )

        assertEquals(0.0, result.score, TOLERANCE)
        assertEquals(ContributionNote.COMMENTARY, result.contributions.single().note)
    }

    /**
     * Invariant 1b. An imported row carries only a timestamp, a listened
     * duration and a track — no position, no seeks, no jump fields — and must
     * score exactly as a captured row with the same listening.
     */
    @Test
    fun `an imported row scores the same as an identical captured row`() {
        val capturedRow = segment(endedAtMs = now, listenedMs = 190_000, source = SegmentSource.CAPTURE)
        val importedRow = segment(
            endedAtMs = now,
            listenedMs = 190_000,
            source = SegmentSource.IMPORT_SPOTIFY_HISTORY,
            endReason = EndReason.IMPORTED,
        )

        // The imported row really is the impoverished one: everything the
        // export cannot say is null. That is the point of the comparison.
        assertNull(importedRow.startPositionMs)
        assertNull(importedRow.endPositionMs)
        assertNull(importedRow.forwardSeekCount)
        assertNull(importedRow.seekedForwardMs)
        assertNull(importedRow.lastSampleAgeMs)

        val captured = scoreOf(listOf(capturedRow))
        val imported = scoreOf(listOf(importedRow))

        assertEquals(captured.score, imported.score, TOLERANCE)
        assertEquals(1.0, imported.score, TOLERANCE)
        assertEquals(
            captured.listens.single().fractionHeard!!,
            imported.listens.single().fractionHeard!!,
            TOLERANCE,
        )
    }

    /**
     * Invariant 1c. A 2019 play must contribute under 0.001 today, or six years
     * of imported history would drown out the present.
     */
    @Test
    fun `a 2019 play contributes almost nothing`() {
        val result = scoreOf(
            listOf(
                segment(
                    endedAtMs = now - 2_651 * day,
                    listenedMs = duration,
                    source = SegmentSource.IMPORT_SPOTIFY_HISTORY,
                    endReason = EndReason.IMPORTED,
                ),
            ),
        )

        assertTrue("was ${result.score}", result.score < 0.001)
        assertTrue(result.score > 0.0)
    }

    /**
     * The export has no durations, so an imported row stores 0 and cannot be
     * scored. When the like sync later fills the service row in, the same
     * stored row starts scoring — nothing is re-imported.
     */
    @Test
    fun `an imported row with no duration contributes nothing until one is supplied`() {
        val row = segment(
            endedAtMs = now,
            listenedMs = 190_000,
            durationMs = 0,
            source = SegmentSource.IMPORT_SPOTIFY_HISTORY,
            endReason = EndReason.IMPORTED,
        )

        val unknown = scoreOf(
            listOf(row),
            serviceRows = mapOf(SPOTIFY_ROW to ServiceTrackInfo("spotify:track:aaa", 0)),
            canonicalDurationMs = 0,
        )
        assertEquals(0.0, unknown.score, TOLERANCE)
        assertEquals(ContributionNote.UNKNOWN_DURATION, unknown.listens.single().note)
        assertNull(unknown.listens.single().fractionHeard)

        // The like sync learns the length. Same row, no re-import.
        val known = scoreOf(
            listOf(row),
            serviceRows = mapOf(SPOTIFY_ROW to ServiceTrackInfo("spotify:track:aaa", 200_000)),
        )
        assertEquals(1.0, known.score, TOLERANCE)
        assertEquals(ContributionNote.SCORED, known.listens.single().note)
        assertNotNull(known.listens.single().fractionHeard)
    }

    /** The canonical track's length is the last resort. */
    @Test
    fun `the canonical duration is used when nothing else knows one`() {
        val row = segment(endedAtMs = now, listenedMs = 190_000, durationMs = 0)

        val result = scoreOf(
            listOf(row),
            serviceRows = mapOf(SPOTIFY_ROW to ServiceTrackInfo("spotify:track:aaa", 0)),
            canonicalDurationMs = 200_000,
        )

        assertEquals(1.0, result.score, TOLERANCE)
    }

    // --- DJ suppression -------------------------------------------------------

    @Test
    fun `a ten percent listen during a DJ session contributes nothing`() {
        val listenEnd = now - 5 * 60_000
        val windows = DjSessions.infer(listOf(listenEnd - 10 * 60_000))

        val result = scoreOf(
            listOf(segment(endedAtMs = listenEnd, listenedMs = duration / 10)),
            djWindows = windows,
        )

        assertEquals(0.0, result.score, TOLERANCE)
        assertEquals(ContributionNote.DJ_SUPPRESSED, result.listens.single().note)
        assertEquals("the raw value is kept, so the view can explain it", -0.5,
            result.listens.single().rawPlayEquivalents, TOLERANCE)
    }

    @Test
    fun `the same listen outside a DJ session is a rejection`() {
        val result = scoreOf(listOf(segment(endedAtMs = now, listenedMs = duration / 10)))

        assertEquals(-0.5, result.score, TOLERANCE)
        assertEquals(ContributionNote.SCORED, result.listens.single().note)
    }

    /** Positives are untouched: letting a DJ pick play through is real information. */
    @Test
    fun `a full play during a DJ session still counts in full`() {
        val listenEnd = now - 5 * 60_000
        val windows = DjSessions.infer(listOf(listenEnd - 10 * 60_000))

        val result = scoreOf(
            listOf(segment(endedAtMs = listenEnd, listenedMs = duration)),
            djWindows = windows,
        )

        assertEquals(1.0, result.score, DECAY_WITHIN_A_DAY)
        assertEquals(ContributionNote.SCORED, result.listens.single().note)
    }

    // --- Likes ----------------------------------------------------------------

    @Test
    fun `an unlike event contributes nothing`() {
        val result = scoreOf(
            likeEvents = listOf(
                LikeEventRecord(LikeEventType.LIKED, now - 10 * day),
                LikeEventRecord(LikeEventType.UNLIKED, now),
            ),
        )

        assertEquals(
            "the like still counts; unliking is membership's business, not the score's",
            3.0 * Decay.weight(now - 10 * day, now, defaults),
            result.score,
            TOLERANCE,
        )
        val unlike = result.contributions.single { it.note == ContributionNote.UNLIKE_IGNORED }
        assertEquals(0.0, unlike.decayedValue, TOLERANCE)
        assertEquals(ContributionKind.LIKE, unlike.kind)
    }

    /** Decision 10.4: likes decay from when the user actually liked the track. */
    @Test
    fun `a like decays from its own timestamp, not from when the app first saw it`() {
        val old = scoreOf(likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now - 365 * day)))
        val fresh = scoreOf(likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now)))

        assertTrue(old.score < fresh.score / 100)
    }

    // --- Negatives are not capped --------------------------------------------

    /**
     * Skipping a track repeatedly in one sitting is about the clearest
     * rejection there is; it must not count for less the more emphatically it
     * happened.
     */
    @Test
    fun `repeated rejections in one day are not capped`() {
        val segments = (0 until 10).map { segment(endedAtMs = now - it * 60_000L, listenedMs = 1_000) }

        val result = scoreOf(segments)

        // Ten rejections at -0.5 each, none of them trimmed.
        assertEquals(-5.0, result.score, DECAY_WITHIN_A_DAY)
    }

    // --- The breakdown explains itself ---------------------------------------

    @Test
    fun `the breakdown sums exactly to the score`() {
        val result = scoreOf(
            segments = listOf(
                segment(endedAtMs = now, listenedMs = duration),
                segment(endedAtMs = now - 30 * day, listenedMs = duration / 10),
                segment(endedAtMs = now - 100 * day, listenedMs = duration),
            ),
            likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now - 60 * day)),
        )

        assertEquals(
            result.contributions.sumOf { it.decayedValue },
            result.score,
            TOLERANCE,
        )
        assertEquals(4, result.contributions.size)
    }

    @Test
    fun `contributions come back oldest first and name their rows`() {
        val result = scoreOf(
            segments = listOf(
                segment(endedAtMs = now, listenedMs = duration),
                segment(endedAtMs = now - 10 * day, listenedMs = duration),
            ),
            likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now - 5 * day)),
        )

        assertEquals(result.contributions.map { it.atMs }.sorted(), result.contributions.map { it.atMs })
        result.listens.forEach { listen ->
            assertTrue("a listen must name the rows behind it", listen.segmentIds.isNotEmpty())
        }
    }

    @Test
    fun `a track with no events at all scores zero`() {
        val result = scoreOf()

        assertEquals(0.0, result.score, TOLERANCE)
        assertTrue(result.contributions.isEmpty())
    }

    /**
     * A segment with no service identifier at all is not commentary —
     * commentary always carries a `spotify:media:` URI — so it scores normally
     * rather than being silently dropped.
     */
    @Test
    fun `a segment with no service id is scored, not treated as commentary`() {
        val result = scoreOf(
            listOf(segment(endedAtMs = now, listenedMs = duration, serviceRowId = null)),
        )

        assertEquals(1.0, result.score, TOLERANCE)
    }

    // --- Parameters really are settings --------------------------------------

    @Test
    fun `changing the half-life changes the score without touching the data`() {
        val segments = listOf(segment(endedAtMs = now - 45 * day, listenedMs = duration))

        val fast = scoreOf(segments, parameters = defaults)
        val slow = scoreOf(segments, parameters = defaults.copy(halfLifeDays = 90.0))

        assertEquals(0.5, fast.score, TOLERANCE)
        assertEquals(0.7071, slow.score, 0.001)
    }

    @Test
    fun `changing the like weight changes the score`() {
        val likes = listOf(LikeEventRecord(LikeEventType.LIKED, now))

        assertEquals(3.0, scoreOf(likeEvents = likes).score, TOLERANCE)
        assertEquals(
            5.0,
            scoreOf(likeEvents = likes, parameters = defaults.copy(likeWeight = 5.0)).score,
            TOLERANCE,
        )
    }

    @Test
    fun `changing the daily cap changes how much a night of looping is worth`() {
        val segments = (0 until 40).map { segment(endedAtMs = now - it * 4 * 60_000L, listenedMs = duration) }

        assertEquals(
            5.0,
            scoreOf(segments, parameters = defaults.copy(dailyCapPlays = 5.0)).score,
            DECAY_WITHIN_A_DAY,
        )
    }

    @Test
    fun `changing the replay threshold re-folds the same stored rows`() {
        val rows = listOf(
            segment(
                endedAtMs = now - 60_000,
                listenedMs = duration / 2,
                endReason = EndReason.POSITION_JUMP_BACK,
                jumpFromMs = 8_000,
                jumpToMs = 0,
            ),
            segment(endedAtMs = now, listenedMs = duration / 2),
        )

        // At 10s the 8s jump is jitter, so the rows are one listen.
        assertEquals(1, scoreOf(rows).listens.size)
        // At 3s it is a deliberate replay, so they are two.
        assertEquals(2, scoreOf(rows, parameters = defaults.copy(replayJumpThresholdMs = 3_000)).listens.size)
    }

    private companion object {
        const val TRACK_ID = 7L
        const val SPOTIFY_ROW = 11L
        const val COMMENTARY_ROW = 12L
        const val TOLERANCE = 1e-6

        /**
         * Slack for fixtures whose events are spread over hours rather than
         * happening at one instant.
         *
         * Each event decays from its own moment, so a night of listening never
         * sums to a round number. At a 45-day half-life a day costs about 1.5%,
         * so 0.01 comfortably covers a fixture spanning a few hours while still
         * failing if the cap or the curve were wrong.
         */
        const val DECAY_WITHIN_A_DAY = 0.01
    }
}
