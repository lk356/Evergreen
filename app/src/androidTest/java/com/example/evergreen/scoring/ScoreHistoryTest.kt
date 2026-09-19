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
        val trackId = aTrackPlayed(onDaysAgo = listOf(1, 5, 12, 30, 60))

        val history = service.scoreHistory(trackId, nowMs = now, days = 180, points = 30)
        val today = service.breakdown(trackId, nowMs = now)!!.score

        assertEquals(now, history.last().atMs)
        assertEquals(today, history.last().score, 1e-9)
    }

    /**
     * Each point equals what the scorer says for that instant on its own.
     *
     * The line is built in one pass over loaded rows for speed; this pins that
     * the shortcut produces the same answers as asking the scorer sixty times
     * from scratch would.
     */
    @Test
    fun everyPointMatchesAScoreComputedIndependentlyForThatInstant() = runBlocking {
        val trackId = aTrackPlayed(onDaysAgo = listOf(3, 9, 21, 44, 70, 120))

        val history = service.scoreHistory(trackId, nowMs = now, days = 150, points = 12)
        history.forEach { point ->
            val independent = service.breakdown(trackId, nowMs = point.atMs)!!.score
            assertEquals(
                "the line disagrees with the scorer at ${point.atMs}",
                independent,
                point.score,
                1e-6,
            )
        }
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
    fun theLineIsFlatAtZeroBeforeTheFirstPlay() = runBlocking {
        val trackId = aTrackPlayed(onDaysAgo = listOf(10))

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
        val trackId = aTrackPlayed(onDaysAgo = listOf(2))

        val history = service.scoreHistory(trackId, nowMs = now, days = 90, points = 10)
        assertEquals(10, history.size)
        assertEquals(now - 90 * day, history.first().atMs)
        assertEquals(now, history.last().atMs)

        val gaps = history.zipWithNext { a, b -> b.atMs - a.atMs }.distinct()
        assertTrue("samples should be evenly spaced, found $gaps", gaps.size <= 2)
        history.zipWithNext { a, b -> assertTrue("oldest first", a.atMs < b.atMs) }
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
        val trackId = newTrack("Volatile", "Tester")
        // A full play 40 days ago, a skip 20 days ago, a full play 10 days ago.
        play(trackId, daysAgo = 40, fraction = 1.0)
        play(trackId, daysAgo = 20, fraction = 0.05)
        play(trackId, daysAgo = 10, fraction = 1.0)

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

    private suspend fun aTrackPlayed(onDaysAgo: List<Int>): Long {
        val trackId = newTrack("Sample", "Tester")
        onDaysAgo.forEach { play(trackId, daysAgo = it, fraction = 1.0) }
        return trackId
    }

    private suspend fun newTrack(title: String, artist: String): Long {
        val dao = db.dao()
        dao.recordSegment(
            trackKey = trackKeyOf(title, artist, "Album"),
            title = title,
            artist = artist,
            album = "Album",
            durationMs = DURATION,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:$title",
            segment = segment(now - 400 * day, 1.0),
        )
        val trackId = dao.allTracks().single().id
        // Liked, because only liked tracks are scored at all.
        val serviceRowId = dao.allServiceTracks().single().id
        db.spotifyDao().recordLiked(
            serviceRowId,
            serviceAddedAtMs = now - 300 * day,
            now = now - 300 * day,
        )
        return trackId
    }

    private suspend fun play(trackId: Long, daysAgo: Int, fraction: Double) {
        val dao = db.dao()
        val serviceRowId = dao.allServiceTracks().single().id
        dao.recordSegment(
            trackKey = trackKeyOf("Sample", "Tester", "Album"),
            title = "Sample",
            artist = "Tester",
            album = "Album",
            durationMs = DURATION,
            service = SERVICE_SPOTIFY,
            serviceTrackId = dao.allServiceTracks().single().serviceTrackId,
            segment = segment(now - daysAgo * day, fraction).copy(
                trackId = trackId,
                serviceTrackRowId = serviceRowId,
            ),
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
    }
}
