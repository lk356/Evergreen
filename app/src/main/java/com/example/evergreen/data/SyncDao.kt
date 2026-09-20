package com.example.evergreen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The reads a delta export needs, and the writes a delta import makes.
 *
 * Separate from the other four DAOs for the reason they are separate from each
 * other: the capture path's DAO is not touched by anything, and a new feature
 * gets its own surface rather than widening one that already works.
 *
 * **Everything here is keyed on a global name, never a local row id.** A row id
 * means nothing on another device, so an export sends content and a receiver
 * resolves it. The one id that does cross is `originRowId`, and it crosses as
 * data, paired with the device that assigned it.
 */
@Dao
abstract class SyncDao {

    // --- Watermarks ----------------------------------------------------------

    /**
     * Which devices' facts this database **originated**, newest id first.
     *
     * `originRowId IS NULL` is what "originated here" means: this database gave
     * the row its id. Rows that arrived in someone else's batch carry their
     * originating id and are that device's to ship, not ours — passing them on
     * would give the same fact two paths to arrive by, which is a merge
     * question and belongs to Phase 5c.
     *
     * More than one device id comes back on a real database, which is why a
     * batch carries a list of origins. The streaming-history import writes its
     * rows under the fixed origin `spotify-history-import` rather than this
     * device's install id, because an imported play was not observed by any
     * device; and a reinstall leaves older rows under the previous id.
     */
    @Query(
        """
        SELECT DISTINCT deviceId FROM listening_segments
         WHERE originRowId IS NULL
         ORDER BY deviceId
        """,
    )
    abstract suspend fun originatingDeviceIds(): List<String>

    /**
     * The highest originating id this database holds from [deviceId].
     *
     * `COALESCE(originRowId, id)` and not plain `id`, because the two transports
     * store it differently and both have to be counted. A row that arrived in a
     * **whole-file** mirror import carries the producer's own id in `id` with
     * `originRowId` null; a row that arrived in a **batch** carries the
     * producer's id in `originRowId` and a local one in `id`. This one
     * expression gives the right answer for both, and that is what lets Phase
     * 5a's transport and Phase 5b's coexist on one database.
     *
     * Null when this database holds nothing at all from that device, which is
     * how the import tells "no baseline" from "a gap".
     */
    @Query(
        """
        SELECT MAX(COALESCE(originRowId, id)) FROM listening_segments
         WHERE deviceId = :deviceId
        """,
    )
    abstract suspend fun watermarkFor(deviceId: String): Long?

    /**
     * The newest fact this database holds, however it arrived.
     *
     * What "how old is this mirror" should mean once deltas exist. Before them
     * the only answer available was the age of the last whole-file import,
     * which after a delta would be an answer to a question nobody asked.
     */
    @Query("SELECT MAX(endedAtMs) FROM listening_segments")
    abstract suspend fun newestFactAtMs(): Long?

    @Query("SELECT MAX(endedAtMs) FROM listening_segments")
    abstract fun newestFactAtMsFlow(): Flow<Long?>

    // --- Export: counting before writing -------------------------------------

    @Query(
        """
        SELECT COUNT(*) FROM listening_segments
         WHERE deviceId = :deviceId AND originRowId IS NULL AND id > :afterId
        """,
    )
    abstract suspend fun countSegmentsToExport(deviceId: String, afterId: Long): Int

    @Query(
        """
        SELECT MAX(id) FROM listening_segments
         WHERE deviceId = :deviceId AND originRowId IS NULL AND id > :afterId
        """,
    )
    abstract suspend fun highestSegmentToExport(deviceId: String, afterId: Long): Long?

    @Query("SELECT COUNT(*) FROM like_events")
    abstract suspend fun countLikeEvents(): Int

    @Query("SELECT COUNT(*) FROM likes")
    abstract suspend fun countLikes(): Int

    @Query("SELECT COUNT(*) FROM parameter_history")
    abstract suspend fun countParameterHistory(): Int

    @Query("SELECT COUNT(*) FROM evergreen_membership")
    abstract suspend fun countMembership(): Int

