package com.example.evergreen.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A segment together with the display fields of the track it belongs to. */
data class SegmentWithTrack(
    @Embedded val segment: ListeningSegmentEntity,
    val title: String?,
    val artist: String?,
)

@Dao
abstract class EvergreenDao {

    // --- Writes -------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTrackIfNew(track: TrackEntity): Long

    @Query("SELECT id FROM tracks WHERE trackKey = :trackKey LIMIT 1")
    protected abstract suspend fun findTrackId(trackKey: String): Long?

    @Query(
        """
        UPDATE tracks
           SET lastSeenAt = :at,
               durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END
         WHERE id = :id
        """,
    )
    protected abstract suspend fun touchTrack(id: Long, at: Long, durationMs: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertServiceTrackIfNew(row: ServiceTrackEntity): Long

    @Query(
        "SELECT id FROM service_tracks WHERE service = :service AND serviceTrackId = :serviceTrackId LIMIT 1",
    )
    protected abstract suspend fun findServiceTrackId(service: String, serviceTrackId: String): Long?

    @Query("UPDATE service_tracks SET lastSeenAt = :at WHERE id = :id")
    protected abstract suspend fun touchServiceTrack(id: Long, at: Long)

    @Insert
    protected abstract suspend fun insertSegment(segment: ListeningSegmentEntity): Long

    /**
     * Resolves the canonical track, resolves the service identifier if there is
     * one, and writes the segment — all in one transaction.
     *
     * "Insert and ignore a conflict, then look the row up" rather than "look up,
     * then insert if missing": the unique indexes make the first form safe even
     * if two writes race, because the database itself decides the winner.
     */
    @Transaction
    open suspend fun recordSegment(
        trackKey: String,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        service: String,
        serviceTrackId: String?,
        segment: ListeningSegmentEntity,
    ): Long {
        val now = segment.endedAtMs

        val insertedTrackId = insertTrackIfNew(
            TrackEntity(
                trackKey = trackKey,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                firstSeenAt = now,
                lastSeenAt = now,
            ),
        )
        // IGNORE returns -1 when the row already existed.
        val trackId = if (insertedTrackId != -1L) {
            insertedTrackId
        } else {
            val existing = requireNotNull(findTrackId(trackKey)) {
                "track $trackKey neither inserted nor found"
            }
            touchTrack(existing, now, durationMs)
            existing
        }

        val serviceTrackRowId = serviceTrackId?.let { id ->
            val inserted = insertServiceTrackIfNew(
                ServiceTrackEntity(
                    trackId = trackId,
                    service = service,
                    serviceTrackId = id,
                    isrc = null,
                    durationMs = durationMs,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            )
            if (inserted != -1L) {
                inserted
            } else {
                findServiceTrackId(service, id)?.also { touchServiceTrack(it, now) }
            }
        }

        return insertSegment(
            segment.copy(trackId = trackId, serviceTrackRowId = serviceTrackRowId),
        )
    }

    // --- Reads --------------------------------------------------------------

    @Query(
        """
        SELECT s.*, t.title AS title, t.artist AS artist
          FROM listening_segments s
          JOIN tracks t ON t.id = s.trackId
         ORDER BY s.startedAtMs DESC, s.id DESC
         LIMIT :limit
        """,
    )
    abstract fun recentSegments(limit: Int): Flow<List<SegmentWithTrack>>

    @Query("SELECT COUNT(*) FROM listening_segments")
    abstract fun segmentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks")
    abstract fun trackCount(): Flow<Int>

    @Query("DELETE FROM listening_segments")
    abstract suspend fun deleteAllSegments()

    @Query("DELETE FROM tracks")
    abstract suspend fun deleteAllTracks()

    /** Wipes everything. Debug-screen affordance; cascades to service_tracks. */
    @Transaction
    open suspend fun deleteEverything() {
        deleteAllSegments()
        deleteAllTracks()
    }
}
