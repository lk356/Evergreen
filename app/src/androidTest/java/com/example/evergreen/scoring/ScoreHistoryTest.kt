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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The score-over-time line.
 *
 * It exists to settle an open question -- whether a single skip drags a score
 * down further than a day of listening can win back -- so the thing that must
 * be true of it is that it is *honest*: the value it shows for a past date has
 * to be the value the score actually had then, not an approximation that makes
 * the curve look smoother or steeper than the scoring really is.
 *
 * Nothing is stored. These tests are what keeps that defensible.
 */
@RunWith(AndroidJUnit4::class)
class ScoreHistoryTest {

    private lateinit var db: EvergreenDatabase
    private lateinit var service: ScoreService

    /** 2026-09-18T12:00:00Z. */
    private val now = 1_789_214_400_000L
    private val day = 24 * 60 * 60 * 1000L

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

    /**
     * The value at the right-hand end of the line is today's score.
     *
     * If this drifts, the chart and the number above it disagree, and the user
     * is left deciding which to believe.
     */
    @Test
    fun theLastPointIsTodaysScore() = runBlocking {
        val trackId = trackPlayedOn(daysAgo = listOf(1, 5, 12, 30, 60), likedDaysAgo = 60)

        val history = service.scoreHistory(trackId, nowMs = now, days = 180, points = 30)
        val today = service.breakdown(trackId, nowMs = now)!!.score

        assertEquals(now, history.last().atMs)
        assertEquals(today, history.last().score, 1e-9)
    }

    /**
     * Still today's score when the track has history older than the load
     * window.
     *
     * The line needs a longer reach than a single score does — its leftmost
     * point is months back and carries its own ten-half-life window — so it
     * loads more rows than the ranker ever would. If it then *scored* with all
     * of them, every point would be computed over more history than the app
     * itself uses, and the number under the chart would disagree with the one
     * above it. On a real track that showed up as 11.538 against 11.528: small,
     * and unarguable when both are on the same screen.
     */
    @Test
    fun theLastPointStillMatchesWhenThereIsHistoryOlderThanTheWindow() = runBlocking {
        val parameters = service.parameters()
        val windowDays = (parameters.loadWindowMs / day).toInt()

        // One play comfortably outside the window, and some inside it.
        val trackId = trackPlayedOn(
            daysAgo = listOf(windowDays + 40, windowDays + 5, 30, 3),
            likedDaysAgo = windowDays + 40,
        )

        val history = service.scoreHistory(trackId, nowMs = now, days = 180, points = 30)
        val today = service.breakdown(trackId, nowMs = now)!!.score

        assertEquals(
            "the end of the line must be the score the rest of the app shows",
            today,
            history.last().score,
            1e-9,
        )
    }

    /**
     * A point on the line counts the past and nothing else.
     *
     * The arithmetic is done here rather than by asking the app a second time,
     * because the obvious oracle is wrong: [ScoreService.breakdown] takes a
     * `nowMs` but does **not** exclude events after it, so asking it about a
     * past instant includes listening that had not happened yet. [Decay]
     * clamps a future-dated event to weight 1.0 rather than letting a bad
     * clock inflate a score, so each such play quietly adds a whole play
     * rather than blowing up — which is exactly the kind of wrongness that
     * would pass unnoticed.
     *
     * So: a track liked and played once 100 days ago, and played again 5 days
     * ago. Fifty days ago its score was the like and the one play, both decayed
     * fifty days, and the play still five days in its future contributed
     * nothing at all.
     */
    @Test
    fun aPointOnTheLineCountsThePastAndNothingElse() = runBlocking {
        val trackId = trackPlayedOn(daysAgo = listOf(100, 5), likedDaysAgo = 100)
        val parameters = service.parameters()

        val history = service.scoreHistory(trackId, nowMs = now, days = 60, points = 61)
        val fiftyDaysAgo = history.single { it.atMs == now - 50 * day }

        // One full play (1.0) and one like (3.0 by default), both fifty days
        // old. Nothing else existed yet.
        val expected = (1.0 + parameters.likeWeight) *
            Decay.weightForAge(50 * day, parameters.halfLifeMs)

        assertEquals(expected, fiftyDaysAgo.score, 1e-9)

        // And it does count, once it has happened: the step is visible against
        // the day before it, not against fifty days before it. Comparing with
        // `fiftyDaysAgo` would fail for a reason that is not a bug -- fifty
        // further days of decay on a like worth three plays outweighs one new
        // play, so the line is legitimately *lower* at the end than in the
        // middle.
        val theDayBefore = history.single { it.atMs == now - 6 * day }
        assertTrue(
            "the play five days ago must show as a step up",
            history.last().score > theDayBefore.score,
        )
    }

    /**
     * A score before anything happened is zero, not a number borrowed from
     * later.
     *
     * The failure this guards against is arithmetic rather than hypothetical:
     * decay weights are 2^(-(now - at)/halfLife), so an event dated *after* the
     * instant being asked about gets an exponent above zero and counts for more
     * than one play. Left unfiltered, the left-hand end of every line would
     * show a score the track never had, and the more recent the listening the
     * more wrong it would be.
     */
    @Test
    fun theLineIsFlatAtZeroBeforeAnythingHappened() = runBlocking {
        // Liked and played on the same day, so "before the play" really is
        // before every event this track has.
        val trackId = trackPlayedOn(daysAgo = listOf(10), likedDaysAgo = 10)

        val history = service.scoreHistory(trackId, nowMs = now, days = 180, points = 60)
        val before = history.filter { it.atMs < now - 11 * day }

        assertTrue("there should be samples before the only play", before.size > 40)
        before.forEach { point ->
            assertEquals("a score before anything happened must be zero", 0.0, point.score, 1e-9)
        }
        assertTrue("and non-zero after it", history.last().score > 0.0)
    }

