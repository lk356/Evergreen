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
import java.util.Locale
import kotlin.random.Random

/**
 * The proof Phase 5a exists to produce: **the same facts and the same
 * parameters give the same ranking, whichever device computed it.**
 *
 * This is invariant 1a — a score is computed, never accumulated — restated
 * across devices, and it is the foundation the 5b merge would stand on. If two
 * devices holding identical facts can disagree about the order, then shipping
 * facts and recomputing locally is not a sound way to sync, and it is much
 * better to find that out with a mirror than after a merge has been built on
 * top of it.
 *
 * [RecomputeTest] already pins that the function is a function. What is new
 * here is the list of ways two devices genuinely differ, and which of them the
 * ranking survives. They are not hypothetical: the Walkman and the Pixel really
 * do recompute at different instants, really do read their rows back in
 * whatever order SQLite hands them over, and really could be moved into
 * different timezones.
 *
 * The one that does *not* survive is the timezone, and the test that pins it is
 * the most useful one in this file — see [aDifferentTimezoneCanChangeTheRanking].
 */
class CrossDeviceRankingTest {

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val hour = 60 * 60 * 1000L

    private val parameters = ScoringParameters()
    private val london = ZoneId.of("Europe/London")

    /**
     * Scores a set of facts **as one device would**.
     *
     * The load window is applied here rather than left out, because it is part
     * of what each device does and it is the one place where a difference in
     * `now` can change which rows are seen at all. Leaving it out would make
     * the "recomputed later" test below prove something weaker than it claims.
     */
    private fun rankAsDevice(
        facts: List<TrackScoringInput>,
        nowMs: Long = now,
        zone: ZoneId = london,
    ): List<RankedTrack> {
        val from = Ranker.loadFromMs(nowMs, parameters)
        val windowed = facts.map { input ->
            input.copy(
                segments = input.segments.filter { it.endedAtMs in from..nowMs },
                likeEvents = input.likeEvents.filter { it.atMs <= nowMs },
            )
        }
        return Ranker(TrackScorer(zone)).rank(windowed, parameters, nowMs)
    }

    // --- The fixture ---------------------------------------------------------

    /**
     * A library with everything in it that could make two devices disagree.
     *
     * Deliberately includes listening that straddles the ten-half-life load
     * window, so that a device recomputing a few hours later really does drop
     * rows the first one kept. Without that, the "recomputed later" test would
     * pass for the wrong reason.
     */
    private fun library(trackCount: Int = 60, seed: Int = 20260919): List<TrackScoringInput> {
        val random = Random(seed)
        var segmentId = 1L
        val windowDays = (parameters.loadWindowMs / day).toInt()

        return (1..trackCount).map { trackId ->
            val duration = 150_000L + random.nextLong(120_000)
            val serviceRow = trackId * 10L
            val segments = mutableListOf<ListeningSegmentEntity>()

            repeat(random.nextInt(2, 30)) {
                // Spread across, and a little beyond, the load window.
                val ageDays = random.nextInt(0, windowDays + 30)
                val imported = ageDays > 30
                val heard = when (random.nextInt(4)) {
                    0 -> random.nextLong(1, duration / 8)
                    1 -> duration / 2
                    else -> duration - random.nextLong(0, 8_000)
                }
                val endedAt = now - ageDays * day - random.nextLong(0, day)
                segments += ListeningSegmentEntity(
                    id = segmentId++,
                    trackId = trackId.toLong(),
                    serviceTrackRowId = serviceRow,
                    appPackage = "com.spotify.music",
                    deviceId = if (imported) "spotify-history-import" else "walkman",
                    startedAtMs = endedAt - heard,
                    endedAtMs = endedAt,
                    startPositionMs = if (imported) null else 0,
                    endPositionMs = if (imported) null else heard,
                    listenedMs = heard,
                    trackDurationMs = duration,
                    forwardSeekCount = if (imported) null else 0,
                    seekedForwardMs = if (imported) null else 0,
                    endReason = if (imported) EndReason.IMPORTED else EndReason.TRACK_CHANGE,
                    endJumpFromMs = null,
                    endJumpToMs = null,
                    lastSampleAgeMs = if (imported) null else 100,
                    createdAt = endedAt,
                    source = if (imported) SegmentSource.IMPORT_SPOTIFY_HISTORY else SegmentSource.CAPTURE,
                )
            }

            TrackScoringInput(
                trackId = trackId.toLong(),
                segments = segments,
                serviceRows = mapOf(serviceRow to ServiceTrackInfo("spotify:track:t$trackId", duration)),
                canonicalDurationMs = duration,
                likeEvents = listOf(
                    LikeEventRecord(LikeEventType.LIKED, now - random.nextInt(0, 1_500) * day),
                ),
            )
        }
    }

