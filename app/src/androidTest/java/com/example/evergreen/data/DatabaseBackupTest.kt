package com.example.evergreen.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The backup button's one promise: the file it writes can be read back.
 *
 * A backup that cannot be restored is worse than no backup, because it is
 * believed. The interesting case is a write that has not been checkpointed out
 * of the write-ahead log yet — copying the database file alone would miss it,
 * which is why this uses SQLite's `VACUUM INTO` and why the test writes a row
 * immediately before backing up.
 *
 * Runs against the app's real database file, because that is what the button
 * copies. Instrumented tests destroy that database anyway.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseBackupTest {

    private lateinit var context: Context
    private lateinit var destination: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        destination = File(context.cacheDir, "backup-test-output.db").also { it.delete() }
    }

    @After
    fun tearDown() {
        destination.delete()
    }

    @Test
    fun theBackupIsAReadableDatabaseHoldingTheRowsJustWritten() = runBlocking {
        val db = EvergreenDatabase.get(context)
        val marker = "backup-probe-${System.nanoTime()}"
        db.dao().recordSegment(
            trackKey = trackKeyOf(marker, "Someone", "An Album"),
            title = marker,
            artist = "Someone",
            album = "An Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:$marker",
            segment = ListeningSegmentEntity(
                trackId = 0,
                serviceTrackRowId = null,
                appPackage = "com.spotify.music",
                deviceId = "test-device",
                startedAtMs = 1_700_000_000_000,
                endedAtMs = 1_700_000_200_000,
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
                createdAt = 1_700_000_200_000,
            ),
        )

        val bytes = DatabaseBackup.writeTo(context, Uri.fromFile(destination))

        assertTrue("backup should not be empty", bytes > 0)
        assertTrue(destination.exists())

        // Opened as a plain SQLite file, with no help from Room: this is what
        // restoring actually looks like.
        val restored = SQLiteDatabase.openDatabase(
            destination.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            restored.rawQuery(
                "SELECT COUNT(*) FROM tracks WHERE title = ?",
                arrayOf(marker),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "the row written just before the backup must be in it",
                    1,
                    cursor.getInt(0),
                )
            }
            // A self-contained snapshot: every table is there, not just the one.
            listOf(
                "tracks", "service_tracks", "listening_segments",
                "likes", "like_events", "sync_state", "track_merges",
            ).forEach { table ->
                restored.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                    assertTrue("$table missing from the backup", cursor.moveToFirst())
                }
            }
        } finally {
            restored.close()
        }
    }

    /** An interrupted run must not leave a snapshot that blocks the next backup. */
    @Test
    fun backingUpTwiceInARowWorks() = runBlocking {
        DatabaseBackup.writeTo(context, Uri.fromFile(destination))
        val second = DatabaseBackup.writeTo(context, Uri.fromFile(destination))

        assertTrue(second > 0)
        assertTrue(
            "the temporary snapshot must be cleaned up",
            !File(context.cacheDir, "evergreen-backup-snapshot.db").exists(),
        )
    }

    @Test
    fun theSuggestedNameSortsChronologicallyAndSaysWhatItIs() {
        val name = DatabaseBackup.suggestedFileName(1_789_128_000_000)

        assertTrue(name, name.startsWith("evergreen-backup-"))
        assertTrue(name, name.endsWith(".db"))
        assertTrue(name, name.contains("2026-09-"))
    }
}