    /** Points are evenly spaced, oldest first, and span exactly the window asked for. */
    @Test
    fun theWindowIsEvenlySpacedAndCoversWhatWasAskedFor() = runBlocking {
        val trackId = trackPlayedOn(daysAgo = listOf(2), likedDaysAgo = 2)

        val history = service.scoreHistory(trackId, nowMs = now, days = 90, points = 10)
        assertEquals(10, history.size)
        assertEquals(now - 90 * day, history.first().atMs)
        assertEquals(now, history.last().atMs)

        val gaps = history.zipWithNext { a, b -> b.atMs - a.atMs }.distinct()
        assertTrue("samples should be evenly spaced, found $gaps", gaps.size <= 2)
        // forEach rather than zipWithNext, which returns a List and would make
        // this method non-void -- JUnit then rejects the whole class rather
        // than this one test, and every other test in it silently never runs.
        history.windowed(2).forEach { (a, b) ->
            assertTrue("oldest first", a.atMs < b.atMs)
        }
    }

    /**
     * A skip shows as a fall, and a play after it as a recovery.
     *
     * This is the shape the open question is about. The point is not the exact
     * numbers -- those are what the user is going to look at -- but that the
     * line actually moves in both directions rather than only decaying.
     */
    @Test
    fun aSkipShowsAsAFallAndAPlayAfterItAsARecovery() = runBlocking {
        // A full play 40 days ago, then a skip 20 days ago, then a full play
        // 10 days ago.
        val trackId = trackPlayedOn(daysAgo = listOf(40), likedDaysAgo = 40)
        play(daysAgo = 20, fraction = 0.05)
        play(daysAgo = 10, fraction = 1.0)

        val history = service.scoreHistory(trackId, nowMs = now, days = 60, points = 60)
        fun at(daysAgo: Int) = history.minByOrNull { kotlin.math.abs(it.atMs - (now - daysAgo * day)) }!!

        val beforeSkip = at(22).score
        val afterSkip = at(18).score
        val afterRecovery = at(8).score

        assertTrue("the skip should pull the score down", afterSkip < beforeSkip)
        assertTrue("a full play afterwards should pull it back up", afterRecovery > afterSkip)
    }

    /** An unknown track produces no line rather than a flat one at zero. */
    @Test
    fun anUnknownTrackHasNoLine() = runBlocking {
        assertTrue(service.scoreHistory(999_999L, nowMs = now).isEmpty())
    }

    // --- Fixtures ------------------------------------------------------------

    /**
     * A liked track with plays on exactly the days given, and nothing else.
     *
     * "Nothing else" is the whole point, and the earlier version of this
     * fixture got it wrong in two ways that made a test lie. A track can only
     * be created by recording a segment, so it used to write one 400 days back
     * purely to bring the row into existence -- a play nobody asked for. And it
     * dated the like 300 days back, which is a scoring event of its own worth
     * three plays. A test asserting "the score before the first play is zero"
     * was then asserting something false about a fixture that had two events
     * before it.
     *
     * Now the oldest requested play *is* the segment that creates the track,
     * and the like is dated explicitly.
     */
    private suspend fun trackPlayedOn(daysAgo: List<Int>, likedDaysAgo: Int): Long {
        require(daysAgo.isNotEmpty()) { "a track needs at least one play to exist" }
        val dao = db.dao()
        val oldestFirst = daysAgo.sortedDescending()

        dao.recordSegment(
            trackKey = trackKeyOf(TITLE, ARTIST, ALBUM),
            title = TITLE,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION,
            service = SERVICE_SPOTIFY,
            serviceTrackId = URI,
            segment = segment(now - oldestFirst.first() * day, 1.0),
        )
        val trackId = dao.allTracks().single().id

        // Liked, because only liked tracks are scored at all.
        db.spotifyDao().recordLiked(
            dao.allServiceTracks().single().id,
            serviceAddedAtMs = now - likedDaysAgo * day,
            now = now - likedDaysAgo * day,
        )

        oldestFirst.drop(1).forEach { play(daysAgo = it, fraction = 1.0) }
        return trackId
    }

    private suspend fun play(daysAgo: Int, fraction: Double) {
        db.dao().recordSegment(
            trackKey = trackKeyOf(TITLE, ARTIST, ALBUM),
            title = TITLE,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION,
            service = SERVICE_SPOTIFY,
            serviceTrackId = URI,
            segment = segment(now - daysAgo * day, fraction),
        )
    }

    private fun segment(endedAtMs: Long, fraction: Double) = ListeningSegmentEntity(
        trackId = 0,
        serviceTrackRowId = null,
        appPackage = "com.spotify.music",
        deviceId = "test-device",
        startedAtMs = endedAtMs - (DURATION * fraction).toLong(),
        endedAtMs = endedAtMs,
        startPositionMs = 0,
        endPositionMs = (DURATION * fraction).toLong(),
        listenedMs = (DURATION * fraction).toLong(),
        trackDurationMs = DURATION,
        forwardSeekCount = 0,
        seekedForwardMs = 0,
        endReason = EndReason.TRACK_CHANGE,
        endJumpFromMs = null,
        endJumpToMs = null,
        lastSampleAgeMs = 100,
        createdAt = endedAtMs,
    )

    private companion object {
        const val DURATION = 200_000L
        const val TITLE = "Sample"
        const val ARTIST = "Tester"
        const val ALBUM = "Album"
        const val URI = "spotify:track:sample"
    }
}
