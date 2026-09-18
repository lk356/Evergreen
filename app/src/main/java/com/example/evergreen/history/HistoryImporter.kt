package com.example.evergreen.history

import androidx.room.withTransaction
import com.example.evergreen.data.EndReason
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.SegmentSource
import com.example.evergreen.data.trackKeyOf
import java.io.InputStream

/** What an import did. Counts only — never a row. */
data class ImportResult(
    val filesRead: Int = 0,
    val filesSkippedAlreadyImported: Int = 0,
    val rowsRead: Int = 0,
    val segmentsWritten: Int = 0,
    val rowsWithoutTrackUri: Int = 0,
    val rowsWithoutTimestamp: Int = 0,
    val rowsAlreadyImported: Int = 0,
    val rowsCoveredByCapture: Int = 0,
) {
    operator fun plus(other: ImportResult) = ImportResult(
        filesRead = filesRead + other.filesRead,
        filesSkippedAlreadyImported = filesSkippedAlreadyImported + other.filesSkippedAlreadyImported,
        rowsRead = rowsRead + other.rowsRead,
        segmentsWritten = segmentsWritten + other.segmentsWritten,
        rowsWithoutTrackUri = rowsWithoutTrackUri + other.rowsWithoutTrackUri,
        rowsWithoutTimestamp = rowsWithoutTimestamp + other.rowsWithoutTimestamp,
        rowsAlreadyImported = rowsAlreadyImported + other.rowsAlreadyImported,
        rowsCoveredByCapture = rowsCoveredByCapture + other.rowsCoveredByCapture,
    )

    /** A one-line summary for `sync_state` and the debug screen. */
    fun summaryLine(): String =
        "$segmentsWritten written, $rowsRead read, " +
            "$rowsWithoutTrackUri not tracks, $rowsAlreadyImported already imported, " +
            "$rowsCoveredByCapture covered by capture"
}

/**
 * Writes a Spotify streaming-history export into the segment table.
 *
 * **There is no parallel import path** (invariant 1b). Imported plays become
 * ordinary [ListeningSegmentEntity] rows, resolved through the same
 * `recordSegment` capture uses, so a known Spotify URI joins its existing
 * canonical track and an unknown one creates a new one. The scorer has no idea
 * which rows came from where, and there is no second scoring route to keep in
 * step with the first.
 *
 * What an imported row cannot say, it leaves null: position, seeks, and the age
 * of the last reading. See [ListeningSegmentEntity] for why that matters.
 */
