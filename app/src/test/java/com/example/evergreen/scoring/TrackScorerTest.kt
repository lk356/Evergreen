package com.example.evergreen.scoring

import com.example.evergreen.data.EndReason
import com.example.evergreen.data.LikeEventType
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.PlaybackType
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
        playbackType: PlaybackType? = null,
        utcOffsetMinutes: Int? = null,
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
        playbackType = playbackType,
        utcOffsetMinutes = utcOffsetMinutes,
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

    // --- The daily cap and the timezone, Phase 5c step 3 --------------------
    //
    // The cap is 2.0 play-equivalents a day and a full play is worth 1.0, so
    // three full plays on one local day produce exactly one DAILY_CAP note and
    // two spread over two days produce none. Counting those notes is therefore
    // a direct read of which day each listen was filed under.

    /** 2026-09-10T00:00:00+01:00 — midnight in London, 23:00Z on the 9th. */
    private val midnightLondon = 1_788_994_800_000L

    @Test
    fun `a row with no offset falls back to the reading device's zone`() {
        // Every row written before schema v8 is null here, and the scorer is
        // constructed with Europe/London. Null must therefore behave exactly
        // like an explicit London offset -- which is what makes the migration
        // incapable of moving the Walkman's ranking.
        val instants = listOf(
            midnightLondon - 60_000,
            midnightLondon + 60_000,
            midnightLondon + 120_000,
        )

        val unstamped = scoreOf(instants.map { segment(it, duration, utcOffsetMinutes = null) })
        val stamped = scoreOf(instants.map { segment(it, duration, utcOffsetMinutes = 60) })

        assertEquals(stamped.score, unstamped.score, TOLERANCE)
        assertEquals(stamped.listens.map { it.note }, unstamped.listens.map { it.note })
    }

    @Test
    fun `two devices in different zones cap on their own local days`() {
        // The hazard schema v8 exists for. The same three plays at the same
        // three instants, recorded either side of midnight in London. London
        // files one on the 9th and two on the 10th, and nothing is capped. Five
        // hours west it is still early evening on the 9th, all three land
        // together, and the third is capped away.
        val instants = listOf(
            midnightLondon - 60_000,
            midnightLondon + 60_000,
            midnightLondon + 120_000,
        )

        val london = scoreOf(instants.map { segment(it, duration, utcOffsetMinutes = 60) })
        val newYork = scoreOf(instants.map { segment(it, duration, utcOffsetMinutes = -240) })

        assertEquals(
            "London: one play on the 9th, two on the 10th, nothing capped",
            0,
            london.listens.count { it.note == ContributionNote.DAILY_CAP },
        )
        assertEquals(
            "New York: all three on the 9th, so the third is capped",
            1,
            newYork.listens.count { it.note == ContributionNote.DAILY_CAP },
        )
        // Play-equivalents rather than the score, so the assertion is exact:
        // both sides decay from the same instants, and decay is not what is
        // being tested here.
        assertEquals(3.0, london.listens.sumOf { it.playEquivalents }, TOLERANCE)
        assertEquals(2.0, newYork.listens.sumOf { it.playEquivalents }, TOLERANCE)
        assertTrue("and the zone really does change the score", london.score > newYork.score)
    }

    @Test
    fun `a row's own offset beats the reading device's zone`() {
        // The property that makes one ranking out of two devices: which day a
        // listen falls in is a fact about the listening, not about whoever is
        // doing the arithmetic. Same rows, two scorers half a world apart.
        val rows = listOf(
            segment(midnightLondon - 60_000, duration, utcOffsetMinutes = 60),
            segment(midnightLondon + 60_000, duration, utcOffsetMinutes = 60),
            segment(midnightLondon + 120_000, duration, utcOffsetMinutes = 60),
        )

        val readInLondon = TrackScorer(ZoneId.of("Europe/London")).score(input(rows), defaults, now)
        val readInTokyo = TrackScorer(ZoneId.of("Asia/Tokyo")).score(input(rows), defaults, now)

        assertEquals(readInLondon.score, readInTokyo.score, TOLERANCE)
        assertEquals(readInLondon.listens.map { it.note }, readInTokyo.listens.map { it.note })
    }

    @Test
    fun `a half-hour offset buckets on the half hour`() {
        // India is UTC+5:30, so its day boundary is at 18:30Z. Four plays
        // straddling it: one before, three after, so exactly one is capped.
        // An offset held in whole hours would put the boundary at 19:00Z,
        // land all four on the same day, and cap two.
        val midnightKolkata = 1_788_978_600_000L // 2026-09-10T00:00:00+05:30
        val breakdown = scoreOf(
            listOf(
                segment(midnightKolkata - 60_000, duration, utcOffsetMinutes = 330),
                segment(midnightKolkata + 60_000, duration, utcOffsetMinutes = 330),
                segment(midnightKolkata + 120_000, duration, utcOffsetMinutes = 330),
                segment(midnightKolkata + 180_000, duration, utcOffsetMinutes = 330),
            ),
        )

        assertEquals(
            "one play on the 9th and three on the 10th",
            1,
            breakdown.listens.count { it.note == ContributionNote.DAILY_CAP },
        )
    }

    @Test
    fun `a listen before the epoch gets its own day`() {
        // floorDiv, not `/`. For any instant before 1970 the shifted value is
        // negative, and `/` truncates towards zero -- so the last day of 1969
        // and the first of 1970 would collapse into one bucket. Four plays
        // straddling the epoch: one before, three after, one capped. Truncating
        // division would put all four together and cap two.
        val scorer = TrackScorer(ZoneId.of("UTC"))
        val breakdown = scorer.score(
            input(
                listOf(
                    segment(-3_600_000, duration, utcOffsetMinutes = 0),
                    segment(3_600_000, duration, utcOffsetMinutes = 0),
                    segment(3_660_000, duration, utcOffsetMinutes = 0),
                    segment(3_720_000, duration, utcOffsetMinutes = 0),
                ),
            ),
            defaults,
            nowMs = now,
        )

        assertEquals(
            "1969-12-31 and 1970-01-01 are different days",
            1,
            breakdown.listens.count { it.note == ContributionNote.DAILY_CAP },
        )
    }

    @Test
    fun `backfilling the offsets does not move a single score`() {
        // **The test the user asked for**, at the score level: the Walkman's
        // ranking must be identical before and after the v8 migration.
        //
        // "Before" is every row with a null offset, scored by a device in
        // Europe/London. "After" is the same rows carrying the offsets the
        // backfill computes -- London's zone history evaluated at each row's
        // own timestamp, which is precisely what the null path was looking up
        // anyway. So the two must agree, and they must agree across daylight
        // saving in both directions, which is where an offset stamped from
        // "today" rather than from the row's own instant would diverge.
        val zone = ZoneId.of("Europe/London")
        val scorer = TrackScorer(zone)

        // A year of listening, three plays a day, deliberately spanning both
        // 2021 transitions -- and clustered within half an hour of midnight, so
        // every play sits where an hour's error in the offset moves it to the
        // other day. Three a day against a cap of 2.0 guarantees the cap
        // actually bites, which is what stops this test passing vacuously:
        // if nothing were ever capped, the offset could not change a score.
        val start = java.time.Instant.parse("2021-01-01T00:00:00Z").toEpochMilli()
        val instants = (0 until 365).flatMap { dayIndex ->
            val dayStart = start + dayIndex * 24L * 60 * 60 * 1000
            listOf(
                dayStart + 23L * 60 * 60 * 1000 + 30 * 60 * 1000,
                dayStart + 23L * 60 * 60 * 1000 + 45 * 60 * 1000,
                dayStart + 30L * 60 * 1000,
            )
        }

        val before = instants.map { segment(it, duration, utcOffsetMinutes = null) }
        val after = instants.map {
            segment(it, duration, utcOffsetMinutes = zone.rules.getOffset(java.time.Instant.ofEpochMilli(it)).totalSeconds / 60)
        }

        val scoredBefore = scorer.score(input(before), defaults, now)
        val scoredAfter = scorer.score(input(after), defaults, now)

        assertTrue(
            "the fixture must actually exercise the cap, or this proves nothing",
            scoredBefore.listens.count { it.note == ContributionNote.DAILY_CAP } > 300,
        )
        assertEquals(
            "1,095 plays over a year, across both daylight-saving changes",
            scoredBefore.score,
            scoredAfter.score,
            TOLERANCE,
        )
        assertEquals(
            scoredBefore.listens.map { it.note },
            scoredAfter.listens.map { it.note },
        )
        assertEquals(
            scoredBefore.listens.map { it.playEquivalents },
            scoredAfter.listens.map { it.playEquivalents },
        )
    }

    // --- Phantoms, Phase 5c step 2 ------------------------------------------

    @Test
    fun `a remote segment contributes nothing`() {
        val breakdown = scoreOf(
            listOf(
                segment(
                    endedAtMs = now - day,
                    listenedMs = duration,
                    playbackType = PlaybackType.REMOTE,
                ),
            ),
        )

        assertEquals(0.0, breakdown.score, TOLERANCE)
        assertEquals(
            ContributionNote.REMOTE_SESSION,
            breakdown.listens.single().note,
        )
    }

    @Test
    fun `a remote segment stays visible with its listened time`() {
        // Visible for the same reason commentary is: a track whose rows are all
        // phantoms has to explain itself rather than look like a scoring bug.
        val breakdown = scoreOf(
            listOf(
                segment(
                    endedAtMs = now - day,
                    listenedMs = 123_456,
                    playbackType = PlaybackType.REMOTE,
                ),
            ),
        )

        val listen = breakdown.listens.single()
        assertEquals(123_456L, listen.listenedMs)
        assertEquals(0.0, listen.decayedValue, TOLERANCE)
        assertEquals("it never reached the curve", 0.0, listen.rawPlayEquivalents, TOLERANCE)
        assertEquals("and it is not counted as a scored listen", 0, breakdown.scoredListenCount)
    }

    @Test
    fun `a local segment and a null one both score as before`() {
        // Null is every row written before schema v8. The phase's stated test
        // is that the Walkman's ranking does not move, and this is that test at
        // the level of one track.
        val nullRow = scoreOf(listOf(segment(now - day, duration, playbackType = null)))
        val localRow = scoreOf(
            listOf(segment(now - day, duration, playbackType = PlaybackType.LOCAL)),
        )

        assertTrue("a full play still scores", nullRow.score > 0.9)
        assertEquals(nullRow.score, localRow.score, TOLERANCE)
        assertEquals(ContributionNote.SCORED, nullRow.listens.single().note)
    }

    @Test
    fun `the phantom of a full play cannot be mistaken for a rejection`() {
        // The harm the fix is really for. Step 1 measured a device recording a
        // zero-length trailing row for a track the other device played to 0.97;
        // a 0.00 fraction is below the lower breakpoint and scores as an
        // emphatic rejection of a song that was in fact enjoyed.
        val withPhantom = scoreOf(
            listOf(
                segment(now - day, duration, playbackType = PlaybackType.LOCAL),
                segment(now - day + 1000, listenedMs = 0, playbackType = PlaybackType.REMOTE),
            ),
        )
        val withoutPhantom = scoreOf(
            listOf(segment(now - day, duration, playbackType = PlaybackType.LOCAL)),
        )

        assertEquals(
            "the phantom must not drag the real play down",
            withoutPhantom.score,
            withPhantom.score,
            TOLERANCE,
        )
        assertTrue(withPhantom.score > 0.9)
    }

    @Test
    fun `a remote row is never folded into a real listen`() {
        // Excluded before the replay fold, deliberately. A phantom sitting next
        // to a real listen of the same track would otherwise be merged into it,
        // producing one listen that is partly somebody else's -- and the merged
        // row's listenedMs would be inflated rather than ignored.
        val real = segment(
            endedAtMs = now - day,
            listenedMs = 100_000,
            endReason = EndReason.POSITION_JUMP_BACK,
            jumpFromMs = 100_000,
            jumpToMs = 0,
            playbackType = PlaybackType.LOCAL,
        )
        val phantom = segment(
            endedAtMs = now - day + 5_000,
            listenedMs = 100_000,
            playbackType = PlaybackType.REMOTE,
        )

        val breakdown = scoreOf(listOf(real, phantom))
        val scored = breakdown.listens.filter { it.note != ContributionNote.REMOTE_SESSION }

        assertEquals("one real listen, un-merged", 1, scored.size)
        assertEquals(100_000L, scored.single().listenedMs)
        assertEquals(1, breakdown.listens.count { it.note == ContributionNote.REMOTE_SESSION })
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
