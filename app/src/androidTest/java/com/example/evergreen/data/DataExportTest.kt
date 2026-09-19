package com.example.evergreen.data

import android.content.Context
import androidx.room.Room
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
 * The readable export.
 *
 * Two things can go wrong with a CSV of someone's music library, and both are
 * silent: a title containing a comma or a quotation mark shifts every column
 * after it, and a blank that should mean "not observed" gets written as a zero
 * that reads as a measurement. Neither shows up until the file is opened
 * somewhere else, which is the one place this app cannot check.
 */
@RunWith(AndroidJUnit4::class)
class DataExportTest {

    private lateinit var db: EvergreenDatabase
    private lateinit var context: Context

    private val now = 1_789_214_400_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * Every field is quoted, and quotation marks inside one are doubled.
     *
     * Not a hypothetical: album titles routinely contain commas, and track
     * titles contain quotation marks. A rule that quotes only when it looks
     * necessary is a rule with an exception waiting in somebody's library, and
     * the failure shifts every column to its right without any error at all.
     */
    @Test
    fun everyFieldIsQuotedAndInnerQuotesAreDoubled() {
        assertEquals(
            "\"a\",\"b\"\r\n",
            DataExport.csvRow(listOf("a", "b")),
        )
        assertEquals(
            "\"Smith, Jane\",\"Live\"\r\n",
            DataExport.csvRow(listOf("Smith, Jane", "Live")),
        )
        assertEquals(
            "\"He said \"\"no\"\"\"\r\n",
            DataExport.csvRow(listOf("He said \"no\"")),
        )
        // A newline inside a field is legal CSV precisely because the field is
        // quoted, and some track titles really do contain one.
        assertEquals(
            "\"two\nlines\"\r\n",
            DataExport.csvRow(listOf("two\nlines")),
        )
        assertEquals("\"\"\r\n", DataExport.csvRow(listOf("")))
    }

    @Test
    fun theFileNameSaysWhatItIsAndWhen() {
        val name = DataExport.suggestedFileName(DataExport.Kind.LISTENING, now)
        assertTrue(name.startsWith("evergreen-listening-"))
        assertTrue(name.endsWith(".csv"))
        assertTrue(
            "exporting twice should give two files, so the name carries the moment",
            name != DataExport.suggestedFileName(DataExport.Kind.LISTENING, now + 60_000 * 60),
        )
        assertTrue(
            DataExport.suggestedFileName(DataExport.Kind.RANKINGS, now)
                .startsWith("evergreen-rankings-"),
        )
    }

    /** An empty database writes a header and no rows, rather than nothing at all. */
    @Test
    fun anEmptyDatabaseStillWritesItsHeaders() = runBlocking {
        val listening = exportToFile(DataExport.Kind.LISTENING)
        assertEquals(0, listening.second)
        assertTrue(listening.first.lineSequence().first().contains("started_at"))

        val rankings = exportToFile(DataExport.Kind.RANKINGS)
        assertEquals(0, rankings.second)
        assertTrue(rankings.first.lineSequence().first().contains("rank"))
    }

    /**
     * A title full of the characters that break a CSV survives the round trip.
     *
     * The escaping is unit-tested on its own; this is the end-to-end version,
     * because the way to get it wrong in practice is not the escaping function
     * but forgetting to route a field through it.
     */
    @Test
    fun aTitleFullOfCommasAndQuotesComesBackIntact() = runBlocking {
        val awkward = "Hello, \"World\" — Pt. 1"
        recordSegment(title = awkward, artist = "A, B & C")

        val (text, rows) = exportToFile(DataExport.Kind.LISTENING)
        assertEquals(1, rows)

        // Two lines of content, whatever the title contains.
        val lines = text.trim().split("\r\n")
        assertEquals(2, lines.size)
        assertTrue(
            "the title should appear with its inner quotes doubled",
            lines[1].contains("\"Hello, \"\"World\"\" — Pt. 1\""),
        )
        // And the same number of fields as the header, which is what a stray
        // unescaped comma would break.
        assertEquals(countFields(lines[0]), countFields(lines[1]))
    }

    // --- Fixtures ------------------------------------------------------------

    /**
     * Writes an export to a real file and hands back its text and row count.
     *
     * Passes the test's own in-memory database rather than letting [DataExport]
     * resolve the app's real one, which is what this test used to do without
     * saying so.
     */
    private suspend fun exportToFile(kind: DataExport.Kind): Pair<String, Int> {
        val file = File(context.cacheDir, "export-test-${kind.label}.csv")
        file.delete()
        val uri = android.net.Uri.fromFile(file)
        val rows = DataExport.writeTo(db, context.contentResolver, uri, kind)
        return file.readText() to rows
    }

    private suspend fun recordSegment(title: String, artist: String) {
        db.dao().recordSegment(
            trackKey = trackKeyOf(title, artist, "Album"),
            title = title,
            artist = artist,
            album = "Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:awkward",
            segment = ListeningSegmentEntity(
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
            ),
        )
    }

    /**
     * Fields in a fully-quoted CSV line.
     *
     * Every field is wrapped, so the separator between two of them is always
     * the three characters `","` — which a comma *inside* a field is not.
     */
    private fun countFields(line: String): Int =
        Regex("\",\"").findAll(line).count() + 1
}
