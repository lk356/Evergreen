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

    // --- Fixtures ------------------------------------------------------------

    /** Writes an export to a real file and hands back its text and row count. */
    private suspend fun exportToFile(kind: DataExport.Kind): Pair<String, Int> {
        val file = File(context.cacheDir, "export-test-${kind.label}.csv")
        file.delete()
        val uri = android.net.Uri.fromFile(file)
        val rows = DataExport.writeTo(context, uri, kind)
        return file.readText() to rows
    }
}
