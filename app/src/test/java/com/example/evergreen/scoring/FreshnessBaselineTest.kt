package com.example.evergreen.scoring

import com.example.evergreen.data.EndReason
import com.example.evergreen.data.LikeEventType
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.PlaybackType
import com.example.evergreen.data.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.ZoneId
import kotlin.random.Random

/**
 * **With every scoring-freshness setting off, the scorer is exactly the
 * scorer it was before them.** Written before the scorer was changed, against
 * the formula as it stood on 2026-11-17, and never to be regenerated to make
 * it pass.
 *
 * Why it matters more than an ordinary regression test: every snapshot and
 * every `parameter_history` row stores the settings it was scored under, and
 * none of them carry the new keys. A missing key is read as "off"
 * (`docs/SCORING_FRESHNESS_PROMPT.md`, "The one rule that protects old
 * data"), so "off" has to mean the old formula **to the bit**, or every old
 * snapshot would silently be re-read under a formula it was never scored with.
 *
 * Exact, not within a tolerance. The expected values below are fingerprints of
 * every number in every breakdown, rendered from the raw bits of each double,
 * so a change in the fifteenth decimal place fails as loudly as a wrong sign.
 *
 * **If this fails**, the per-track fingerprints say which tracks moved. The fix
 * is in the scorer, not here: a new setting at its off value must not change
 * anything, including the order in which floating-point sums are taken.
 */
class FreshnessBaselineTest {

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val minute = 60 * 1000L

    /** The settings as shipped, and a deliberately different set. */
    private val defaults = ScoringParameters()
    private val varied = ScoringParameters(
        halfLifeDays = 35.0,
        lowerBreakpoint = 0.15,
        upperBreakpoint = 0.80,
        negativeWeight = -0.25,
        likeWeight = 2.0,
        replayJumpThresholdMs = 5_000L,
        dailyCapPlays = 1.5,
    )

    // --- The fixture ---------------------------------------------------------

    private class Fixture(
        val inputs: List<TrackScoringInput>,
        val commentaryEndedAtMs: List<Long>,
    )

