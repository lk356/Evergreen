package com.example.evergreen.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [EvergreenDao.recordSegment] against a real SQLite database.
 *
 * These are instrumented tests rather than plain JVM ones because the behaviour
 * under test *is* the database behaviour — unique indexes, conflict handling and
 * transaction boundaries. Faking those would test the fake.
 */
@RunWith(AndroidJUnit4::class)
class EvergreenDaoTest {

    private lateinit var db: EvergreenDatabase
    private lateinit var dao: EvergreenDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            // Test-only: runBlocking below runs on the instrumentation thread.
            // The app itself never does this — SegmentWriter keeps every write
            // on a single background dispatcher.
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun segment(listenedMs: Long = 10_000) = ListeningSegmentEntity(
        trackId = 0,
        serviceTrackRowId = null,
        appPackage = "com.spotify.music",
        deviceId = "test-device",
        startedAtMs = 1_700_000_000_000,
        endedAtMs = 1_700_000_010_000,
        startPositionMs = 0,
        endPositionMs = listenedMs,
        listenedMs = listenedMs,
        trackDurationMs = 180_000,
        forwardSeekCount = 0,
        seekedForwardMs = 0,
        endReason = EndReason.TRACK_CHANGE,
        endJumpFromMs = null,
        endJumpToMs = null,
        lastSampleAgeMs = 0,
        createdAt = 1_700_000_010_000,
    )

    private suspend fun record(
        title: String,
        album: String,
        serviceTrackId: String?,
        artist: String = "Test Artist",
    ) = dao.recordSegment(
        trackKey = trackKeyOf(title, artist, album),
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000,
        service = SERVICE_SPOTIFY,
        serviceTrackId = serviceTrackId,
        segment = segment(),
    )

    @Test
    fun sameUriWithDifferentMetadataStaysOneCanonicalTrack() = runBlocking {
        val uri = "spotify:track:AAA"

        record(title = "Song", album = "Album", serviceTrackId = uri)
        // Same recording, but Spotify now reports it with a suffix and a
        // renamed album — exactly what used to fork the canonical track.
        record(title = "Song (Remastered)", album = "Album (Deluxe)", serviceTrackId = uri)

        val tracks = dao.allTracks()
        assertEquals("a known URI must not create a second canonical track", 1, tracks.size)

        val serviceRows = dao.allServiceTracks()
        assertEquals(1, serviceRows.size)

        val segments = dao.allSegments()
        assertEquals(2, segments.size)
        assertTrue(
            "both segments should point at the one canonical track",
            segments.all { it.trackId == tracks.single().id },
        )
        assertTrue(
            "every segment's trackId must match its service row's trackId",
            segments.all { it.serviceTrackRowId == serviceRows.single().id },
        )
        // The title stays as first seen rather than churning on every wobble.
        assertEquals("Song", tracks.single().title)
    }

    @Test
    fun withoutAServiceIdTheTrackKeyFallbackStillWorks() = runBlocking {
        record(title = "Song", album = "Album", serviceTrackId = null)
        record(title = "Song", album = "Album", serviceTrackId = null)
        // Different song, no URI available either.
        record(title = "Other", album = "Album", serviceTrackId = null)

        val tracks = dao.allTracks()
        assertEquals(2, tracks.size)
        assertEquals(0, dao.allServiceTracks().size)

        val segments = dao.allSegments()
        assertEquals(3, segments.size)
        assertTrue(
            "segments with no service id carry no service row",
            segments.all { it.serviceTrackRowId == null },
        )
    }

    @Test
    fun aNewUriWithAKnownTrackKeyLinksToTheExistingTrack() = runBlocking {
        record(title = "Song", album = "Album", serviceTrackId = "spotify:track:AAA")
        // A different Spotify URI for metadata we have already seen — the album
        // version and the single version, say.
        record(title = "Song", album = "Album", serviceTrackId = "spotify:track:BBB")

        val tracks = dao.allTracks()
        assertEquals("identical metadata should reuse the canonical track", 1, tracks.size)

        val serviceRows = dao.allServiceTracks()
        assertEquals("but each URI gets its own service row", 2, serviceRows.size)
        assertTrue(
            "both service rows hang off the same canonical track",
            serviceRows.all { it.trackId == tracks.single().id },
        )
    }

    @Test
    fun trackIdAndServiceRowNeverDisagree() = runBlocking {
        // The invariant the diagnostic query checks for, across a mixture of
        // the cases above.
        record(title = "Song", album = "Album", serviceTrackId = "spotify:track:AAA")
        record(title = "Song (Remastered)", album = "Other", serviceTrackId = "spotify:track:AAA")
        record(title = "Song", album = "Album", serviceTrackId = "spotify:track:BBB")
        record(title = "Third", album = "Album", serviceTrackId = null)

        val serviceRowsById = dao.allServiceTracks().associateBy { it.id }
        for (s in dao.allSegments()) {
            val rowId = s.serviceTrackRowId ?: continue
            val serviceRow = serviceRowsById[rowId]
            assertNotNull("segment ${s.id} points at a missing service row", serviceRow)
            assertEquals(
                "segment ${s.id} disagrees with its service row",
                serviceRow!!.trackId,
                s.trackId,
            )
        }
    }

    @Test
    fun isrcStaysNullInPhaseOne() = runBlocking {
        record(title = "Song", album = "Album", serviceTrackId = "spotify:track:AAA")

        assertEquals(null, dao.allServiceTracks().single().isrc)
    }
}