    // --- What survives -------------------------------------------------------

    /** The base case, and the one the whole phase rests on. */
    @Test
    fun `the same facts and parameters give the same ranking`() {
        val facts = library()

        val walkman = rankAsDevice(facts)
        val pixel = rankAsDevice(facts)

        assertEquals("not merely the same order — the same numbers", walkman, pixel)
    }

    /**
     * Two devices read their rows back in whatever order the database gives
     * them, and nothing guarantees that is the same order.
     *
     * A mirror copies row ids, so today the two orders do match. That is
     * exactly why this is worth pinning now: the merge in 5b will not preserve
     * them, and a ranking that silently depended on insertion order would
     * break then, with the mirror's success as evidence that it could not.
     */
    @Test
    fun `the order the rows arrive in does not change the ranking`() {
        val facts = library()

        val inOrder = rankAsDevice(facts)
        val shuffled = rankAsDevice(
            facts.shuffled(Random(7)).map { input ->
                input.copy(segments = input.segments.shuffled(Random(11)))
            },
        )

        assertEquals(inOrder, shuffled)
    }

    /**
     * The interesting one: the two devices recompute at different moments.
     *
     * They always will — the Walkman writes Evergreen at some point overnight
     * and the Pixel recomputes when the mirror is opened — so if a few hours
     * could reorder the ranking, a mirror could never be checked against the
     * device it came from.
     *
     * It cannot, and the reason is worth stating because it is not obvious. A
     * contribution's decay weight is `2^(-(now - at) / halfLife)`; shifting
     * `now` by the same amount for every contribution multiplies *every* weight
     * by the same constant, so every score scales by that constant and the
     * order is untouched. The daily cap is applied before decay (see
     * [TrackScorer.applyDailyCap]) and so does not disturb this, which is a
     * property of that ordering rather than a coincidence.
     *
     * The only asymmetry is the load window, which moves with `now` and drops a
     * sliver of listening ten half-lives old — worth under a thousandth of a
     * play, and this fixture deliberately has rows there to prove it is small
     * rather than absent.
     */
    @Test
    fun `recomputing hours later gives the same order`() {
        val facts = library()

        val walkmanAtNight = rankAsDevice(facts, nowMs = now)
        val pixelInTheMorning = rankAsDevice(facts, nowMs = now + 9 * hour)

        assertEquals(
            "the same tracks in the same places",
            walkmanAtNight.map { it.trackId },
            pixelInTheMorning.map { it.trackId },
        )
        assertNotEquals(
            "though every score is smaller, because everything has decayed",
            walkmanAtNight.map { it.score },
            pixelInTheMorning.map { it.score },
        )
    }

    /** Nine hours is nothing. A fortnight between imports is the real case. */
    @Test
    fun `recomputing a fortnight later gives the same order`() {
        val facts = library()

        assertEquals(
            rankAsDevice(facts, nowMs = now).map { it.trackId },
            rankAsDevice(facts, nowMs = now + 14 * day).map { it.trackId },
        )
    }