    /**
     * A listening history with everything in it the new settings will touch,
     * and everything the old formula already handles.
     *
     * Built as **sessions on a timeline**, not as rows scattered per track,
     * because two of the four changes read the timeline: browsing runs need
     * consecutive listens across tracks, and DJ sessions need commentary
     * between songs. Each session belongs to one device (two phones, plus the
     * imported Spotify history) and is one of: ordinary listening, a burst of
     * flicks, a DJ session with commentary, or a night of looping one track.
     *
     * Seeded, so it is the same fixture on every run and every machine. Kotlin's
     * seeded `Random` is a specified algorithm, not the platform's.
     */
    private fun fixture(seed: Int = 20261117): Fixture {
        val random = Random(seed)
        val trackCount = 40
        val durations = (1..trackCount).associateWith { 140_000L + random.nextLong(160_000) } +
            (COMMENTARY_TRACK to 0L)
        // A few tracks whose service row has no duration, so that some of
        // their rows fall back to the canonical duration and some know none.
        val noServiceDuration = setOf(7, 19, 33)
        val noDurationAnywhere = setOf(33)

        val segments = (1..trackCount + 1).associateWith { mutableListOf<ListeningSegmentEntity>() }
        val commentary = mutableListOf<Long>()
        val commentaryRows = mutableListOf<Triple<String, Long, Int?>>()
        var id = 1L

        fun add(
            track: Int,
            device: String,
            startedAt: Long,
            listened: Long,
            endReason: EndReason = EndReason.TRACK_CHANGE,
            jumpFrom: Long? = null,
            playbackType: PlaybackType? = null,
            offset: Int? = null,
        ): Long {
            val imported = device == IMPORT
            val endedAt = startedAt + listened
            val rowDuration = when {
                imported || track == COMMENTARY_TRACK -> 0L
                track in noServiceDuration && random.nextBoolean() -> 0L
                else -> durations.getValue(track)
            }
            segments.getValue(track) += ListeningSegmentEntity(
                id = id++,
                trackId = track.toLong(),
                serviceTrackRowId = track * 10L,
                appPackage = "com.spotify.music",
                deviceId = device,
                startedAtMs = startedAt,
                endedAtMs = endedAt,
                startPositionMs = if (imported) null else 0L,
                endPositionMs = if (imported) null else listened,
                listenedMs = listened,
                trackDurationMs = rowDuration,
                forwardSeekCount = if (imported) null else 0,
                seekedForwardMs = if (imported) null else 0L,
                endReason = if (imported) EndReason.IMPORTED else endReason,
                endJumpFromMs = jumpFrom,
                endJumpToMs = jumpFrom?.let { 0L },
                lastSampleAgeMs = if (imported) null else 100L,
                createdAt = endedAt,
                source = if (imported) SegmentSource.IMPORT_SPOTIFY_HISTORY else SegmentSource.CAPTURE,
                sourceEndReason = if (imported) listOf("trackdone", "fwdbtn", "backbtn").random(random) else null,
                originRowId = if (device == PIXEL) 10_000L + id else null,
                playbackType = when {
                    imported -> null
                    playbackType != null -> playbackType
                    else -> listOf(null, PlaybackType.LOCAL, PlaybackType.LOCAL).random(random)
                },
                utcOffsetMinutes = if (imported) null else offset,
            )
            return endedAt
        }

        fun anyTrack() = random.nextInt(1, trackCount + 1)
        fun full(track: Int) = durations.getValue(track) - random.nextLong(0, 6_000)
        fun skipMs() = random.nextLong(500, 5_000)
        fun gap() = random.nextLong(0, 3_000)

        repeat(110) { session ->
            val device = when (random.nextInt(5)) {
                0, 1 -> WALKMAN
                2 -> PIXEL
                else -> IMPORT
            }
            // Imported history is old; captured listening is recent.
            val ageDays = if (device == IMPORT) random.nextInt(40, 470) else random.nextInt(0, 60)
            // A few sessions start late in the evening, so that the local day
            // a listen falls in depends on the offset it was captured under.
            var at = now - ageDays * day - random.nextLong(0, day)
            val offset = listOf(null, 0, 60, 540, -300).random(random)

            when (session % 5) {
                // Ordinary listening: mostly full plays, some cut short, the
                // odd replay and the odd remote-session row.
                0, 1 -> repeat(random.nextInt(3, 12)) {
                    val track = anyTrack()
                    val listened = when (random.nextInt(6)) {
                        0 -> skipMs()
                        1 -> durations.getValue(track) / 10
                        2 -> durations.getValue(track) / 2
                        else -> full(track)
                    }
                    at = when {
                        device != IMPORT && random.nextInt(8) == 0 -> {
                            // A replay: half a listen, a jump back, then the
                            // rest. A jump under the threshold is jitter and
                            // folds into one listen; at or over it, a replay
                            // and two. 7 s folds under the shipped 10 s and
                            // not under the varied 5 s; 15 s folds under
                            // neither.
                            val half = durations.getValue(track) / 2
                            val jump = listOf(7_000L, 15_000L).random(random)
                            val end = add(track, device, at, half, EndReason.POSITION_JUMP_BACK, jump, offset = offset)
                            add(track, device, end, half, offset = offset)
                        }
                        device != IMPORT && random.nextInt(10) == 0 ->
                            add(track, device, at, listened, playbackType = PlaybackType.REMOTE, offset = offset)
                        else -> add(track, device, at, listened, offset = offset)
                    } + gap()
                }
                // A burst of flicks, forwards through new songs or back
                // through songs just heard, ending on a song played in full.
                2 -> {
                    val recent = (1..random.nextInt(3, 8)).map { anyTrack() }
                    recent.forEach { track -> at = add(track, device, at, full(track), offset = offset) + gap() }
                    val flicks = if (random.nextBoolean()) recent.reversed() else (1..random.nextInt(3, 11)).map { anyTrack() }
                    flicks.forEach { track -> at = add(track, device, at, skipMs(), offset = offset) + gap() }
                    val landed = anyTrack()
                    add(landed, device, at, full(landed), offset = offset)
                }
                // A DJ session: commentary every few songs, picks played
                // through or skipped, and sometimes a genre block flicked past.
                3 -> if (device != IMPORT) {
                    repeat(random.nextInt(2, 5)) {
                        commentary += at + 20_000
                        commentaryRows += Triple(device, at, offset)
                        at += 20_000 + gap()
                        repeat(random.nextInt(2, 5)) {
                            val track = anyTrack()
                            val listened = when (random.nextInt(4)) {
                                0 -> skipMs()
                                1 -> random.nextLong(15_000, 25_000)
                                else -> full(track)
                            }
                            at = add(track, device, at, listened, offset = offset) + gap()
                        }
                    }
                }
                // A night on repeat: one track looped past the daily cap,
                // then skipped a few times the next evening.
                4 -> {
                    val track = anyTrack()
                    repeat(random.nextInt(3, 9)) { at = add(track, device, at, full(track), offset = offset) + gap() }
                    at += day
                    repeat(random.nextInt(1, 4)) { at = add(track, device, at, skipMs(), offset = offset) + 60 * minute }
                }
            }
        }

        // Same-timestamp collisions, which the saturating walk must order
        // without looking at local row ids: the same listen recorded on both
        // phones, and a like landing on the very millisecond a listen ended.
        val collisions = (1..6).map { anyTrack() }
        val sameMoment = now - 3 * day
        collisions.forEach { track ->
            add(track, WALKMAN, sameMoment - full(track), full(track), offset = 0)
        }
        collisions.forEach { track ->
            val endedAt = segments.getValue(track).last().endedAtMs
            add(track, PIXEL, endedAt - 2_000, 2_000, offset = 0)
        }

        // DJ commentary is stored as rows of its own "track", with a
        // `spotify:media:` URI and no duration, exactly as capture stores it.
        commentaryRows.forEach { (device, startedAt, offset) ->
            add(COMMENTARY_TRACK, device, startedAt, 20_000, offset = offset)
        }

        // A night on repeat that crosses midnight in London but not in Tokyo,
        // on rows with no stored offset, so the reading device's zone decides
        // the day: 23:40 BST on 2026-09-07 is 22:40 UTC and 07:40 in Tokyo.
        // Without this the zone pins below would agree for the wrong reason.
        var late = now - 10 * day + (10 * 60 + 40) * minute
        repeat(8) { late = add(MIDNIGHT_TRACK, WALKMAN, late, full(MIDNIGHT_TRACK), offset = null) + gap() }

        val inputs = (1..trackCount).map { track ->
            val likes = buildList {
                val likedAgo = random.nextInt(0, 600)
                add(LikeEventRecord(LikeEventType.LIKED, now - likedAgo * day))
                if (track % 9 == 0) {
                    add(LikeEventRecord(LikeEventType.UNLIKED, now - likedAgo / 2 * day))
                    add(LikeEventRecord(LikeEventType.LIKED, now - likedAgo / 3 * day))
                }
                if (track in collisions) add(LikeEventRecord(LikeEventType.LIKED, sameMoment))
            }
            TrackScoringInput(
                trackId = track.toLong(),
                segments = segments.getValue(track),
                serviceRows = mapOf(
                    track * 10L to ServiceTrackInfo(
                        "spotify:track:t$track",
                        if (track in noServiceDuration) 0L else durations.getValue(track),
                    ),
                ),
                canonicalDurationMs = if (track in noDurationAnywhere) 0L else durations.getValue(track),
                likeEvents = likes,
            )
        }
        val commentaryInput = TrackScoringInput(
            trackId = COMMENTARY_TRACK.toLong(),
            segments = segments.getValue(COMMENTARY_TRACK),
            serviceRows = mapOf(COMMENTARY_TRACK * 10L to ServiceTrackInfo("spotify:media:dj", 0L)),
            canonicalDurationMs = 0L,
            likeEvents = emptyList(),
        )
        return Fixture(inputs + commentaryInput, commentary)
    }

