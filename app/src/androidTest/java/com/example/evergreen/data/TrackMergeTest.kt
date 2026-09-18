package com.example.evergreen.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executing a same-song merge against a real database.
 *
 * The claim under test is the one the whole schema-v2 foreign-key change exists
 * to support: **merging re-points listening history, it never deletes it.**
 */
@RunWith(AndroidJUnit4::class)
class TrackMergeTest {

    private lateinit var db: EvergreenDatabase
    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            EvergreenDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * The real pair from the user's library, with listening history added to
     * both sides -- which the live data did not have, and which is the case
     * where a mistake would actually cost something.
     */
    @Test
    fun mergingMovesSegmentsFromBothSidesAndDeletesNone() = runBlocking {
        val single = seed("Dracula", "Dracula", "spotify:track:single", segments = 3)
        val album = seed("Dracula", "Deadbeat", "spotify:track:album", segments = 5)

        val before = db.dao().allSegments().size
        assertEquals(8, before)

        val record = db.spotifyDao().mergeTracks(
            survivorId = album,
            loserId = single,
            isrc = "USQX92504223",
            now = t0,
        )

        assertEquals("every segment survives", 8, db.dao().allSegments().size)
        assertEquals("all now on one track", 8, db.dao().allSegments().count { it.trackId == album })
        assertEquals("one canonical track remains", 1, db.dao().allTracks().size)
        assertEquals(3, record.segmentsMoved)
        assertEquals(1, record.serviceRowsMoved)
    }

    /** Both Spotify URIs must survive; they are what a segment identifies by. */
    @Test
    fun bothServiceIdentifiersSurviveAndPointAtTheSurvivor() = runBlocking {
        val single = seed("Dracula", "Dracula", "spotify:track:single", segments = 1)
        val album = seed("Dracula", "Deadbeat", "spotify:track:album", segments = 1)

        db.spotifyDao().mergeTracks(album, single, "USQX92504223", t0)

        val rows = db.dao().allServiceTracks()
        assertEquals(2, rows.size)
        assertTrue("both now belong to the survivor", rows.all { it.trackId == album })
        assertNotNull(rows.firstOrNull { it.serviceTrackId == "spotify:track:single" })
        assertNotNull(rows.firstOrNull { it.serviceTrackId == "spotify:track:album" })
    }

    /**
     * A segment must never end up pointing at a track that no longer exists,
     * nor at a service row belonging to a different track.
     */
    @Test
    fun noSegmentIsLeftDanglingAfterAMerge() = runBlocking {
        val single = seed("Dracula", "Dracula", "spotify:track:single", segments = 2)
        val album = seed("Dracula", "Deadbeat", "spotify:track:album", segments = 2)

        db.spotifyDao().mergeTracks(album, single, "USQX92504223", t0)

        assertEquals(
            "no segment disagrees with its service row",
            0,
            db.dao().mismatchedTrackIdCountOnce(),
        )
        val liveIds = db.dao().allTracks().map { it.id }.toSet()
        assertTrue(db.dao().allSegments().all { it.trackId in liveIds })
    }

    /** Likes belong to service rows, so both survive a merge untouched. */
    @Test
    fun likesAreUnaffectedByAMerge() = runBlocking {
        val single = seed("Dracula", "Dracula", "spotify:track:single", segments = 1)
        val album = seed("Dracula", "Deadbeat", "spotify:track:album", segments = 1)
        db.dao().allServiceTracks().forEach {
            db.spotifyDao().recordLiked(it.id, serviceAddedAtMs = t0, now = t0)
        }

        db.spotifyDao().mergeTracks(album, single, "USQX92504223", t0)

        assertEquals("both releases are still liked", 2, db.spotifyDao().archivedLikeCountOnce())
    }

    /** The merge has to remain explainable after the losing row is gone. */
    @Test
    fun theMergeIsRecordedWithWhatWasAbsorbed() = runBlocking {
        val single = seed("Dracula", "Dracula", "spotify:track:single", segments = 4)
        val album = seed("Dracula", "Deadbeat", "spotify:track:album", segments = 1)

        db.spotifyDao().mergeTracks(album, single, "USQX92504223", t0)

        val record = db.spotifyDao().allMerges().single()
        assertEquals(album, record.survivorTrackId)
        assertEquals("Dracula", record.mergedAlbum)
        assertEquals("USQX92504223", record.isrc)
        assertEquals(4, record.segmentsMoved)
        assertEquals(t0, record.mergedAt)
    }

    @Test
    fun aTrackCannotBeMergedIntoItself() = runBlocking {
        val track = seed("Dracula", "Dracula", "spotify:track:single", segments = 1)

        val threw = runCatching {
            db.spotifyDao().mergeTracks(track, track, "USQX92504223", t0)
        }.isFailure

        assertTrue(threw)
        assertEquals(1, db.dao().allTracks().size)
    }

    /**
     * End to end: plan from the dry-run query, then execute the plan.
     */
    @Test
    fun theDryRunPlanIsWhatActuallyGetsExecuted() = runBlocking {
        seed("Dracula", "Dracula", "spotify:track:s1", segments = 2, isrc = "USQX92504223")
        seed("Dracula", "Deadbeat", "spotify:track:s2", segments = 7, isrc = "USQX92504223")
        seed("Unrelated", "Other", "spotify:track:s3", segments = 1, isrc = "AAA111111111")

        val plan = SameSongMerge.plan(db.spotifyDao().sameIsrcMergeCandidates())
        assertEquals(1, plan.size)
        val group = plan.single()

        group.losers.forEach { loser ->
            db.spotifyDao().mergeTracks(group.survivor.trackId, loser.trackId, group.isrc, t0)
        }

        assertEquals("all ten segments intact", 10, db.dao().allSegments().size)
        assertEquals("the unrelated track is untouched", 2, db.dao().allTracks().size)
        assertTrue(
            "nothing is left to merge",
            SameSongMerge.plan(db.spotifyDao().sameIsrcMergeCandidates()).isEmpty(),
        )
    }

    /** Creates a canonical track with one service id and [segments] segments. */
    private suspend fun seed(
        title: String,
        album: String,
        uri: String,
        segments: Int,
        isrc: String? = null,
    ): Long {
        repeat(segments) { i ->
            db.dao().recordSegment(
                trackKey = trackKeyOf(title, "An Artist", album),
                title = title,
                artist = "An Artist",
                album = album,
                durationMs = 200_000,
                service = SERVICE_SPOTIFY,
                serviceTrackId = uri,
                segment = ListeningSegmentEntity(
                    trackId = 0,
                    serviceTrackRowId = null,
                    appPackage = "com.spotify.music",
                    deviceId = "test-device",
                    startedAtMs = t0 + i * 1000L,
                    endedAtMs = t0 + i * 1000L + 500,
                    startPositionMs = 0,
                    endPositionMs = 100_000,
                    listenedMs = 100_000,
                    trackDurationMs = 200_000,
                    forwardSeekCount = 0,
                    seekedForwardMs = 0,
                    endReason = EndReason.TRACK_CHANGE,
                    endJumpFromMs = null,
                    endJumpToMs = null,
                    lastSampleAgeMs = 0,
                    createdAt = t0,
                ),
            )
        }
        val row = db.dao().allServiceTracks().first { it.serviceTrackId == uri }
        if (isrc != null) db.spotifyDao().setIsrc(row.id, isrc, t0)
        return row.trackId
    }
}
