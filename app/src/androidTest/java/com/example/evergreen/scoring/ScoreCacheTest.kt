package com.example.evergreen.scoring

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.data.EndReason
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.trackKeyOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of test 4 in `docs/PHASE3_PLAN.md` section 9: the same
 * recompute guarantee, but through the cache.
 *
 * `RecomputeTest` (a plain JVM test) pins that the scoring *function* carries no
 * state between runs. This one pins that adding a cache in front of it did not
 * quietly reintroduce the accumulation invariant 1a forbids — that changing a
 * parameter really does produce the same answer as a database that had never
 * seen the old one.
 */
@RunWith(AndroidJUnit4::class)
class ScoreCacheTest {

    private lateinit var db: EvergreenDatabase
    private lateinit var service: ScoreService

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private val parametersB = ScoringParameters(
        halfLifeDays = 90.0,
        upperBreakpoint = 0.80,
        likeWeight = 5.0,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = ScoreService(db)
    }

    @After
    fun tearDown() = db.close()

    /** A few tracks with different listening shapes and like ages. */
    private suspend fun buildHistory(trackCount: Int = 12) {
        repeat(trackCount) { index ->
            val title = "Track $index"
            val duration = 180_000L + index * 1_000L
            val uri = "spotify:track:t%03d".format(index)

            repeat(index % 5 + 1) { play ->
                db.dao().recordSegment(
                    trackKey = trackKeyOf(title, "Artist", "Album"),
                    title = title,
                    artist = "Artist",
                    album = "Album",
                    durationMs = duration,
                    service = SERVICE_SPOTIFY,
                    serviceTrackId = uri,
                    segment = ListeningSegmentEntity(
                        trackId = 0,
                        serviceTrackRowId = null,
                        appPackage = "com.spotify.music",
                        deviceId = "test-device",
                        startedAtMs = now - (index * 7 + play) * day - duration,
                        endedAtMs = now - (index * 7 + play) * day,
                        startPositionMs = 0,
                        endPositionMs = duration,
                        // Some full plays, some rejections.
                        listenedMs = if (play % 3 == 0) duration else duration / 20,
                        trackDurationMs = duration,
                        forwardSeekCount = 0,
                        seekedForwardMs = 0,
                        endReason = EndReason.TRACK_CHANGE,
                        endJumpFromMs = null,
                        endJumpToMs = null,
                        lastSampleAgeMs = 100,
                        createdAt = now,
                    ),
                )
            }

            val rowId = db.spotifyDao().resolveServiceTrack(
                trackKey = trackKeyOf(title, "Artist", "Album"),
                title = title,
                artist = "Artist",
                album = "Album",
                durationMs = duration,
                service = SERVICE_SPOTIFY,
                serviceTrackId = uri,
                isrc = null,
                now = now,
            )
            db.spotifyDao().recordLiked(
                serviceTrackRowId = rowId,
                serviceAddedAtMs = now - (index * 30L) * day,
                now = now,
            )
        }
    }

    private suspend fun freshServiceScores(parameters: ScoringParameters): Map<Long, Double> {
        // A second service over the same database, with the cache cleared, is
        // as close as this can get to "a database that never saw parameters A".
        db.scoringDao().replaceScores(emptyList())
        return ScoreService(db).ranking(now, force = true).tracks.associate { it.trackId to it.score }
    }

    // --- The cache is filled and reused ---------------------------------------

    @Test
    fun theFirstRankingComputesAndFillsTheCache() = runBlocking {
        buildHistory()

        val first = service.ranking(now)

        assertFalse("nothing to read yet", first.fromCache)
        assertEquals(12, first.tracks.size)
        assertEquals(12, db.scoringDao().cachedScoreCount())
        assertEquals(first.parameters.hash(), db.scoringDao().cachedParametersHash())
    }

    @Test
    fun theSecondRankingComesFromTheCache() = runBlocking {
        buildHistory()
        val first = service.ranking(now)

        val second = service.ranking(now)

        assertTrue(second.fromCache)
        assertEquals(first.tracks, second.tracks)
    }

    @Test
    fun forcingARecomputeBypassesTheCache() = runBlocking {
        buildHistory()
        service.ranking(now)

        val forced = service.ranking(now, force = true)

        assertFalse(forced.fromCache)
    }

    // --- Invariant 1a, through the cache --------------------------------------