    // --- Rendering every number, exactly -------------------------------------

    /**
     * Every field of a breakdown that the old formula produced, written out
     * with each double as its raw bits.
     *
     * Only the fields that existed before the change: a new field on
     * [ScoreContribution] is pinned separately, by asserting it is empty when
     * its setting is off, so that adding one does not invalidate this.
     */
    private fun render(breakdown: ScoreBreakdown): String = buildString {
        append("track ").append(breakdown.trackId)
        append(" score ").append(bits(breakdown.score)).append('\n')
        breakdown.contributions.forEach { c ->
            append(c.kind).append(' ').append(c.atMs).append(' ').append(c.note)
            append(" f=").append(c.fractionHeard?.let(::bits))
            append(" ms=").append(c.listenedMs)
            append(" raw=").append(bits(c.rawPlayEquivalents))
            append(" pe=").append(bits(c.playEquivalents))
            append(" w=").append(bits(c.decayWeight))
            append(" v=").append(bits(c.decayedValue))
            append(" rows=").append(c.segmentIds)
            append(" off=").append(c.utcOffsetMinutes)
            append('\n')
        }
    }

    private fun bits(value: Double): String = java.lang.Long.toHexString(value.toRawBits())

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class Run(val digest: String, val perTrack: String, val ranking: String)

