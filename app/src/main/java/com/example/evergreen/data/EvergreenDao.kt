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

    @Query(
        "SELECT * FROM service_tracks WHERE service = :service AND serviceTrackId = :serviceTrackId LIMIT 1",
    )
    protected abstract suspend fun findServiceTrack(
        service: String,
        serviceTrackId: String,
    ): ServiceTrackEntity?

    @Query("UPDATE service_tracks SET lastSeenAt = :at WHERE id = :id")
    protected abstract suspend fun touchServiceTrack(id: Long, at: Long)

    @Insert
    protected abstract suspend fun insertSegment(segment: ListeningSegmentEntity): Long

    /**
     * Resolves the canonical track, resolves the service identifier if there is
     * one, and writes the segment — all in one transaction.
     *
     * **The service identifier wins when we already know it.** A Spotify URI is
     * exact; `trackKey` is a guess assembled from title, artist and album, and
     * those change under us — a "Remastered" suffix appears, an album gets
     * renamed, a device reports metadata in a different language. Resolving by
     * `trackKey` first meant that a known URI arriving with changed metadata
     * created a *second* canonical track, while the existing `service_tracks`
     * row went on pointing at the first. The segment's `trackId` and its
     * `serviceTrackRowId` then disagreed about which song had been played.
     *
     * So: look the service ID up first and adopt its canonical track. Fall back
     * to `trackKey` only when there is no service ID at all, or when the service
     * ID has never been seen before.
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

        val known = serviceTrackId?.let { findServiceTrack(service, it) }

        val trackId: Long
        val serviceTrackRowId: Long?

        if (known != null) {
            // Known URI: this is the same song, whatever the metadata now says.
            // The canonical track's title is deliberately left as first seen —
            // rewriting it on every metadata wobble would churn the row and
            // change what Phase 3 scored, for no gain.
            trackId = known.trackId
            serviceTrackRowId = known.id
            touchTrack(trackId, now, durationMs)
            touchServiceTrack(known.id, now)
        } else {
            trackId = resolveTrackByKey(trackKey, title, artist, album, durationMs, now)
            serviceTrackRowId = serviceTrackId?.let { id ->
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
                // IGNORE returns -1 if another writer inserted it first; the
                // unique index remains the backstop.
                if (inserted != -1L) {
                    inserted
                } else {
                    findServiceTrackId(service, id)?.also { touchServiceTrack(it, now) }
                }
            }
        }

        return insertSegment(
            segment.copy(trackId = trackId, serviceTrackRowId = serviceTrackRowId),
        )
    }

    /**
     * Finds or creates the canonical track for [trackKey].
     *
     * "Insert and ignore a conflict, then look the row up" rather than "look up,
     * then insert if missing": the unique index makes the first form safe even
     * if two writes race, because the database itself decides the winner.
     */
    private suspend fun resolveTrackByKey(
        trackKey: String,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        now: Long,
    ): Long {
        val inserted = insertTrackIfNew(
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
        if (inserted != -1L) return inserted

        val existing = requireNotNull(findTrackId(trackKey)) {
            "track $trackKey neither inserted nor found"
        }
        touchTrack(existing, now, durationMs)
        return existing
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

    /**
     * Segments whose canonical track disagrees with the canonical track of the
     * service row they point at.
     *
     * Should always be zero. It can be non-zero for rows written before the
     * lookup order in [recordSegment] was fixed, where a known URI arriving with
     * changed metadata created a second canonical track. Diagnostic only —
     * nothing repairs these automatically, because deciding which of the two
     * tracks is the real one is a merge question that belongs to Phase 2.
     */
    @Query(
        """
        SELECT COUNT(*)
          FROM listening_segments s
          JOIN service_tracks st ON st.id = s.serviceTrackRowId
         WHERE s.trackId != st.trackId
        """,
    )
    abstract fun mismatchedTrackIdCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks")
    abstract fun trackCount(): Flow<Int>

    /**
     * When this app last watched something being played, or null if it never
     * has.
     *
     * Captured rows only. An imported row's `endedAtMs` is a real listening
     * time from the Spotify export, so counting those would let a six-year
     * import masquerade as capture still working — which is precisely the
     * failure this figure exists to notice. Capture rests on an undocumented
     * broadcast setting and on media-session behaviour reverse-engineered on a
     * device, so it can stop without anything else in the app changing.
     */
    @Query(
        "SELECT MAX(endedAtMs) FROM listening_segments WHERE source = 'CAPTURE'",
    )
    abstract fun lastCapturedAtMs(): Flow<Long?>

    /** [lastCapturedAtMs] as a one-shot read, for the workers. */
    @Query(
        "SELECT MAX(endedAtMs) FROM listening_segments WHERE source = 'CAPTURE'",
    )
    abstract suspend fun lastCapturedAtMsOnce(): Long?

    /** How many segments capture has written since [sinceMs]. */
    @Query(
        """
        SELECT COUNT(*) FROM listening_segments
         WHERE source = 'CAPTURE' AND endedAtMs >= :sinceMs
        """,
    )
    abstract fun capturedSinceCount(sinceMs: Long): Flow<Int>

    /** One-shot form of [mismatchedTrackIdCount], for tests and diagnostics. */
    @Query(
        """
        SELECT COUNT(*)
          FROM listening_segments s
          JOIN service_tracks st ON st.id = s.serviceTrackRowId
         WHERE s.trackId != st.trackId
        """,
    )
    abstract suspend fun mismatchedTrackIdCountOnce(): Int

    @Query("SELECT * FROM tracks ORDER BY id")
    abstract suspend fun allTracks(): List<TrackEntity>

    @Query("SELECT * FROM service_tracks ORDER BY id")
    abstract suspend fun allServiceTracks(): List<ServiceTrackEntity>

    @Query("SELECT * FROM listening_segments ORDER BY id")
    abstract suspend fun allSegments(): List<ListeningSegmentEntity>

    /**
     * One page of listening history with its titles, for the readable export.
     *
     * Paged rather than read whole. The segment table is the only thing in this
     * app that grows without bound and already holds 77,000 rows after the
     * import; materialising all of them as objects at once is tens of megabytes
     * on a device with limited RAM, to write a file that is streamed out row by
     * row anyway.
     *
     * Ordered by `id`, which is insertion order and therefore stable while the
     * export runs -- `startedAtMs` is not, because an import can add rows dated
     * years ago partway through.
     */
    @Query(
        """
        SELECT s.*, t.title AS title, t.artist AS artist
          FROM listening_segments s
          JOIN tracks t ON t.id = s.trackId
         ORDER BY s.id
         LIMIT :limit OFFSET :offset
        """,
    )
    abstract suspend fun segmentPage(limit: Int, offset: Int): List<SegmentWithTrack>

    @Query("SELECT COUNT(*) FROM listening_segments")
    abstract suspend fun segmentCountOnce(): Int

    @Query("DELETE FROM listening_segments")
    abstract suspend fun deleteAllSegments()

    @Query("DELETE FROM tracks")
    abstract suspend fun deleteAllTracks()

    @Query("DELETE FROM service_tracks")
    abstract suspend fun deleteAllServiceTracks()

    @Query("DELETE FROM likes")
    abstract suspend fun deleteAllLikes()

    @Query("DELETE FROM like_events")
    abstract suspend fun deleteAllLikeEvents()

    /**
     * Wipes everything. Debug-screen affordance, behind a confirmation.
     *
     * The order is load-bearing since schema v2. Deleting `tracks` used to
     * cascade into `service_tracks`; now every foreign key is RESTRICT, so each
     * table has to be cleared before the one it points at, children first. Get
     * the order wrong and SQLite raises a constraint failure rather than
     * deleting anything — which is the behaviour we asked for, and the reason
     * this is spelled out instead of relying on a cascade.
     */
    @Transaction
    open suspend fun deleteEverything() {
        deleteAllLikeEvents()
        deleteAllLikes()
        deleteAllSegments()
        deleteAllServiceTracks()
        deleteAllTracks()
    }
}