    /**
     * The test the whole cache design exists to pass.
     *
     * Score under A, fill the cache, change the parameters, recompute through
     * the cache path — and get exactly what a database that had never seen A
     * would produce. If any part of the old answer survived into the new one,
     * this fails.
     */
    @Test
    fun changingAParameterGivesTheSameAnswerAsAFreshDatabase() = runBlocking {
        buildHistory()

        service.ranking(now)
        val underA = db.scoringDao().cachedScores().associate { it.trackId to it.score }

        db.scoringDao().putScoringParameters(parametersB)
        val afterChange = service.ranking(now).tracks.associate { it.trackId to it.score }

        val fromScratch = freshServiceScores(parametersB)

        assertNotEquals("the two parameter sets must actually differ", underA, afterChange)
        assertEquals(fromScratch.keys, afterChange.keys)
        fromScratch.forEach { (trackId, expected) ->
            assertEquals(
                "track $trackId",
                expected,
                afterChange.getValue(trackId),
                1e-9,
            )
        }
    }

    /** Dropping the cache and rebuilding must give the same again. */
    @Test
    fun droppingTheCacheAndRebuildingChangesNothing() = runBlocking {
        buildHistory()
        val before = service.ranking(now).tracks

        db.scoringDao().replaceScores(emptyList())
        val rebuilt = service.ranking(now)

        assertFalse(rebuilt.fromCache)
        assertEquals(before, rebuilt.tracks)
    }

    /** A changed parameter must make the cache stale, or it would be read as truth. */
    @Test
    fun changingAParameterMakesTheCacheStale() = runBlocking {
        buildHistory()
        service.ranking(now)

        assertFalse(db.scoringDao().isCacheStale(ScoringParameters()))
        assertTrue(db.scoringDao().isCacheStale(parametersB))
    }

    @Test
    fun anEmptyCacheIsStale() = runBlocking {
        assertTrue(db.scoringDao().isCacheStale(ScoringParameters()))
    }

    /**
     * A track unliked since the last run must not linger in the cache holding
     * the rank it had. The cache is replaced wholesale rather than upserted for
     * exactly this reason.
     */
    @Test
    fun anUnlikedTrackLeavesTheCache() = runBlocking {
        buildHistory()
        assertEquals(12, service.ranking(now).tracks.size)

        db.spotifyDao().markUnlikedAfterFullSync(syncStartedAt = now + 1, now = now + 2)
        val after = service.ranking(now, force = true)

        assertTrue("no tracks are liked any more", after.tracks.isEmpty())
        assertEquals(0, db.scoringDao().cachedScoreCount())
    }

    // --- Breakdowns ------------------------------------------------------------

    @Test
    fun aBreakdownExplainsTheScoreItIsPairedWith() = runBlocking {
        buildHistory()
        val ranking = service.ranking(now)
        val top = ranking.tracks.first()

        val breakdown = service.breakdown(top.trackId, now)

        assertNotNull(breakdown)
        // The explain view must never disagree with the ranked list.
        assertEquals(top.score, breakdown!!.score, 1e-9)
        assertEquals(
            breakdown.contributions.sumOf { it.decayedValue },
            breakdown.score,
            1e-9,
        )
        assertTrue(breakdown.contributions.isNotEmpty())
        assertTrue(breakdown.likes.isNotEmpty())
    }

    @Test
    fun aBreakdownForAnUnknownTrackIsNull() = runBlocking {
        assertEquals(null, service.breakdown(9999, now))
    }

    // --- The record ------------------------------------------------------------

    @Test
    fun theRecordIsEveryLikedTrackAboveTheFloor() = runBlocking {
        buildHistory()

        val record = service.record(now)
        val all = service.ranking(now).tracks

        assertTrue(record.size <= all.size)
        assertTrue(record.all { it.score > ScoringParameters().recordFloor })
    }

    // --- Parameters ------------------------------------------------------------

    @Test
    fun parametersRoundTripThroughTheDatabase() = runBlocking {
        db.scoringDao().putScoringParameters(parametersB)

        assertEquals(parametersB, db.scoringDao().scoringParameters())
        assertTrue(db.scoringDao().hasStoredScoringParameters())
    }

    @Test
    fun unusableParametersAreRefusedBeforeTheyAreStored() = runBlocking {
        try {
            db.scoringDao().putScoringParameters(ScoringParameters(halfLifeDays = 0.0))
            throw AssertionError("should have refused a zero half-life")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("halfLifeDays"))
        }
        assertFalse(db.scoringDao().hasStoredScoringParameters())
    }

    /** Defaults apply until something is stored, so a fresh install scores. */
    @Test
    fun theDefaultsApplyBeforeAnythingIsStored() = runBlocking {
        assertEquals(ScoringParameters(), db.scoringDao().scoringParameters())
        assertFalse(db.scoringDao().hasStoredScoringParameters())
    }
}