    @Query("SELECT COUNT(*) FROM snapshots")
    abstract suspend fun countSnapshots(): Int

    @Query("SELECT COUNT(*) FROM snapshot_entries")
    abstract suspend fun countSnapshotEntries(): Int

    // --- Export: the pages ---------------------------------------------------

    /**
     * A segment with everything needed to name its track on another device.
     *
     * The join is what replaces sending `tracks` and `service_tracks` as
     * tables: each fact carries its own content-addressed track, so the
     * receiver resolves rather than trusts.
     */
    data class SegmentExportRow(
        val id: Long,
        val deviceId: String,
        val appPackage: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val startPositionMs: Long?,
        val endPositionMs: Long?,
        val listenedMs: Long,
        val trackDurationMs: Long,
        val forwardSeekCount: Int?,
        val seekedForwardMs: Long?,
        val endReason: String,
        val endJumpFromMs: Long?,
        val endJumpToMs: Long?,
        val lastSampleAgeMs: Long?,
        val createdAt: Long,
        val source: String,
        val sourceEndReason: String?,
        val trackKey: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackDuration: Long,
        val service: String?,
        val serviceTrackId: String?,
        val isrc: String?,
    )

    /**
     * One page of segments to export, by keyset rather than by offset.
     *
     * `id > :afterId ORDER BY id LIMIT :limit` walks the primary key index and
     * costs the same for the last page as for the first. `LIMIT/OFFSET` would
     * re-scan everything before the page each time, which over 77,000 rows on
     * the NW-A307 turns a minute into an afternoon — the same lesson the
     * streaming-history import learned about committing per batch.
     */
    @Query(
        """
        SELECT s.id AS id, s.deviceId AS deviceId, s.appPackage AS appPackage,
               s.startedAtMs AS startedAtMs, s.endedAtMs AS endedAtMs,
               s.startPositionMs AS startPositionMs, s.endPositionMs AS endPositionMs,
               s.listenedMs AS listenedMs, s.trackDurationMs AS trackDurationMs,
               s.forwardSeekCount AS forwardSeekCount, s.seekedForwardMs AS seekedForwardMs,
               s.endReason AS endReason, s.endJumpFromMs AS endJumpFromMs,
               s.endJumpToMs AS endJumpToMs, s.lastSampleAgeMs AS lastSampleAgeMs,
               s.createdAt AS createdAt, s.source AS source,
               s.sourceEndReason AS sourceEndReason,
               t.trackKey AS trackKey, t.title AS title, t.artist AS artist,
               t.album AS album, t.durationMs AS trackDuration,
               st.service AS service, st.serviceTrackId AS serviceTrackId, st.isrc AS isrc
          FROM listening_segments s
          JOIN tracks t ON t.id = s.trackId
          LEFT JOIN service_tracks st ON st.id = s.serviceTrackRowId
         WHERE s.deviceId = :deviceId AND s.originRowId IS NULL AND s.id > :afterId
         ORDER BY s.id
         LIMIT :limit
        """,
    )
    abstract suspend fun segmentsToExport(
        deviceId: String,
        afterId: Long,
        limit: Int,
    ): List<SegmentExportRow>

    data class LikeEventExportRow(
        val id: Long,
        val event: String,
        val observedAt: Long,
        val serviceAddedAtMs: Long?,
        val trackKey: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackDuration: Long,
        val service: String?,
        val serviceTrackId: String?,
        val isrc: String?,
    )

    @Query(
        """
        SELECT e.id AS id, e.event AS event, e.observedAt AS observedAt,
               e.serviceAddedAtMs AS serviceAddedAtMs,
               t.trackKey AS trackKey, t.title AS title, t.artist AS artist,
               t.album AS album, t.durationMs AS trackDuration,
               st.service AS service, st.serviceTrackId AS serviceTrackId, st.isrc AS isrc
          FROM like_events e
          JOIN service_tracks st ON st.id = e.serviceTrackRowId
          JOIN tracks t ON t.id = st.trackId
         WHERE e.id > :afterId
         ORDER BY e.id
         LIMIT :limit
        """,
    )
    abstract suspend fun likeEventsToExport(afterId: Long, limit: Int): List<LikeEventExportRow>

