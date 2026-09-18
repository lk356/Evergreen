package com.example.evergreen.history

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
import com.example.evergreen.scoring.ScoringCurve
import com.example.evergreen.scoring.ScoringParameters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Test 13 of `docs/PHASE3_PLAN.md` section 9: rows land as segments; a second
 * run is a no-op; an overlapping captured segment wins; a track liked after the
 * import gets a duration and starts scoring without re-import.
 *
 * Every fixture is synthetic. No real export row appears in the repository.
 */
@RunWith(AndroidJUnit4::class)
class HistoryImportTest {

    private lateinit var db: EvergreenDatabase
    private lateinit var importer: HistoryImporter

    private val fixedNow = 1_789_128_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = HistoryImporter(db, now = { fixedNow })
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Fixtures -------------------------------------------------------------

    private fun historyJson(vararg rows: String) = "[${rows.joinToString(",")}]"

    private fun row(
        ts: String,
        msPlayed: Long,
        uri: String = TRACK_URI,
        name: String? = "Metadata",
        artist: String? = "Willix",
        album: String? = "Metadata",
        reasonEnd: String? = "trackdone",
    ) = """
        {"ts":"$ts","ms_played":$msPlayed,"spotify_track_uri":${uri.quotedOrNull()},
         "master_metadata_track_name":${name.quotedOrNull()},
         "master_metadata_album_artist_name":${artist.quotedOrNull()},
         "master_metadata_album_album_name":${album.quotedOrNull()},
         "reason_end":${reasonEnd.quotedOrNull()},
         "ip_addr":"203.0.113.42","conn_country":"GB","platform":"android"}
    """.trimIndent()

    private fun String?.quotedOrNull() = if (this == null) "null" else "\"$this\""

    private fun streamOf(json: String): () -> InputStream = { json.byteInputStream() }

    private fun zipOf(vararg entries: Pair<String, String>): () -> InputStream {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        return { bytes.inputStream() }
    }

    // --- Rows land as segments -----------------------------------------------

    @Test
    fun importedRowsBecomeOrdinarySegments() = runBlocking {
        val result = importer.importJsonFile(
            streamOf(historyJson(row("2024-03-01T20:15:30Z", 214_000))),
        )

        assertEquals(1, result.segmentsWritten)
        assertEquals(1, result.rowsRead)

        val segment = db.dao().allSegments().single()
        assertEquals(1_709_324_130_000L, segment.endedAtMs)
        assertEquals(1_709_323_916_000L, segment.startedAtMs)
        assertEquals(214_000L, segment.listenedMs)
        assertEquals(SegmentSource.IMPORT_SPOTIFY_HISTORY, segment.source)
        assertEquals(EndReason.IMPORTED, segment.endReason)
        assertEquals("trackdone", segment.sourceEndReason)
        assertEquals(HistoryImporter.IMPORT_DEVICE_ID, segment.deviceId)

        // What the export cannot say is left null, not filled with a zero.
        assertNull(segment.startPositionMs)
        assertNull(segment.endPositionMs)
        assertNull(segment.forwardSeekCount)
        assertNull(segment.seekedForwardMs)
        assertNull(segment.lastSampleAgeMs)

        // Resolved through the same path capture uses, so it has a canonical
        // track and a service row like any other segment.
        val track = db.dao().allTracks().single()
        assertEquals("Metadata", track.title)
        assertEquals(track.id, segment.trackId)
        val serviceRow = db.dao().allServiceTracks().single()
        assertEquals(TRACK_URI, serviceRow.serviceTrackId)
        assertEquals(serviceRow.id, segment.serviceTrackRowId)
    }

    /** A known URI joins the canonical track capture already created for it. */
    @Test
    fun anImportedRowJoinsTheTrackCaptureAlreadyKnows() = runBlocking {
        captureSegment(startedAtMs = 1_600_000_000_000, endedAtMs = 1_600_000_200_000)

        importer.importJsonFile(streamOf(historyJson(row("2024-03-01T20:15:30Z", 214_000))))

        assertEquals("one canonical track, not two", 1, db.dao().allTracks().size)
        assertEquals(1, db.dao().allServiceTracks().size)
        assertEquals(2, db.dao().allSegments().size)
        assertEquals(0, db.dao().mismatchedTrackIdCountOnce())
    }