    private fun run(parameters: ScoringParameters, zone: ZoneId): Run {
        val fixture = fixture()
        val scorer = TrackScorer(zone)
        val windows = DjSessions.infer(fixture.commentaryEndedAtMs)
        val breakdowns = fixture.inputs.map { scorer.score(it, parameters, now, windows) }
        val ranking = Ranker(scorer).rank(fixture.inputs, parameters, now, windows)
        return Run(
            digest = sha256(breakdowns.joinToString("") { render(it) }),
            // Eight characters per track, in track order, so a failure says
            // which tracks moved.
            perTrack = breakdowns.joinToString(" ") { sha256(render(it)).take(8) },
            ranking = ranking.joinToString(" ") { "${it.trackId}:${bits(it.score)}" }.let(::sha256),
        )
    }

    private fun assertRun(expected: Run, actual: Run) {
        assertEquals("per-track fingerprints (track 1 first)", expected.perTrack, actual.perTrack)
        assertEquals("whole-breakdown fingerprint", expected.digest, actual.digest)
        assertEquals("ranking fingerprint", expected.ranking, actual.ranking)
    }

    // --- The fixture really does contain what it claims ----------------------

    @Test
    fun `the fixture exercises every path the old formula has`() {
        val fixture = fixture()
        val breakdowns = fixture.inputs.map {
            TrackScorer(LONDON).score(it, defaults, now, DjSessions.infer(fixture.commentaryEndedAtMs))
        }
        val notes = breakdowns.flatMap { b -> b.contributions.map { it.note } }.toSet()
        // Listed by name rather than as `entries`, so that adding a note for a
        // new setting does not make this claim about the old formula wrong.
        val oldNotes = setOf(
            ContributionNote.SCORED,
            ContributionNote.UNKNOWN_DURATION,
            ContributionNote.COMMENTARY,
            ContributionNote.REMOTE_SESSION,
            ContributionNote.DJ_SUPPRESSED,
            ContributionNote.DAILY_CAP,
            ContributionNote.LIKE,
            ContributionNote.UNLIKE_IGNORED,
        )
        assertEquals("every note the old formula can give, and no other", oldNotes, notes)
        assertTrue("too few DJ sessions", fixture.commentaryEndedAtMs.size >= 10)
        assertEquals("two phones and the import", 3, fixture.inputs.flatMap { it.segments }.map { it.deviceId }.toSet().size)
    }

    // --- The pins ------------------------------------------------------------

    @Test
    fun `shipped settings, London`() = assertRun(
        Run(
            digest = "6196a662008e9d30ed1b3d97699891abe5ad2109882a402afd373cf511f5e8e5",
            perTrack =
            "345a5030 9acab57f 1eb0de3e b87b7928 1183513e 7b59a043 605a7f89 8b5e1176 " +
            "e7bc18c2 0ae2afa2 f63fbef6 18f4284a 0017886f 8970bc9d c72caba9 57ca853a " +
            "60395de4 72b35894 a5c20dd4 96132fed af533a35 54615c3c 4fdcd2d5 8224f4c2 " +
            "299a5808 6c513595 2d7f3000 170b27ca e2c704e1 ef8455fb 64c821aa 9940cc04 " +
            "f7048d78 bf5b44c6 625b053b 30ef8a4a 9a92f025 757fdd10 c42cfd08 0fa88e27 " +
            "bca42781",
            ranking = "2da4d83157252f35e49ab8bffb4bd1f67cf15907fa77c17808da92b271f31ee4",
        ),
        run(defaults, LONDON),
    )

