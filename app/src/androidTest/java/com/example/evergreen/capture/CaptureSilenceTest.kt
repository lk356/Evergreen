package com.example.evergreen.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.data.EndReason
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.SegmentSource
import com.example.evergreen.data.trackKeyOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * "Has capture gone quiet?" — the question, not the notification.
 *
 * The rule the watchdog applies is one query and two comparisons, and every
 * way it could be wrong is a way the user is either nagged or left in the dark
 * about the one failure that loses data outright. So the query is tested here
 * against a real database rather than inferred from the worker.
 */
@RunWith(AndroidJUnit4::class)
class CaptureSilenceTest {

    private lateinit var db: EvergreenDatabase

    /** 2026-09-18T12:00:00Z. */
    private val now = 1_789_214_400_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aDatabaseWithNothingCapturedHasNoLastCapture() = runBlocking {
        assertNull(db.dao().lastCapturedAtMsOnce())
    }

    @Test
    fun theLastCaptureIsTheMostRecentCapturedSegment() = runBlocking {
        record(endedAtMs = now - 10 * day, source = SegmentSource.CAPTURE)
        record(endedAtMs = now - 2 * day, source = SegmentSource.CAPTURE)
        record(endedAtMs = now - 6 * day, source = SegmentSource.CAPTURE)

        assertEquals(now - 2 * day, db.dao().lastCapturedAtMsOnce())
    }

    /**
     * An import must not look like capture still working.
     *
     * The rows in a Spotify export carry real listening times, some of them
     * recent, so counting them here would let one import silence the watchdog
     * for as long as the export reaches — which is precisely the window in
     * which capture breaking would go unnoticed. This is the single most
     * important line in the query.
     */
    @Test
    fun anImportedRowIsNotEvidenceThatCaptureIsWorking() = runBlocking {
        record(endedAtMs = now - 30 * day, source = SegmentSource.CAPTURE)
        record(endedAtMs = now - 1 * day, source = SegmentSource.IMPORT_SPOTIFY_HISTORY)

        assertEquals(
            "yesterday's imported row must not count as capture",
            now - 30 * day,
            db.dao().lastCapturedAtMsOnce(),
        )
    }

    @Test
    fun aDatabaseOfNothingButImportedRowsStillHasNoLastCapture() = runBlocking {
        record(endedAtMs = now - 1 * day, source = SegmentSource.IMPORT_SPOTIFY_HISTORY)
        assertNull(db.dao().lastCapturedAtMsOnce())
    }

    @Test
    fun theRecentCountIgnoresImportedRowsToo() = runBlocking {
        record(endedAtMs = now - 2 * day, source = SegmentSource.CAPTURE)
        record(endedAtMs = now - 3 * day, source = SegmentSource.IMPORT_SPOTIFY_HISTORY)
        record(endedAtMs = now - 40 * day, source = SegmentSource.CAPTURE)

        assertEquals(1, db.dao().capturedSinceCount(now - 7 * day).first())
    }

    /**
     * The repeat rule: one notification per quiet stretch, not one a day.
     *
     * The marker is compared against the last capture rather than against the
     * clock, which is what makes it re-arm on its own the moment listening
     * resumes. There is deliberately no reset step to forget.
     */
    @Test
    fun theRepeatRuleIsRelativeToTheLastCaptureRatherThanToTheClock() {
        val lastCapture = now - 9 * day

        // Never warned about this stretch: warn.
        assertTrue(shouldWarn(notifiedAt = null, lastCapture = lastCapture))
        // Already warned about this stretch: stay quiet.
        assertTrue(!shouldWarn(notifiedAt = now - day, lastCapture = lastCapture))
        // Warned during an *earlier* stretch, then listening resumed and
        // stopped again: warn, because the marker predates the newer capture.
        assertTrue(shouldWarn(notifiedAt = now - 40 * day, lastCapture = lastCapture))
    }

    /** The same rule as the worker applies, kept in one place so it cannot drift. */
    private fun shouldWarn(notifiedAt: Long?, lastCapture: Long): Boolean =
        notifiedAt == null || notifiedAt <= lastCapture

    // --- Fixtures ------------------------------------------------------------

    private suspend fun record(endedAtMs: Long, source: SegmentSource) {
        db.dao().recordSegment(
            trackKey = trackKeyOf("Track", "Artist", "Album"),
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:abc",
            segment = ListeningSegmentEntity(
                trackId = 0,
                serviceTrackRowId = null,
                appPackage = "com.spotify.music",
                deviceId = "test-device",
                startedAtMs = endedAtMs - 200_000,
                endedAtMs = endedAtMs,
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
                createdAt = endedAtMs,
                source = source,
            ),
        )
    }
}