    @Test
    fun rowsWithoutATrackUriAreSkippedAndCounted() = runBlocking {
        val result = importer.importJsonFile(
            streamOf(
                historyJson(
                    row("2024-03-01T20:15:30Z", 214_000),
                    """{"ts":"2024-03-01T21:00:00Z","ms_played":900000,
                        "spotify_track_uri":null,"episode_name":"A Podcast"}""",
                ),
            ),
        )

        assertEquals(1, result.segmentsWritten)
        assertEquals(1, result.rowsWithoutTrackUri)
        assertEquals(1, db.dao().allSegments().size)
    }

    // --- Idempotence ----------------------------------------------------------

    /** The plan's phrase: "running it twice changes nothing". */
    @Test
    fun runningTheSameFileTwiceChangesNothing() = runBlocking {
        val json = historyJson(
            row("2024-03-01T20:15:30Z", 214_000),
            row("2024-03-02T20:15:30Z", 100_000),
        )

        val first = importer.importJsonFile(streamOf(json))
        val second = importer.importJsonFile(streamOf(json))

        assertEquals(2, first.segmentsWritten)
        assertEquals(1, first.filesRead)
        assertEquals(0, second.segmentsWritten)
        assertEquals("the file hash should have stopped it", 1, second.filesSkippedAlreadyImported)
        assertEquals(2, db.dao().allSegments().size)
    }

    /**
     * The second defence, for when the hash does not match but the plays are
     * the same — importing a JSON file extracted from a zip already imported,
     * or a re-export that overlaps the previous one.
     */
    @Test
    fun theSamePlayArrivingInADifferentFileIsNotImportedTwice() = runBlocking {
        val original = historyJson(row("2024-03-01T20:15:30Z", 214_000))
        // Same play, different file: one extra row makes the hash differ.
        val overlapping = historyJson(
            row("2024-03-01T20:15:30Z", 214_000),
            row("2024-03-05T10:00:00Z", 180_000),
        )

        importer.importJsonFile(streamOf(original))
        val second = importer.importJsonFile(streamOf(overlapping))

        assertEquals(0, second.filesSkippedAlreadyImported)
        assertEquals("only the genuinely new play", 1, second.segmentsWritten)
        assertEquals(1, second.rowsAlreadyImported)
        assertEquals(2, db.dao().allSegments().size)
    }

    /** Identity is by content, so the same export under a new name is the same file. */
    @Test
    fun aFileIsIdentifiedByItsContentsNotItsName() = runBlocking {
        val json = historyJson(row("2024-03-01T20:15:30Z", 214_000))

        importer.importJsonFile(streamOf(json))
        // A byte-identical stream from "another file".
        val again = importer.importJsonFile(streamOf(json))

        assertEquals(1, again.filesSkippedAlreadyImported)
    }

    // --- Capture wins over the export ----------------------------------------

    /**
     * Where the two records overlap, capture is the better one: it knows the
     * position reached, the seeks and the replays, where the export knows only
     * a duration.
     */
    @Test
    fun anOverlappingCapturedSegmentWins() = runBlocking {
        // Capture saw this track from 20:14:00 to 20:16:00 on 2024-03-01.
        captureSegment(startedAtMs = 1_709_324_040_000, endedAtMs = 1_709_324_160_000)

        val result = importer.importJsonFile(
            streamOf(historyJson(row("2024-03-01T20:15:30Z", 214_000))),
        )

        assertEquals(0, result.segmentsWritten)
        assertEquals(1, result.rowsCoveredByCapture)
        assertEquals("only the captured row", 1, db.dao().allSegments().size)
        assertEquals(SegmentSource.CAPTURE, db.dao().allSegments().single().source)
    }

    /**
     * A captured play of the same track at a different time is a different
     * listen and must not suppress the imported one — otherwise one capture
     * would swallow every historical play of that song.
     */
    @Test
    fun aCapturedSegmentAtAnotherTimeDoesNotSuppressTheImport() = runBlocking {
        captureSegment(startedAtMs = 1_600_000_000_000, endedAtMs = 1_600_000_200_000)

        val result = importer.importJsonFile(
            streamOf(historyJson(row("2024-03-01T20:15:30Z", 214_000))),
        )

        assertEquals(1, result.segmentsWritten)
        assertEquals(0, result.rowsCoveredByCapture)
    }

