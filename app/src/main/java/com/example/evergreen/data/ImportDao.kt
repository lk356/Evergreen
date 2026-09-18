package com.example.evergreen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The reads the streaming-history import needs, and its bookkeeping.
 *
 * Separate from [EvergreenDao] so the capture path's DAO is not touched, and
 * from [SpotifyDao] because none of this goes near the network.
 *
 * Every check here is a single indexed lookup rather than a preloaded index in
 * memory. That is deliberate: the export is about 77,000 rows and the device
 * that runs the import is the NW-A307, where memory is the scarce resource and
 * CPU time is not. Holding 77,000 keys in a hash set to save a few seconds of
 * indexed lookups would trade the resource we have for the one we do not.
 */
@Dao
abstract class ImportDao {

    // --- Resolving a URI before deciding whether to import it -----------------

    /**
     * The service row for a Spotify URI, or null if it has never been seen.
     *
     * Needed *before* the row is written, because both skip rules below are
     * keyed on identity: whether this exact play is already imported, and
     * whether capture already saw the same track at the same time.
     */
    @Query(
        "SELECT * FROM service_tracks WHERE service = :service AND serviceTrackId = :serviceTrackId LIMIT 1",
    )
    abstract suspend fun findServiceTrack(service: String, serviceTrackId: String): ServiceTrackEntity?

    // --- The two skip rules ---------------------------------------------------

    /**
     * True when this exact play has already been imported.
     *
     * The within-a-run half of idempotence. The file-hash guard stops the same
     * file being imported twice; this stops the same *play* being imported
     * twice when it arrives in two different files — which happens when a later
     * export overlaps an earlier one, and when the user imports both the zip
     * and a JSON file extracted from it.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM listening_segments
             WHERE serviceTrackRowId = :serviceTrackRowId
               AND startedAtMs = :startedAtMs
               AND source = 'IMPORT_SPOTIFY_HISTORY'
        )
        """,
    )
    abstract suspend fun hasImportedSegment(serviceTrackRowId: Long, startedAtMs: Long): Boolean

    /**
     * True when capture already recorded this track over the same stretch of
     * time.
     *
     * Capture is the better record of the two: it knows the position reached,
     * the seeks and the replays, where the export knows only a duration. So
     * where they overlap, capture wins and the imported row is dropped.
     *
     * Today nothing overlaps — the export ends 2026-09-15 and capture began
     * 2026-09-17. This rule is for the re-export next year, which will contain
     * plays the phone also saw. It keeps the desktop plays capture cannot see
     * until Phase 6 while dropping the duplicates.
     *
     * Overlap is strict at both ends, so two listens that merely touch — one
     * ending exactly as the next begins, which is what back-to-back plays look
     * like — are not treated as the same listen.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM listening_segments
             WHERE trackId = :trackId
               AND source = 'CAPTURE'
               AND startedAtMs < :endedAtMs
               AND endedAtMs > :startedAtMs
        )
        """,
    )
    abstract suspend fun hasCapturedOverlap(trackId: Long, startedAtMs: Long, endedAtMs: Long): Boolean

    // --- Counts for the debug screen -----------------------------------------

    @Query("SELECT COUNT(*) FROM listening_segments WHERE source = 'IMPORT_SPOTIFY_HISTORY'")
    abstract fun importedSegmentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM listening_segments WHERE source = 'IMPORT_SPOTIFY_HISTORY'")
    abstract suspend fun importedSegmentCountOnce(): Int

    @Query("SELECT COUNT(*) FROM listening_segments WHERE source = 'CAPTURE'")
    abstract suspend fun capturedSegmentCountOnce(): Int

    /** The oldest and newest imported play, for the import summary. */
    @Query(
        """
        SELECT MIN(startedAtMs) FROM listening_segments WHERE source = 'IMPORT_SPOTIFY_HISTORY'
        """,
    )
    abstract suspend fun oldestImportedAtMs(): Long?

    @Query(
        """
        SELECT MAX(endedAtMs) FROM listening_segments WHERE source = 'IMPORT_SPOTIFY_HISTORY'
        """,
    )
    abstract suspend fun newestImportedAtMs(): Long?

    // --- File bookkeeping -----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    protected abstract suspend fun syncState(key: String): SyncStateEntity?

    @Query("SELECT COUNT(*) FROM sync_state WHERE `key` LIKE :prefix || '%'")
    abstract fun importedFileCount(prefix: String = SyncKeys.HISTORY_IMPORT_FILE_PREFIX): Flow<Int>

    /**
     * When the file with this content hash finished importing, or null.
     *
     * Keyed on the content rather than the name, so re-downloading the export
     * to a different filename is still recognised as the same file, and editing
     * a file is correctly recognised as a different one.
     *
     * One `sync_state` row per file rather than a growing JSON list in a single
     * row: the lookup is then a primary-key hit, and two imports finishing near
     * each other cannot overwrite one another's record.
     */
    suspend fun fileImportedAtMs(sha256: String): Long? =
        syncState(SyncKeys.HISTORY_IMPORT_FILE_PREFIX + sha256)?.valueMs

    /**
     * Records that a file finished importing.
     *
     * Written only at the *end* of a file, so an import interrupted by a flat
     * battery is resumed rather than skipped. Re-running it is cheap because
     * every row it already wrote is caught by [hasImportedSegment].
     */
    suspend fun recordFileImported(sha256: String, at: Long, summary: String) {
        putSyncState(
            SyncStateEntity(
                key = SyncKeys.HISTORY_IMPORT_FILE_PREFIX + sha256,
                valueMs = at,
                valueText = summary,
            ),
        )
    }

    /** True once this device has been told it is the one that writes Evergreen. */
    suspend fun isEvergreenWriter(): Boolean =
        syncState(SyncKeys.EVERGREEN_WRITER_DEVICE)?.valueText == "true"

    fun evergreenWriterFlow(): Flow<SyncStateEntity?> = syncStateFlow(SyncKeys.EVERGREEN_WRITER_DEVICE)

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    protected abstract fun syncStateFlow(key: String): Flow<SyncStateEntity?>

    suspend fun setEvergreenWriter(enabled: Boolean) {
        putSyncState(
            SyncStateEntity(
                key = SyncKeys.EVERGREEN_WRITER_DEVICE,
                valueMs = System.currentTimeMillis(),
                valueText = enabled.toString(),
            ),
        )
    }
}
