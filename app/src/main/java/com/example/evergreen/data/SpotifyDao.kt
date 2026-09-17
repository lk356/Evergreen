package com.example.evergreen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A row the ISRC backfill still needs to look up. */
data class IsrcLookupCandidate(
    val id: Long,
    val serviceTrackId: String,
)

/** Two canonical tracks that share an ISRC — a same-song merge candidate. */
data class MergeCandidate(
    val isrc: String,
    val trackId: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val segmentCount: Int,
)

/**
 * Everything Phase 2 writes: the like archive, ISRC backfill bookkeeping and
 * sync timestamps.
 *
 * Kept apart from [EvergreenDao] so the capture path's queries stay exactly as
 * Phase 1 left them. Nothing here is called from the capture path.
 */
@Dao
abstract class SpotifyDao {

    // --- Resolving a track we learned about from the service -----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTrackIfNew(track: TrackEntity): Long

    @Query("SELECT id FROM tracks WHERE trackKey = :trackKey LIMIT 1")
    protected abstract suspend fun findTrackId(trackKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertServiceTrackIfNew(row: ServiceTrackEntity): Long

    @Query(
        "SELECT * FROM service_tracks WHERE service = :service AND serviceTrackId = :serviceTrackId LIMIT 1",
    )
    protected abstract suspend fun findServiceTrack(
        service: String,
        serviceTrackId: String,
    ): ServiceTrackEntity?

    @Query(
        """
        UPDATE service_tracks
           SET lastSeenAt = :at,
               isrc = COALESCE(:isrc, isrc),
               isrcLookupAt = CASE WHEN :isrc IS NOT NULL THEN :at ELSE isrcLookupAt END,
               isrcLookupError = CASE WHEN :isrc IS NOT NULL THEN NULL ELSE isrcLookupError END
         WHERE id = :id
        """,
    )
    protected abstract suspend fun touchServiceTrack(id: Long, at: Long, isrc: String?)

    /**
     * Finds or creates the rows for a track the *service* told us about.
     *
     * A liked song may never have been played on this phone, so the like sync
     * has to be able to create canonical tracks, not only annotate existing
     * ones. Otherwise the archive would silently hold only the likes that
     * happen to overlap the listening history.
     *
     * Resolution order matches [EvergreenDao.recordSegment] deliberately: the
     * service identifier wins when we already know it, and `trackKey` is only a
     * fallback. That order is tested in `EvergreenDaoTest` and must not be
     * reversed here, or the same song would fork into two canonical tracks
     * depending on which code path saw it first.
     */
    @Transaction
    open suspend fun resolveServiceTrack(
        trackKey: String,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        service: String,
        serviceTrackId: String,
        isrc: String?,
        now: Long,
    ): Long {
        findServiceTrack(service, serviceTrackId)?.let { known ->
            touchServiceTrack(known.id, now, isrc)
            return known.id
        }

        val trackId = insertTrackIfNew(
            TrackEntity(
                trackKey = trackKey,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                firstSeenAt = now,
                lastSeenAt = now,
            ),
        ).takeIf { it != -1L }
            ?: requireNotNull(findTrackId(trackKey)) { "track neither inserted nor found" }

        val inserted = insertServiceTrackIfNew(
            ServiceTrackEntity(
                trackId = trackId,
                service = service,
                serviceTrackId = serviceTrackId,
                isrc = isrc,
                isrcLookupAt = if (isrc != null) now else null,
                durationMs = durationMs,
                firstSeenAt = now,
                lastSeenAt = now,
            ),
        )
        if (inserted != -1L) return inserted

        // Lost a race; the unique index decided the winner.
        val existing = requireNotNull(findServiceTrack(service, serviceTrackId))
        touchServiceTrack(existing.id, now, isrc)
        return existing.id
    }

    // --- The like archive ----------------------------------------------------

    @Query("SELECT * FROM likes WHERE serviceTrackRowId = :serviceTrackRowId LIMIT 1")
    protected abstract suspend fun findLike(serviceTrackRowId: Long): LikeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLike(like: LikeEntity): Long

    @Query(
        """
        UPDATE likes
           SET lastSeenLikedAt = :at,
               unlikedAt = NULL,
               serviceAddedAtMs = COALESCE(:serviceAddedAtMs, serviceAddedAtMs)
         WHERE id = :id
        """,
    )
    protected abstract suspend fun refreshLike(id: Long, at: Long, serviceAddedAtMs: Long?)

    @Insert
    protected abstract suspend fun insertLikeEvent(event: LikeEventEntity)

    /**
     * Records that a track was seen liked in this sync.
     *
     * An event row is written only on a *transition*. Writing one every sync
     * would add a row per liked song per run — a few thousand a day, forever —
     * to describe nothing having happened. "Still liked" is carried by
     * [LikeEntity.lastSeenLikedAt] instead.
     */
    @Transaction
    open suspend fun recordLiked(serviceTrackRowId: Long, serviceAddedAtMs: Long?, now: Long) {
        val existing = findLike(serviceTrackRowId)
        if (existing == null) {
            insertLike(
                LikeEntity(
                    serviceTrackRowId = serviceTrackRowId,
                    serviceAddedAtMs = serviceAddedAtMs,
                    firstSeenLikedAt = now,
                    lastSeenLikedAt = now,
                    unlikedAt = null,
                ),
            )
            insertLikeEvent(
                LikeEventEntity(
                    serviceTrackRowId = serviceTrackRowId,
                    event = LikeEventType.LIKED,
                    observedAt = now,
                    serviceAddedAtMs = serviceAddedAtMs,
                ),
            )
            return
        }

        val wasUnliked = existing.unlikedAt != null
        refreshLike(existing.id, now, serviceAddedAtMs)
        if (wasUnliked) {
            // A re-like. The previous UNLIKED event stays where it is, so the
            // round trip remains visible in the archive.
            insertLikeEvent(
                LikeEventEntity(
                    serviceTrackRowId = serviceTrackRowId,
                    event = LikeEventType.LIKED,
                    observedAt = now,
                    serviceAddedAtMs = serviceAddedAtMs,
                ),
            )
        }
    }

    @Query(
        """
        SELECT serviceTrackRowId FROM likes
         WHERE unlikedAt IS NULL AND lastSeenLikedAt < :syncStartedAt
        """,
    )
    protected abstract suspend fun likesNotSeenSince(syncStartedAt: Long): List<Long>

    @Query("UPDATE likes SET unlikedAt = :at WHERE serviceTrackRowId IN (:ids)")
    protected abstract suspend fun markUnliked(ids: List<Long>, at: Long)

    /**
     * Marks everything that was liked before this sync but did not appear in it.
     *
     * **Only safe to call after a sync that read every page.** A run cut short
     * by a 429, a dropped connection or a dead battery would otherwise look
     * exactly like the user having unliked everything it did not get to. The
     * caller enforces this; see `LikeSync`.
     *
     * Nothing is deleted. The row stays, gains an `unlikedAt`, and an UNLIKED
     * event is appended — the song becomes historical, not erased.
     */
    @Transaction
    open suspend fun markUnlikedAfterFullSync(syncStartedAt: Long, now: Long): Int {
        val gone = likesNotSeenSince(syncStartedAt)
        if (gone.isEmpty()) return 0
        markUnliked(gone, now)
        gone.forEach { id ->
            insertLikeEvent(
                LikeEventEntity(
                    serviceTrackRowId = id,
                    event = LikeEventType.UNLIKED,
                    observedAt = now,
                ),
            )
        }
        return gone.size
    }

    // --- ISRC backfill -------------------------------------------------------

    /**
     * Rows still missing an ISRC that are due another attempt.
     *
     * Ordered oldest-attempt-first so a run that is cut short still makes
     * progress rather than retrying the same head of the queue every time.
     */
    @Query(
        """
        SELECT id, serviceTrackId FROM service_tracks
         WHERE isrc IS NULL
           AND service = :service
           AND (isrcLookupAt IS NULL OR isrcLookupAt < :retryBefore)
           AND isrcLookupAttempts < :maxAttempts
         ORDER BY isrcLookupAt IS NOT NULL, isrcLookupAt ASC, id ASC
         LIMIT :limit
        """,
    )
    abstract suspend fun serviceTracksNeedingIsrc(
        service: String,
        retryBefore: Long,
        maxAttempts: Int,
        limit: Int,
    ): List<IsrcLookupCandidate>

    @Query(
        """
        UPDATE service_tracks
           SET isrc = :isrc, isrcLookupAt = :at,
               isrcLookupAttempts = isrcLookupAttempts + 1,
               isrcLookupError = NULL
         WHERE id = :id
        """,
    )
    abstract suspend fun setIsrc(id: Long, isrc: String, at: Long)

    @Query(
        """
        UPDATE service_tracks
           SET isrcLookupAt = :at,
               isrcLookupAttempts = isrcLookupAttempts + 1,
               isrcLookupError = :error
         WHERE id = :id
        """,
    )
    abstract suspend fun recordIsrcLookupFailure(id: Long, error: String, at: Long)

    // --- Sync state ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    abstract suspend fun syncState(key: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    abstract fun syncStateFlow(key: String): Flow<SyncStateEntity?>

    suspend fun putTimestamp(key: String, at: Long) = putSyncState(SyncStateEntity(key, valueMs = at))

    // --- Counts for the debug screen -----------------------------------------

    @Query("SELECT COUNT(*) FROM likes WHERE unlikedAt IS NULL")
    abstract fun likedNowCount(): Flow<Int>

    /** Everything ever liked, including what has since been unliked. */
    @Query("SELECT COUNT(*) FROM likes")
    abstract fun archivedLikeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM like_events")
    abstract fun likeEventCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM service_tracks WHERE service = :service")
    abstract fun serviceTrackCount(service: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM service_tracks WHERE service = :service AND isrc IS NOT NULL")
    abstract fun serviceTracksWithIsrcCount(service: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM service_tracks
         WHERE service = :service AND isrc IS NULL AND isrcLookupError IS NOT NULL
        """,
    )
    abstract fun failedIsrcLookupCount(service: String): Flow<Int>

    @Query(
        """
        SELECT isrcLookupError AS reason, COUNT(*) AS count
          FROM service_tracks
         WHERE service = :service AND isrc IS NULL AND isrcLookupError IS NOT NULL
         GROUP BY isrcLookupError ORDER BY count DESC
        """,
    )
    abstract fun failedIsrcLookupBreakdown(service: String): Flow<List<IsrcFailureCount>>

    // --- Same-song merge, dry run only ---------------------------------------

    /**
     * Canonical tracks that share an ISRC with at least one other.
     *
     * Read-only, and nothing in Phase 2 acts on it. `trackKey` includes the
     * album, so the single and the album release of a song are two canonical
     * tracks today; an ISRC identifies the *recording* and so is the natural
     * candidate for merging them. Whether it is the right rule is a judgement
     * about the user's data, so this reports and stops.
     *
     * `segmentCount` is included because it is what a merge would have to
     * re-point, and because a candidate pair where both sides have listening
     * history is the interesting case to eyeball first.
     */
    @Query(
        """
        SELECT st.isrc AS isrc,
               st.trackId AS trackId,
               t.title AS title,
               t.artist AS artist,
               t.album AS album,
               (SELECT COUNT(*) FROM listening_segments s WHERE s.trackId = t.id) AS segmentCount
          FROM service_tracks st
          JOIN tracks t ON t.id = st.trackId
         WHERE st.isrc IS NOT NULL
           AND st.isrc IN (
               SELECT isrc FROM service_tracks
                WHERE isrc IS NOT NULL
                GROUP BY isrc HAVING COUNT(DISTINCT trackId) > 1
           )
         ORDER BY st.isrc, t.id
        """,
    )
    abstract suspend fun sameIsrcMergeCandidates(): List<MergeCandidate>
}

/** One reason ISRC lookups failed, and how often. */
data class IsrcFailureCount(
    val reason: String,
    val count: Int,
)
