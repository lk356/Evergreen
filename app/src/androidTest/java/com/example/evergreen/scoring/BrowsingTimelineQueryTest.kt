package com.example.evergreen.scoring

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.data.EndReason
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.PlaybackType
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.trackKeyOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The timeline query behind browsing runs (scoring freshness, change B),
 * against real SQLite: every track in the window, other-device rows left out,
 * and DJ commentary flagged rather than dropped.
 */
@RunWith(AndroidJUnit4::class)
class BrowsingTimelineQueryTest {

    private lateinit var db: EvergreenDatabase

    /** 2026-09-17T12:00:00Z. */
    private val now = 1_789_128_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun record(title: String, uri: String, endedAt: Long, playbackType: PlaybackType? = null) {
        db.dao().recordSegment(
            trackKey = trackKeyOf(title, "Artist", "Album"),
            title = title,
            artist = "Artist",
            album = "Album",
            durationMs = 0,
            service = SERVICE_SPOTIFY,
            serviceTrackId = uri,
            segment = ListeningSegmentEntity(
                trackId = 0,
                serviceTrackRowId = null,
                appPackage = "com.spotify.music",
                deviceId = "walkman",
                startedAtMs = endedAt - 2_000,
                endedAtMs = endedAt,
                startPositionMs = 0,
                endPositionMs = 2_000,
                listenedMs = 2_000,
                trackDurationMs = 0,
                forwardSeekCount = 0,
                seekedForwardMs = 0,
                endReason = EndReason.TRACK_CHANGE,
                endJumpFromMs = null,
                endJumpToMs = null,
                lastSampleAgeMs = 100,
                createdAt = endedAt,
                playbackType = playbackType,
            ),
        )
    }

    @Test
    fun theTimelineHoldsEveryTrackButNotPhantomsOrOldRows() = runBlocking {
        // An unliked song, a liked-or-not song, commentary, a phantom, and a
        // row older than the window.
        record("Song A", "spotify:track:a", now - 3_000)
        record("Song B", "spotify:track:b", now - 6_000, PlaybackType.LOCAL)
        record("DJ", "spotify:media:dj", now - 9_000)
        record("Phantom", "spotify:track:c", now - 12_000, PlaybackType.REMOTE)
        record("Old", "spotify:track:d", now - 500 * day)

        val timeline = db.scoringDao()
            .timelineForBrowsing(TrackScorer.SPOTIFY_TRACK_PREFIX, fromMs = now - 450 * day)
            .sortedBy { it.endedAtMs }

        assertEquals("A, B and the commentary; no phantom, nothing old", 3, timeline.size)
        assertEquals(listOf(true, false, false), timeline.map { it.isCommentary })
        assertEquals(listOf("walkman"), timeline.map { it.timeline }.distinct())
        assertEquals(2_000L, timeline.first().listenedMs)
    }
}