    /** Back-to-back listens touch but do not overlap, so neither is dropped. */
    @Test
    fun aCapturedSegmentThatMerelyTouchesDoesNotSuppressTheImport() = runBlocking {
        // The imported play runs 20:11:56 -> 20:15:30. Capture picks up exactly
        // where it stops.
        captureSegment(startedAtMs = 1_709_324_130_000, endedAtMs = 1_709_324_330_000)

        val result = importer.importJsonFile(
            streamOf(historyJson(row("2024-03-01T20:15:30Z", 214_000))),
        )

        assertEquals(1, result.segmentsWritten)
        assertEquals(0, result.rowsCoveredByCapture)
    }

    // --- Duration arrives later ----------------------------------------------

    /**
     * The export carries no durations, so an imported play of a track nobody
     * has liked cannot be scored — "how much of it was heard" has no answer.
     *
     * When the like sync later learns the duration, the same stored row starts
     * scoring, with nothing re-imported. This is what decision 12's "import
     * once, early" rests on.
     */
    @Test
    fun aTrackLikedAfterTheImportStartsScoringWithoutAReImport() = runBlocking {
        importer.importJsonFile(streamOf(historyJson(row("2024-03-01T20:15:30Z", 214_000))))

        val segment = db.dao().allSegments().single()
        assertEquals("the export has no durations", 0L, segment.trackDurationMs)

        val parameters = ScoringParameters()
        assertEquals(
            "an unknown duration must contribute nothing, not a rejection",
            0.0,
            ScoringCurve.outcome(segment.listenedMs, segment.trackDurationMs, parameters)
                .playEquivalents,
            1e-9,
        )

        // The like sync now sees the track, and fills in what the export could
        // not: the duration.
        val serviceRowId = db.spotifyDao().resolveServiceTrack(
            trackKey = trackKeyOf("Metadata", "Willix", "Metadata"),
            title = "Metadata",
            artist = "Willix",
            album = "Metadata",
            // 214s of 220s is 97% heard: unambiguously a full play, rather
            // than a fraction that lands inside the upper ramp and scores
            // partially. The ramp itself is ScoringCurveTest's business.
            durationMs = 220_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = TRACK_URI,
            isrc = "GBAYE1500860",
            now = fixedNow,
        )
        db.spotifyDao().recordLiked(
            serviceTrackRowId = serviceRowId,
            serviceAddedAtMs = 1_700_000_000_000,
            now = fixedNow,
        )

        // The same stored row, now scoreable through the service row's duration.
        val serviceRow = db.dao().allServiceTracks().single()
        assertEquals("the like sync filled the duration the export lacked", 220_000L, serviceRow.durationMs)
        val fallbackDuration = db.dao().allSegments().single().trackDurationMs
            .takeIf { it > 0 } ?: serviceRow.durationMs
        assertEquals(
            1.0,
            ScoringCurve.outcome(segment.listenedMs, fallbackDuration, parameters).playEquivalents,
            1e-9,
        )
        assertEquals("nothing was re-imported", 1, db.dao().allSegments().size)
    }

    // --- Zip handling ---------------------------------------------------------

    @Test
    fun aZipImportsEveryAudioHistoryEntryAndIgnoresTheRest() = runBlocking {
        val result = importer.importZip(
            zipOf(
                "MyData/Streaming_History_Audio_2023_0.json" to
                    historyJson(row("2024-03-01T20:15:30Z", 214_000)),
                "MyData/Streaming_History_Audio_2024_1.json" to
                    historyJson(row("2024-03-02T20:15:30Z", 100_000)),
                // None of the app's business, and not valid history JSON.
                "MyData/Playlist1.json" to """{"playlists":[{"name":"x"}]}""",
                "MyData/Marquee.json" to """[{"artistName":"y"}]""",
                "MyData/Streaming_History_Video_2024_0.json" to """[{"ts":"x"}]""",
                "__MACOSX/._Streaming_History_Audio_2023_0.json" to "rubbish",
            ),
        )

        assertEquals(2, result.filesRead)
        assertEquals(2, result.segmentsWritten)
        assertEquals(2, db.dao().allSegments().size)
    }

    @Test
    fun aZipWithNoHistoryFilesFailsRatherThanReportingSuccess() {
        assertThrows(HistoryImporter.NoHistoryFilesException::class.java) {
            runBlocking {
                importer.importZip(zipOf("MyData/Playlist1.json" to """{"playlists":[]}"""))
            }
        }
    }