class HistoryImporter(
    private val database: EvergreenDatabase,
    private val parser: HistoryParser = HistoryParser(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val dao = database.dao()
    private val importDao = database.importDao()

    /**
     * Imports one JSON file's worth of rows.
     *
     * @param openStream opens the file. Called twice — once to hash the
     *   contents, once to read them — because identity is by content and the
     *   hash has to be known before deciding whether to read at all.
     * @param onProgress called every [BATCH_SIZE] rows with the running result.
     */
    suspend fun importJsonFile(
        openStream: () -> InputStream,
        onProgress: (ImportResult) -> Unit = {},
    ): ImportResult {
        val sha256 = openStream().use { HistoryFiles.sha256(it) }
        if (importDao.fileImportedAtMs(sha256) != null) {
            return ImportResult(filesSkippedAlreadyImported = 1)
        }

        val result = openStream().use { stream -> importStream(stream, onProgress) }

        // Recorded only now, at the end. An import stopped by a flat battery is
        // therefore resumed on the next attempt rather than being skipped as
        // done, and re-reading it is cheap because every row it already wrote is
        // caught by the per-row check below.
        importDao.recordFileImported(sha256, now(), result.summaryLine())
        return result.copy(filesRead = 1)
    }

    /**
     * Imports every audio-history entry of a zipped export.
     *
     * The zip is hashed as a whole, so re-importing the same download does
     * nothing. Importing a JSON file extracted from a zip already imported is a
     * different hash and will be read — and then written off row by row by
     * [com.example.evergreen.data.ImportDao.hasImportedSegment], which is why
     * both defences exist.
     */
    suspend fun importZip(
        openStream: () -> InputStream,
        onProgress: (ImportResult) -> Unit = {},
    ): ImportResult {
        val sha256 = openStream().use { HistoryFiles.sha256(it) }
        if (importDao.fileImportedAtMs(sha256) != null) {
            return ImportResult(filesSkippedAlreadyImported = 1)
        }

        var total = ImportResult()
        openStream().use { raw ->
            val zip = java.util.zip.ZipInputStream(raw.buffered())
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!HistoryFiles.isAudioHistoryEntry(entry.name)) {
                    zip.closeEntry()
                    continue
                }
                // The entry stream must not be closed by the parser; closing it
                // would close the whole zip and lose every later entry.
                val carried = total
                total += importStream(NonClosing(zip)) { progress -> onProgress(carried + progress) }
                    .copy(filesRead = 1)
                zip.closeEntry()
            }
        }

        if (total.filesRead == 0) {
            throw NoHistoryFilesException(
                "No streaming history files found. The export should contain files named " +
                    "Streaming_History_Audio_*.json.",
            )
        }

        importDao.recordFileImported(sha256, now(), total.summaryLine())
        return total
    }

    /** Thrown when a zip contains nothing importable, rather than reporting a successful import of nothing. */
    class NoHistoryFilesException(message: String) : Exception(message)

    private suspend fun importStream(
        stream: InputStream,
        onProgress: (ImportResult) -> Unit,
    ): ImportResult {
        var written = 0
        var alreadyImported = 0
        var coveredByCapture = 0
        var rowsSeen = 0
        val batch = mutableListOf<HistoryRow>()

        suspend fun flush() {
            if (batch.isEmpty()) return
            // One transaction per batch rather than per row. 77,000 individual
            // transactions would mean 77,000 commits, which is the difference
            // between a minute and an afternoon on the NW-A307.
            database.withTransaction {
                for (row in batch) {
                    when (writeRow(row)) {
                        RowResult.WRITTEN -> written++
                        RowResult.ALREADY_IMPORTED -> alreadyImported++
                        RowResult.COVERED_BY_CAPTURE -> coveredByCapture++
                    }
                }
            }
            batch.clear()
        }

        val parsed = parser.parse(stream) { row ->
            batch += row
            rowsSeen++
            if (batch.size >= BATCH_SIZE) {
                flush()
                onProgress(
                    ImportResult(
                        rowsRead = rowsSeen,
                        segmentsWritten = written,
                        rowsAlreadyImported = alreadyImported,
                        rowsCoveredByCapture = coveredByCapture,
                    ),
                )
            }
        }
        flush()

        return ImportResult(
            rowsRead = parsed.rowsRead,
            segmentsWritten = written,
            rowsWithoutTrackUri = parsed.rowsWithoutTrackUri,
            rowsWithoutTimestamp = parsed.rowsWithoutTimestamp,
            rowsAlreadyImported = alreadyImported,
            rowsCoveredByCapture = coveredByCapture,
        )
    }

    private enum class RowResult { WRITTEN, ALREADY_IMPORTED, COVERED_BY_CAPTURE }

    private suspend fun writeRow(row: HistoryRow): RowResult {
        // Resolved first, because both skip rules are about identity: is this
        // exact play already here, and did capture already see this track then.
        val known = importDao.findServiceTrack(SERVICE_SPOTIFY, row.spotifyTrackUri)

        if (known != null) {
            if (importDao.hasImportedSegment(known.id, row.startedAtMs)) {
                return RowResult.ALREADY_IMPORTED
            }
            if (importDao.hasCapturedOverlap(known.trackId, row.startedAtMs, row.endedAtMs)) {
                return RowResult.COVERED_BY_CAPTURE
            }
        }

        // The duration comes from the service row when it is known, and is 0
        // otherwise, because the export has none. 0 is honest: the scorer reads
        // it as "no duration known" and contributes nothing, and it falls back
        // to the service row at scoring time — so a track liked *after* the
        // import starts scoring its imported plays with no re-import needed.
        val durationMs = known?.durationMs ?: 0L

        dao.recordSegment(
            trackKey = trackKeyOf(row.trackName, row.artistName, row.albumName),
            title = row.trackName,
            artist = row.artistName,
            album = row.albumName,
            durationMs = durationMs,
            service = SERVICE_SPOTIFY,
            serviceTrackId = row.spotifyTrackUri,
            segment = ListeningSegmentEntity(
                trackId = 0,
                serviceTrackRowId = null,
                appPackage = SPOTIFY_PACKAGE,
                deviceId = IMPORT_DEVICE_ID,
                startedAtMs = row.startedAtMs,
                endedAtMs = row.endedAtMs,
                // Null, not zero. The export records how long a stream lasted,
                // never where in the track it reached.
                startPositionMs = null,
                endPositionMs = null,
                listenedMs = row.listenedMs,
                trackDurationMs = durationMs,
                forwardSeekCount = null,
                seekedForwardMs = null,
                endReason = EndReason.IMPORTED,
                endJumpFromMs = null,
                endJumpToMs = null,
                lastSampleAgeMs = null,
                createdAt = now(),
                source = SegmentSource.IMPORT_SPOTIFY_HISTORY,
                sourceEndReason = row.reasonEnd,
            ),
        )
        return RowResult.WRITTEN
    }

    /**
     * Hides [close] from the parser so that finishing one zip entry does not
     * close the zip and lose every entry after it.
     */
    private class NonClosing(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() = Unit
    }

    companion object {
        /**
         * Rows per transaction.
         *
         * Big enough that the commit cost is amortised, small enough that an
         * interrupted import loses at most this much work and that the
         * transaction's memory stays bounded on a low-memory device.
         */
        const val BATCH_SIZE = 500

        const val SPOTIFY_PACKAGE = "com.spotify.music"

        /**
         * The `deviceId` imported rows carry.
         *
         * A fixed string rather than this device's random install id, because
         * an imported play was not observed by any device — it came from
         * Spotify's own records of listening that may have happened on a
         * desktop this app has never run on. Phase 6 needs to tell those apart
         * from segments synced from a real device.
         */
        const val IMPORT_DEVICE_ID = "spotify-history-import"
    }
}