    data class LikeExportRow(
        val id: Long,
        val serviceAddedAtMs: Long?,
        val firstSeenLikedAt: Long,
        val lastSeenLikedAt: Long,
        val unlikedAt: Long?,
        val trackKey: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackDuration: Long,
        val service: String?,
        val serviceTrackId: String?,
        val isrc: String?,
    )

    @Query(
        """
        SELECT l.id AS id, l.serviceAddedAtMs AS serviceAddedAtMs,
               l.firstSeenLikedAt AS firstSeenLikedAt, l.lastSeenLikedAt AS lastSeenLikedAt,
               l.unlikedAt AS unlikedAt,
               t.trackKey AS trackKey, t.title AS title, t.artist AS artist,
               t.album AS album, t.durationMs AS trackDuration,
               st.service AS service, st.serviceTrackId AS serviceTrackId, st.isrc AS isrc
          FROM likes l
          JOIN service_tracks st ON st.id = l.serviceTrackRowId
          JOIN tracks t ON t.id = st.trackId
         WHERE l.id > :afterId
         ORDER BY l.id
         LIMIT :limit
        """,
    )
    abstract suspend fun likesToExport(afterId: Long, limit: Int): List<LikeExportRow>

    @Query(
        "SELECT * FROM parameter_history WHERE id > :afterId ORDER BY id LIMIT :limit",
    )
    abstract suspend fun parameterHistoryToExport(
        afterId: Long,
        limit: Int,
    ): List<ParameterHistoryEntity>

    data class MemberExportRow(
        val id: Long,
        val joinedAt: Long,
        val leftAt: Long?,
        val joinReason: String,
        val leaveReason: String?,
        val rankAtJoin: Int,
        val rankAtLeave: Int?,
        val trackKey: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackDuration: Long,
        val service: String?,
        val serviceTrackId: String?,
        val isrc: String?,
    )

    /**
     * Membership joined to *a* service identifier for the track, or none.
     *
     * `MIN(st.id)` picks one deterministically when a canonical track has
     * several service rows — the album version and the single version are
     * different URIs and the same song. Which one is named does not matter,
     * because the receiver resolves the canonical track either way; what
     * matters is that the same one is named every time, or two exports of an
     * unchanged database would differ.
     */
    @Query(
        """
        SELECT m.id AS id, m.joinedAt AS joinedAt, m.leftAt AS leftAt,
               m.joinReason AS joinReason, m.leaveReason AS leaveReason,
               m.rankAtJoin AS rankAtJoin, m.rankAtLeave AS rankAtLeave,
               t.trackKey AS trackKey, t.title AS title, t.artist AS artist,
               t.album AS album, t.durationMs AS trackDuration,
               st.service AS service, st.serviceTrackId AS serviceTrackId, st.isrc AS isrc
          FROM evergreen_membership m
          JOIN tracks t ON t.id = m.trackId
          LEFT JOIN service_tracks st
            ON st.id = (SELECT MIN(id) FROM service_tracks WHERE trackId = m.trackId)
         WHERE m.id > :afterId
         ORDER BY m.id
         LIMIT :limit
        """,
    )
    abstract suspend fun membershipToExport(afterId: Long, limit: Int): List<MemberExportRow>

    @Query("SELECT * FROM snapshots ORDER BY monthKey")
    abstract suspend fun snapshotsToExport(): List<SnapshotEntity>

    data class SnapshotEntryExportRow(
        val id: Long,
        val rank: Int,
        val score: Double,
        val entryServiceTrackId: String?,
        val entryTitle: String?,
        val entryArtist: String?,
        val inPlaylist: Boolean,
        val trackKey: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackDuration: Long,
        val service: String?,
        val serviceTrackId: String?,
        val isrc: String?,
    )