    @Test
    fun `varied settings, UTC`() = assertRun(
        Run(
            digest = "4638446faec1f2ff5245b5084a63151e1531818c4d59154d2a1ff6bbdf2fb060",
            perTrack =
            "916981a9 415a5573 47b14c5e 9f103636 351a8286 ad65193f b00b90e9 e3b8e75e " +
            "09be471e b05a3d79 c63558de edc9bd28 2c107bbb 854a34f3 2755357b 4858fb4f " +
            "ad44df2c cd9a688a 8f8dc0f2 53c8f949 cc5de977 b1d7b908 bfc1debb 1ee26095 " +
            "82783851 4f366d18 f57c027e a6f16f66 d8db21cb a8734c44 fa4270a7 0348e408 " +
            "3bffddd4 bf87f4d2 e78a55a5 b5ff8811 e92fab20 93dc1cd9 9a5d56ac e0f45071 " +
            "bca42781",
            ranking = "bef7c037385d00d56ccb5631eeaac11e7b2b1d1138073c1aaf5af32b03dd09e4",
        ),
        run(varied, ZoneId.of("UTC")),
    )

    @Test
    fun `shipped settings, Tokyo`() = assertRun(
        Run(
            digest = "7d0e24b03b63c45d5081acd0eae1a4f742ae3fcceab44c8eb845eeef7c3826cd",
            perTrack =
            "345a5030 9acab57f 1eb0de3e b87b7928 1183513e 7b59a043 605a7f89 8b5e1176 " +
            "e7bc18c2 0ae2afa2 f63fbef6 af758216 0017886f 8970bc9d c72caba9 57ca853a " +
            "60395de4 72b35894 a5c20dd4 96132fed af533a35 54615c3c 4fdcd2d5 8224f4c2 " +
            "299a5808 6c513595 2d7f3000 170b27ca e2c704e1 ef8455fb 64c821aa 9940cc04 " +
            "f7048d78 bf5b44c6 625b053b 30ef8a4a 9a92f025 757fdd10 c42cfd08 0fa88e27 " +
            "bca42781",
            ranking = "000c5ec7d1030618b3763ff1fa3cc89e1fb642045b9c4e2c8d891350d8847ff4",
        ),
        run(defaults, ZoneId.of("Asia/Tokyo")),
    )

    /**
     * The settings' own text and fingerprint, unchanged.
     *
     * The score cache stores [ScoringParameters.hash] beside every row and
     * rebuilds when it stops matching; `parameter_history` rows and synced
     * batches carry it too. New keys are written only when their feature is
     * on, so settings that were never changed keep exactly this text.
     */
    @Test
    fun `the settings JSON and its hash do not move`() {
        assertEquals(
            "{\"halfLifeDays\":45.0,\"lowerBreakpoint\":0.2,\"upperBreakpoint\":0.85,\"negativeWeight\":-0.5,\"likeWeight\":3.0,\"replayJumpThresholdMs\":10000,\"dailyCapPlays\":2.0,\"recordFloor\":0.1,\"playlistJoinRank\":100,\"playlistLeaveRank\":130}",
            defaults.toJson(),
        )
        assertEquals("058a46f6953c5219ee87867ab1976af3ef815be4435d301fe147be264021da26", defaults.hash())
        assertEquals(
            "{\"halfLifeDays\":35.0,\"lowerBreakpoint\":0.15,\"upperBreakpoint\":0.8,\"negativeWeight\":-0.25,\"likeWeight\":2.0,\"replayJumpThresholdMs\":5000,\"dailyCapPlays\":1.5,\"recordFloor\":0.1,\"playlistJoinRank\":100,\"playlistLeaveRank\":130}",
            varied.toJson(),
        )
        assertEquals("620a2b09212e3eef8a6b93ac336a18d5dcf146a4d338a244fd2a264c0a519e32", varied.hash())
    }

    private companion object {
        const val WALKMAN = "walkman"
        const val PIXEL = "pixel"
        const val IMPORT = "spotify-history-import"
        const val COMMENTARY_TRACK = 41
        const val MIDNIGHT_TRACK = 12
        val LONDON: ZoneId = ZoneId.of("Europe/London")
    }
}