    /**
     * The scoring parameters must fingerprint identically on both devices.
     *
     * The score cache stamps every row with this hash and rebuilds when it
     * stops matching, and a snapshot stores the JSON verbatim. If the hash
     * depended on anything about the device, a mirror would rebuild its cache
     * forever and — worse — a snapshot taken on one device would claim to have
     * been computed under settings that did not match the other's.
     *
     * The device-dependent thing to be afraid of is the default locale.
     * `String.format` renders digits in the default locale's numbering system,
     * and under `ar-SA-u-nu-arab` `%d` of 255 really does come back as `٢٥٥`.
     * It happens that Java does not localise `%x`, which is what the digest
     * uses, so this passes — but it passes for a reason nobody chose, and the
     * failure it would have caused (a cache that never matched, and snapshots
     * claiming settings the other device could not reproduce) is invisible
     * enough to be worth a test rather than a comment.
     */
    @Test
    fun `the parameters fingerprint the same whatever the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.UK)
            val onOneDevice = parameters.toJson() to parameters.hash()

            // A locale whose numbering system is not ASCII digits.
            Locale.setDefault(Locale.forLanguageTag("ar-SA-u-nu-arab"))
            val onAnother = parameters.toJson() to parameters.hash()

            assertEquals("the canonical JSON", onOneDevice.first, onAnother.first)
            assertEquals("and the fingerprint over it", onOneDevice.second, onAnother.second)
            assertTrue(
                "the fingerprint is plain ASCII hex",
                onAnother.second.all { it in '0'..'9' || it in 'a'..'f' },
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- What does not survive -----------------------------------------------

    /**
     * **The one real cross-device hazard, pinned so that it is a known fact.**
     *
     * The daily cap buckets listening by *local* calendar day, using
     * `ZoneId.systemDefault()` — a deliberate Phase 3 decision, because "forty
     * loops in one night" is a local idea and a UTC day would cut an evening in
     * half. The consequence is that two devices in different timezones can cap
     * the same listening differently and produce different scores.
     *
     * It does not bite today: both devices are in the same place, and a mirror
     * carries no timezone with it. It would bite the moment one device
     * travelled, and it would bite silently — the scores would simply be a
     * little different, with nothing to say why.
     *
     * Recorded rather than fixed. Fixing it means storing the zone a segment
     * was recorded in, which is a schema change and a Phase 5b decision; and
     * scoring everything in UTC instead would change the calibrated ranking on
     * the device that is already working. This test is the note that says which
     * of those two it is.
     */
    @Test
    fun `a different timezone can change the ranking`() {
        // Listening concentrated around local midnight, which is where the
        // day boundary decides whether two plays share a cap or not.
        val facts = (1..12).map { trackId ->
            val duration = 200_000L
            val serviceRow = trackId * 10L
            val plays = (0 until 6).map { index ->
                val endedAt = now - trackId * day - index * 3 * hour
                ListeningSegmentEntity(
                    id = trackId * 100L + index,
                    trackId = trackId.toLong(),
                    serviceTrackRowId = serviceRow,
                    appPackage = "com.spotify.music",
                    deviceId = "walkman",
                    startedAtMs = endedAt - duration,
                    endedAtMs = endedAt,
                    startPositionMs = 0,
                    endPositionMs = duration,
                    listenedMs = duration,
                    trackDurationMs = duration,
                    forwardSeekCount = 0,
                    seekedForwardMs = 0,
                    endReason = EndReason.TRACK_CHANGE,
                    endJumpFromMs = null,
                    endJumpToMs = null,
                    lastSampleAgeMs = 100,
                    createdAt = endedAt,
                )
            }
            TrackScoringInput(
                trackId = trackId.toLong(),
                segments = plays,
                serviceRows = mapOf(serviceRow to ServiceTrackInfo("spotify:track:t$trackId", duration)),
                canonicalDurationMs = duration,
                likeEvents = listOf(LikeEventRecord(LikeEventType.LIKED, now - 400 * day)),
            )
        }

        val here = rankAsDevice(facts, zone = london)
        val elsewhere = rankAsDevice(facts, zone = ZoneId.of("Pacific/Auckland"))

        assertNotEquals(
            "the daily cap buckets by local day, so the zone reaches the scores",
            here.map { it.score },
            elsewhere.map { it.score },
        )
        assertEquals(
            "both devices are in the same timezone today, which is why this is a note " +
                "and not a bug",
            rankAsDevice(facts, zone = london),
            here,
        )
    }
}