    @Query(
        """
        SELECT e.id AS id, e.`rank` AS `rank`, e.score AS score,
               e.serviceTrackId AS entryServiceTrackId, e.title AS entryTitle,
               e.artist AS entryArtist, e.inPlaylist AS inPlaylist,
               t.trackKey AS trackKey, t.title AS title, t.artist AS artist,
               t.album AS album, t.durationMs AS trackDuration,
               st.service AS service, st.serviceTrackId AS serviceTrackId, st.isrc AS isrc
          FROM snapshot_entries e
          JOIN tracks t ON t.id = e.trackId
          LEFT JOIN service_tracks st
            ON st.id = (SELECT MIN(id) FROM service_tracks WHERE trackId = e.trackId)
         WHERE e.snapshotId = :snapshotId AND e.id > :afterId
         ORDER BY e.id
         LIMIT :limit
        """,
    )
    abstract suspend fun snapshotEntriesToExport(
        snapshotId: Long,
        afterId: Long,
        limit: Int,
    ): List<SnapshotEntryExportRow>

    // --- Import: resolving a track without trusting a row id -----------------

    @Query("SELECT id FROM service_tracks WHERE service = :service AND serviceTrackId = :serviceTrackId")
    abstract suspend fun findServiceTrackRowId(service: String, serviceTrackId: String): Long?

    @Query("SELECT trackId FROM service_tracks WHERE id = :rowId")
    abstract suspend fun trackIdOfServiceTrack(rowId: Long): Long

