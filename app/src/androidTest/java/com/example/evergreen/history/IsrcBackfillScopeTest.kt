package com.example.evergreen.history

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
 * Test 14 of `docs/PHASE3_PLAN.md` section 9: imported-only service rows are
 * never queued for an ISRC lookup.
 *
 * **Why this matters enough to have a test of its own.** Spotify removed the
 * batch track endpoint in February 2026, so an ISRC now costs one request per
 * track. The import adds about 12,000 tracks that were played once, years ago,
 * and never liked. Left in the queue they would be five attempts each against a
 * per-developer-account quota that Spotify does not publish — tens of thousands
 * of requests to learn nothing about any song that could be in Evergreen.
 *
 * An imported-only row still gets its ISRC for free if the track is ever liked,
 * because the like sync carries full track objects.
 */
@RunWith(AndroidJUnit4::class)
class IsrcBackfillScopeTest {

    private lateinit var db: EvergreenDatabase

    private val now = 1_789_128_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun queue(): List<String> =
        db.spotifyDao().serviceTracksNeedingIsrc(
            service = SERVICE_SPOTIFY,
            trackUriPrefix = "spotify:track:",
            retryBefore = now,
            maxAttempts = 5,
            limit = 100,
        ).map { it.serviceTrackId }

    @Test
    fun anImportedOnlyRowIsNeverQueued() = runBlocking {
        HistoryImporter(db, now = { now }).importJsonFile(
            {
                """
                [{"ts":"2020-05-01T10:00:00Z","ms_played":200000,
                  "spotify_track_uri":"spotify:track:importedOnly",
                  "master_metadata_track_name":"Played Once In 2020",
                  "master_metadata_album_artist_name":"Someone",
                  "master_metadata_album_album_name":"An Album"}]
                """.trimIndent().byteInputStream()
            },
        )

        assertEquals("the row exists", 1, db.dao().allServiceTracks().size)
        assertTrue("but it must not be queued for a lookup", queue().isEmpty())
    }

    @Test
    fun aRowWithACapturedSegmentIsQueued() = runBlocking {
        db.dao().recordSegment(
            trackKey = trackKeyOf("Played Here", "Someone", "An Album"),
            title = "Played Here",
            artist = "Someone",
            album = "An Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:captured",
            segment = capturedSegment(),
        )

        assertEquals(listOf("spotify:track:captured"), queue())
    }

    @Test
    fun aLikedRowIsQueued() = runBlocking {
        val rowId = db.spotifyDao().resolveServiceTrack(
            trackKey = trackKeyOf("Liked", "Someone", "An Album"),
            title = "Liked",
            artist = "Someone",
            album = "An Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:liked",
            isrc = null,
            now = now,
        )
        db.spotifyDao().recordLiked(serviceTrackRowId = rowId, serviceAddedAtMs = null, now = now)

        assertEquals(listOf("spotify:track:liked"), queue())
    }

    /**
     * A track that was liked and later unliked stays in the archive, so it is
     * still worth an ISRC — the archive is the thing this app exists to keep.
     */
    @Test
    fun aRowThatWasLikedAndThenUnlikedIsStillQueued() = runBlocking {
        val rowId = db.spotifyDao().resolveServiceTrack(
            trackKey = trackKeyOf("Was Liked", "Someone", "An Album"),
            title = "Was Liked",
            artist = "Someone",
            album = "An Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:unliked",
            isrc = null,
            now = now,
        )
        db.spotifyDao().recordLiked(serviceTrackRowId = rowId, serviceAddedAtMs = null, now = now)
        // A later full sync no longer finds it, so it is marked unliked. The
        // row is never deleted; that is the whole point of the archive.
        db.spotifyDao().markUnlikedAfterFullSync(syncStartedAt = now + 1, now = now + 2)

        assertEquals(listOf("spotify:track:unliked"), queue())
    }

    /**
     * The mixed case, which is the real one: 12,000 imported-only rows and a
     * few hundred that matter. Only the latter may reach the network.
     */
    @Test
    fun onlyTheRowsWorthARequestAreQueued() = runBlocking {
        HistoryImporter(db, now = { now }).importJsonFile(
            {
                buildString {
                    append('[')
                    repeat(50) { index ->
                        if (index > 0) append(',')
                        append(
                            """{"ts":"2020-05-01T10:00:00Z","ms_played":200000,
                               "spotify_track_uri":"spotify:track:old%02d",
                               "master_metadata_track_name":"Old $index",
                               "master_metadata_album_artist_name":"Someone",
                               "master_metadata_album_album_name":"Old Album"}"""
                                .trimIndent().format(index),
                        )
                    }
                    append(']')
                }.byteInputStream()
            },
        )
        db.dao().recordSegment(
            trackKey = trackKeyOf("Played Here", "Someone", "An Album"),
            title = "Played Here",
            artist = "Someone",
            album = "An Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:captured",
            segment = capturedSegment(),
        )

        assertEquals(51, db.dao().allServiceTracks().size)
        assertEquals(listOf("spotify:track:captured"), queue())
    }

    /**
     * The Phase 2 rule this must not have undone: DJ commentary arrives as
     * `spotify:media:` and no track endpoint will ever resolve it.
     */
    @Test
    fun djCommentaryIsStillExcluded() = runBlocking {
        db.dao().recordSegment(
            trackKey = trackKeyOf("Up next", "DJ X", "DJ"),
            title = "Up next",
            artist = "DJ X",
            album = "DJ",
            durationMs = 0,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:media:2j7w4Q2yMxa9ekzCdGhKUc",
            segment = capturedSegment().copy(listenedMs = 0, trackDurationMs = 0),
        )

        assertTrue(queue().isEmpty())
    }

    private fun capturedSegment() = ListeningSegmentEntity(
        trackId = 0,
        serviceTrackRowId = null,
        appPackage = "com.spotify.music",
        deviceId = "test-device",
        startedAtMs = now - 200_000,
        endedAtMs = now,
        startPositionMs = 0,
        endPositionMs = 200_000,
        listenedMs = 200_000,
        trackDurationMs = 200_000,
        forwardSeekCount = 0,
        seekedForwardMs = 0,
        endReason = EndReason.TRACK_CHANGE,
        endJumpFromMs = null,
        endJumpToMs = null,
        lastSampleAgeMs = 100,
        createdAt = now,
    )
}