    @Test
    fun theSameZipIsNotImportedTwice() = runBlocking {
        val zip = zipOf(
            "MyData/Streaming_History_Audio_2023_0.json" to
                historyJson(row("2024-03-01T20:15:30Z", 214_000)),
        )

        assertEquals(1, importer.importZip(zip).segmentsWritten)
        val second = importer.importZip(zip)

        assertEquals(0, second.segmentsWritten)
        assertEquals(1, second.filesSkippedAlreadyImported)
    }

    @Test
    fun zipContentsAreSniffedFromTheirFirstBytes() {
        val zipHeader = zipOf("a/Streaming_History_Audio_0.json" to "[]")().use { stream ->
            ByteArray(4).also { stream.read(it) }
        }
        val jsonHeader = "[{\"ts\"".toByteArray().copyOf(4)

        assertTrue(HistoryFiles.looksLikeZip(zipHeader))
        assertTrue(!HistoryFiles.looksLikeZip(jsonHeader))
    }

    // --- Batching -------------------------------------------------------------

    /**
     * More rows than fit in one transaction, to exercise the batch boundary.
     * 77,000 rows go through this path on the real import.
     */
    @Test
    fun importsMoreRowsThanOneBatch() = runBlocking {
        val count = HistoryImporter.BATCH_SIZE + 137
        val rows = (0 until count).map { index ->
            row(
                ts = "2024-03-01T20:15:30Z",
                msPlayed = 200_000,
                uri = "spotify:track:t%08d".format(index),
                name = "Track $index",
            )
        }
        val progressReports = mutableListOf<Int>()

        val result = importer.importJsonFile(streamOf(historyJson(*rows.toTypedArray()))) {
            progressReports += it.segmentsWritten
        }

        assertEquals(count, result.segmentsWritten)
        assertEquals(count, db.dao().allSegments().size)
        assertTrue("progress should have been reported", progressReports.isNotEmpty())
        assertEquals(HistoryImporter.BATCH_SIZE, progressReports.first())
    }

    /** A malformed file stops the import rather than recording it as done. */
    @Test
    fun aMalformedFileIsNotRecordedAsImported() = runBlocking {
        val broken = """[{"ts":"2024-03-01T20:15:30Z","ms_played":1"""

        assertThrows(Exception::class.java) {
            runBlocking { importer.importJsonFile(streamOf(broken)) }
        }

        // Nothing recorded, so fixing the download and retrying works.
        val sha = streamOf(broken)().use { HistoryFiles.sha256(it) }
        assertNull(db.importDao().fileImportedAtMs(sha))
    }

    @Test
    fun theImportSummaryRecordsWhatHappened() = runBlocking {
        val json = historyJson(row("2024-03-01T20:15:30Z", 214_000))

        importer.importJsonFile(streamOf(json))

        val sha = streamOf(json)().use { HistoryFiles.sha256(it) }
        assertNotNull(db.importDao().fileImportedAtMs(sha))
        assertEquals(fixedNow, db.importDao().fileImportedAtMs(sha))
    }

    // --- Helpers --------------------------------------------------------------

    private suspend fun captureSegment(startedAtMs: Long, endedAtMs: Long) {
        db.dao().recordSegment(
            trackKey = trackKeyOf("Metadata", "Willix", "Metadata"),
            title = "Metadata",
            artist = "Willix",
            album = "Metadata",
            durationMs = 240_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = TRACK_URI,
            segment = ListeningSegmentEntity(
                trackId = 0,
                serviceTrackRowId = null,
                appPackage = "com.spotify.music",
                deviceId = "test-device",
                startedAtMs = startedAtMs,
                endedAtMs = endedAtMs,
                startPositionMs = 0,
                endPositionMs = 120_000,
                listenedMs = endedAtMs - startedAtMs,
                trackDurationMs = 240_000,
                forwardSeekCount = 0,
                seekedForwardMs = 0,
                endReason = EndReason.TRACK_CHANGE,
                endJumpFromMs = null,
                endJumpToMs = null,
                lastSampleAgeMs = 100,
                createdAt = endedAtMs,
            ),
        )
    }

    private companion object {
        const val TRACK_URI = "spotify:track:4h3K5he5ItFqJB1MVHMKJV"
    }
}