    @Query("SELECT id FROM tracks WHERE trackKey = :trackKey")
    abstract suspend fun findTrackIdByKey(trackKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTrackIfNew(track: TrackEntity): Long

    @Query(
        """
        UPDATE tracks
           SET lastSeenAt = :at,
               durationMs = CASE WHEN durationMs = 0 AND :durationMs > 0 THEN :durationMs
                                 ELSE durationMs END
         WHERE id = :id
        """,
    )
    abstract suspend fun touchTrack(id: Long, at: Long, durationMs: Long)

    /**
     * The canonical track for a fact that names no service identifier.
     *
     * Plenty of segments have none — a media session that published no URI —
     * and they still name a song. "Insert and ignore a conflict, then look it
     * up" rather than "look up, then insert": the unique index on `trackKey`
     * decides, so the form is safe even under a race.
     *
     * `lastSeenAt` is **not** advanced here, and that is deliberate. An
     * arriving fact is an observation the *other* device made; moving this
     * device's "last seen" to now would be claiming to have seen something it
     * did not. The duration is filled only when it was unknown, for the same
     * reason the like sync fills it: 0 means "never reported", and overwriting
     * an observed length with another source's makes percentages quietly
     * inconsistent between rows.
     */
    @Transaction
    open suspend fun resolveTrackByKeyOnly(
        trackKey: String,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        firstSeenAt: Long,
    ): Long {
        val inserted = insertTrackIfNew(
            TrackEntity(
                trackKey = trackKey,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                firstSeenAt = firstSeenAt,
                lastSeenAt = firstSeenAt,
            ),
        )
        if (inserted != -1L) return inserted
        val existing = requireNotNull(findTrackIdByKey(trackKey)) {
            "track neither inserted nor found"
        }
        if (durationMs > 0) touchTrackDurationOnly(existing, durationMs)
        return existing
    }

    @Query(
        """
        UPDATE tracks
           SET durationMs = CASE WHEN durationMs = 0 THEN :durationMs ELSE durationMs END
         WHERE id = :id
        """,
    )
    abstract suspend fun touchTrackDurationOnly(id: Long, durationMs: Long)

    // --- Import: inserting a fact that may already be here --------------------

    /**
     * Inserts a segment unless `(deviceId, originRowId)` is already present.
     *
     * `IGNORE` rather than a lookup first. The unique index decides, which
     * means two ways of getting this wrong are closed at once: a watermark that
     * is somehow behind, and two imports racing. It returns -1 when the row was
     * already there, which is what the "changed nothing" count is built from.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSegmentIfNew(segment: ListeningSegmentEntity): Long

    /**
     * True when this exact like event is already recorded.
     *
     * The identity is content, with **no device in it** — a like is an
     * observation of Spotify's state rather than of a device, so two devices
     * seeing one like are seeing one fact twice. A device column here would
     * make every like arrive twice and be impossible to collapse
     * (`docs/SYNC_FOUNDATION.md` Part 4).
     *
     * `IS` rather than `=` for the nullable column, because `NULL = NULL` is
     * NULL in SQL and two events that both lack a service timestamp would
     * otherwise never match each other.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM like_events
             WHERE serviceTrackRowId = :serviceTrackRowId
               AND event = :event
               AND observedAt = :observedAt
               AND serviceAddedAtMs IS :serviceAddedAtMs
        )
        """,
    )
    abstract suspend fun hasLikeEvent(
        serviceTrackRowId: Long,
        event: String,
        observedAt: Long,
        serviceAddedAtMs: Long?,
    ): Boolean

    @Insert
    abstract suspend fun insertLikeEvent(event: LikeEventEntity)

    /**
     * Records an incoming like event unless it is already here.
     *
     * @return true when a row was written.
     */
    @Transaction
    open suspend fun applyLikeEvent(
        serviceTrackRowId: Long,
        event: LikeEventType,
        observedAt: Long,
        serviceAddedAtMs: Long?,
    ): Boolean {
        if (hasLikeEvent(serviceTrackRowId, event.name, observedAt, serviceAddedAtMs)) return false
        insertLikeEvent(
            LikeEventEntity(
                serviceTrackRowId = serviceTrackRowId,
                event = event,
                observedAt = observedAt,
                serviceAddedAtMs = serviceAddedAtMs,
            ),
        )
        return true
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertLikeIfNew(like: LikeEntity): Long

    @Query(
        """
        UPDATE likes
           SET serviceAddedAtMs = :serviceAddedAtMs,
               firstSeenLikedAt = :firstSeenLikedAt,
               lastSeenLikedAt = :lastSeenLikedAt,
               unlikedAt = :unlikedAt
         WHERE serviceTrackRowId = :serviceTrackRowId
        """,
    )
    abstract suspend fun updateLikeState(
        serviceTrackRowId: Long,
        serviceAddedAtMs: Long?,
        firstSeenLikedAt: Long,
        lastSeenLikedAt: Long,
        unlikedAt: Long?,
    )

    /**
     * Writes the producer's current like state for one service track.
     *
     * An upsert with the producer's own timestamps rather than `now`, which is
     * what makes it idempotent: applying the same fact twice writes the same
     * values. `recordLiked` on [SpotifyDao] is the wrong tool here — it stamps
     * the current time and writes an event on a transition, which is right when
     * observing Spotify and wrong when copying somebody else's observation.
     */
    @Transaction
    open suspend fun applyLikeState(
        serviceTrackRowId: Long,
        serviceAddedAtMs: Long?,
        firstSeenLikedAt: Long,
        lastSeenLikedAt: Long,
        unlikedAt: Long?,
    ) {
        val inserted = insertLikeIfNew(
            LikeEntity(
                serviceTrackRowId = serviceTrackRowId,
                serviceAddedAtMs = serviceAddedAtMs,
                firstSeenLikedAt = firstSeenLikedAt,
                lastSeenLikedAt = lastSeenLikedAt,
                unlikedAt = unlikedAt,
            ),
        )
        if (inserted == -1L) {
            updateLikeState(
                serviceTrackRowId = serviceTrackRowId,
                serviceAddedAtMs = serviceAddedAtMs,
                firstSeenLikedAt = firstSeenLikedAt,
                lastSeenLikedAt = lastSeenLikedAt,
                unlikedAt = unlikedAt,
            )
        }
    }

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM parameter_history
             WHERE changedAt = :changedAt AND parametersHash = :parametersHash
        )
        """,
    )
    abstract suspend fun hasParameterEntry(changedAt: Long, parametersHash: String): Boolean

    @Insert
    abstract suspend fun insertParameterEntry(entry: ParameterHistoryEntity)

    @Transaction
    open suspend fun applyParameterEntry(entry: ParameterHistoryEntity): Boolean {
        if (hasParameterEntry(entry.changedAt, entry.parametersHash)) return false
        insertParameterEntry(entry)
        return true
    }

    @Query(
        "SELECT id FROM evergreen_membership WHERE trackId = :trackId AND joinedAt = :joinedAt",
    )
    abstract suspend fun findMembershipId(trackId: Long, joinedAt: Long): Long?

    @Insert
    abstract suspend fun insertMembership(row: EvergreenMembershipEntity)

    @Query(
        """
        UPDATE evergreen_membership
           SET leftAt = :leftAt, joinReason = :joinReason, leaveReason = :leaveReason,
               rankAtJoin = :rankAtJoin, rankAtLeave = :rankAtLeave
         WHERE id = :id
        """,
    )
    abstract suspend fun updateMembership(
        id: Long,
        leftAt: Long?,
        joinReason: String,
        leaveReason: String?,
        rankAtJoin: Int,
        rankAtLeave: Int?,
    )

    /**
     * Upserts a membership row on `(trackId, joinedAt)`.
     *
     * An upsert and not an insert, because this is the one fact type that
     * changes after it is written: a row is opened at join and closed later by
     * setting `leftAt`. There is therefore no ascending id that could describe
     * "what is new", which is why a batch carries every membership row.
     */
    @Transaction
    open suspend fun applyMembership(row: EvergreenMembershipEntity): Boolean {
        val existing = findMembershipId(row.trackId, row.joinedAt)
        if (existing == null) {
            insertMembership(row)
            return true
        }
        updateMembership(
            id = existing,
            leftAt = row.leftAt,
            joinReason = row.joinReason.name,
            leaveReason = row.leaveReason?.name,
            rankAtJoin = row.rankAtJoin,
            rankAtLeave = row.rankAtLeave,
        )
        return false
    }

    @Query("SELECT takenAt FROM snapshots WHERE id = :id")
    abstract suspend fun snapshotTakenAt(id: Long): Long?

    @Query("SELECT id FROM snapshots WHERE monthKey = :monthKey")
    abstract suspend fun findSnapshotId(monthKey: String): Long?

    @Insert
    abstract suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Query(
        """
        UPDATE snapshots
           SET label = :label, takenAt = :takenAt, parametersJson = :parametersJson,
               playlistSize = :playlistSize, trackCount = :trackCount,
               exportedPlaylistId = :exportedPlaylistId, exportedAt = :exportedAt
         WHERE id = :id
        """,
    )
    abstract suspend fun updateSnapshot(
        id: Long,
        label: String,
        takenAt: Long,
        parametersJson: String,
        playlistSize: Int,
        trackCount: Int,
        exportedPlaylistId: String?,
        exportedAt: Long?,
    )

    @Query("DELETE FROM snapshot_entries WHERE snapshotId = :snapshotId")
    abstract suspend fun deleteSnapshotEntries(snapshotId: Long)

    @Insert
    abstract suspend fun insertSnapshotEntry(entry: SnapshotEntryEntity)

    /**
     * Upserts a snapshot on its month, and clears its entries for reloading.
     *
     * The one place in a delta import that deletes anything, and it deletes
     * only from `snapshot_entries` — a derived record of a ranking, whose
     * incoming copy is about to replace it row for row. Facts are never
     * deleted, here or anywhere.
     *
     * @return the local row id of the snapshot, for its entries to hang off.
     */
    @Transaction
    open suspend fun applySnapshot(snapshot: SnapshotEntity): Long {
        val existing = findSnapshotId(snapshot.monthKey)
        if (existing == null) return insertSnapshot(snapshot)
        updateSnapshot(
            id = existing,
            label = snapshot.label,
            takenAt = snapshot.takenAt,
            parametersJson = snapshot.parametersJson,
            playlistSize = snapshot.playlistSize,
            trackCount = snapshot.trackCount,
            exportedPlaylistId = snapshot.exportedPlaylistId,
            exportedAt = snapshot.exportedAt,
        )
        deleteSnapshotEntries(existing)
        return existing
    }
}
