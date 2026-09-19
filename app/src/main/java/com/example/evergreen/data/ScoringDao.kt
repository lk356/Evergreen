package com.example.evergreen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.evergreen.scoring.ScoringParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 3's reads and writes, kept out of the capture and Spotify DAOs so that
 * neither of those paths changes when scoring does.
 *
 * Carries the scoring parameters, the score cache, the Evergreen membership
 * record and the bulk reads the scorer needs. The snapshot queries join it in
 * step 15.
 */
@Dao
abstract class ScoringDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    protected abstract suspend fun syncState(key: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    protected abstract fun syncStateFlow(key: String): Flow<SyncStateEntity?>

    /**
     * The settings in force, or the shipped defaults if none have been stored
     * or the stored text is unreadable.
     *
     * Falling back rather than throwing is deliberate: a malformed string here
     * — which the debug screen's editor makes possible — should not stop the
     * app scoring at all. The editor validates before saving, so the user is
     * told what is wrong at the point they can fix it.
     */
    open suspend fun scoringParameters(): ScoringParameters =
        ScoringParameters.fromJsonOrDefault(syncState(SyncKeys.SCORING_PARAMETERS)?.valueText)

    /** The settings in force, re-emitted whenever they are changed. */
    open fun scoringParametersFlow(): Flow<ScoringParameters> =
        syncStateFlow(SyncKeys.SCORING_PARAMETERS).map { row ->
            ScoringParameters.fromJsonOrDefault(row?.valueText)
        }

    /**
     * Stores new settings, and records the change in [parameter_history].
     *
     * Note what this does *not* do: it writes no scores and touches no
     * migration. Changing a parameter invalidates the score cache because every
     * cached row carries the hash of the parameters that produced it, so the
     * next recompute notices the mismatch and rebuilds (decisions 1a and 1d).
     *
     * The two writes are one transaction because the log is only worth having
     * if it cannot disagree with the value in force. A saved parameter with no
     * log entry would be a silent hole in the record; a log entry for a value
     * that was never saved would be a lie about it.
     *
     * Saving the settings that are already in force writes nothing and returns
     * false. Opening the settings screen and closing it again is not a change,
     * and a log full of no-ops is a log nobody reads.
     *
     * @return true when something actually changed.
     */
    @Transaction
    open suspend fun putScoringParameters(parameters: ScoringParameters, at: Long): Boolean {
        val problems = parameters.problems()
        require(problems.isEmpty()) { problems.joinToString(separator = "; ") }

        val json = parameters.toJson()
        // Null when nothing has ever been stored. The settings in force were
        // then the shipped defaults, which is a fact that exists nowhere else
        // and stops being true the instant this write lands -- so it is what
        // gets recorded as the previous value, rather than null.
        val stored = syncState(SyncKeys.SCORING_PARAMETERS)?.valueText
        val previous = stored ?: ScoringParameters().toJson()
        if (previous == json) return false

        putSyncState(SyncStateEntity(key = SyncKeys.SCORING_PARAMETERS, valueText = json))
        insertParameterChange(
            ParameterHistoryEntity(
                changedAt = at,
                parametersJson = json,
                parametersHash = parameters.hash(),
                previousJson = previous,
            ),
        )
        return true
    }

    // --- The parameter change log ---------------------------------------------

    @Insert
    protected abstract suspend fun insertParameterChange(row: ParameterHistoryEntity)

    /** Every recorded change, newest first. */
    @Query("SELECT * FROM parameter_history ORDER BY changedAt DESC, id DESC")
    abstract fun parameterHistory(): Flow<List<ParameterHistoryEntity>>

    /** Every recorded change, newest first, as a one-shot read. */
    @Query("SELECT * FROM parameter_history ORDER BY changedAt DESC, id DESC")
    abstract suspend fun parameterHistoryOnce(): List<ParameterHistoryEntity>

    @Query("SELECT COUNT(*) FROM parameter_history")
    abstract suspend fun parameterHistoryCount(): Int

    /**
     * The settings that were in force at [atMs].
     *
     * The most recent change at or before that instant, or null when the log
     * starts after it — in which case the caller should fall back to the
     * `previousJson` of the earliest row, which is what was in force before
     * anything was ever recorded.
     */
    @Query(
        """
        SELECT * FROM parameter_history
         WHERE changedAt <= :atMs
         ORDER BY changedAt DESC, id DESC
         LIMIT 1
        """,
    )
    abstract suspend fun parametersInForceAt(atMs: Long): ParameterHistoryEntity?

    /** The earliest recorded change, whose `previousJson` predates the log. */
    @Query("SELECT * FROM parameter_history ORDER BY changedAt, id LIMIT 1")
    abstract suspend fun earliestParameterChange(): ParameterHistoryEntity?

    /** True once settings have been stored, i.e. the user has edited them. */
    open suspend fun hasStoredScoringParameters(): Boolean =
        syncState(SyncKeys.SCORING_PARAMETERS)?.valueText != null

    // --- Small sync_state values the Evergreen sync keeps ----------------------

    open suspend fun syncStateMs(key: String): Long? = syncState(key)?.valueMs

    open suspend fun syncStateText(key: String): String? = syncState(key)?.valueText

    open suspend fun putSyncMs(key: String, at: Long) =
        putSyncState(SyncStateEntity(key = key, valueMs = at))

    open suspend fun putSyncText(key: String, text: String) =
        putSyncState(SyncStateEntity(key = key, valueText = text))

    open fun syncValueFlow(key: String): Flow<SyncStateEntity?> = syncStateFlow(key)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    abstract suspend fun clearSyncState(key: String)

    /**
     * Reads a one-shot flag and clears it, in one transaction.
     *
     * "Did the parameters change since the last write" is asked once, by the
     * sync worker, and must not be answered twice -- two workers racing would
     * otherwise both suppress their notification, or both post one.
     */
    @Transaction
    open suspend fun takeSyncStateMs(key: String): Long? {
        val value = syncState(key)?.valueMs
        if (value != null) clearSyncState(key)
        return value
    }

    // --- The score cache ------------------------------------------------------

    @Query("SELECT * FROM scores ORDER BY rank")
    abstract suspend fun cachedScores(): List<ScoreEntity>

    @Query("SELECT * FROM scores ORDER BY rank")
    abstract fun cachedScoresFlow(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE trackId = :trackId LIMIT 1")
    abstract suspend fun cachedScore(trackId: Long): ScoreEntity?

    @Query("SELECT COUNT(*) FROM scores")
    abstract suspend fun cachedScoreCount(): Int

    @Query("SELECT COUNT(*) FROM scores")
    abstract fun cachedScoreCountFlow(): Flow<Int>

    /** The hash the cache was built under, or null when it is empty or mixed. */
    @Query("SELECT parametersHash FROM scores LIMIT 1")
    abstract suspend fun cachedParametersHash(): String?

    @Query("SELECT COUNT(*) FROM scores WHERE parametersHash != :hash")
    protected abstract suspend fun rowsUnderOtherParameters(hash: String): Int

    @Query("DELETE FROM scores")
    protected abstract suspend fun deleteAllScores()

    /**
     * Empties the score cache.
     *
     * Public because the mirror import has to: the rows in an imported cache
     * were computed on another device, and the point of a mirror is that this
     * device recomputes them and gets the same ranking. Carrying the cache
     * across would hide exactly the disagreement worth finding.
     *
     * Safe at any moment, which is the practical form of invariant 1a — a
     * score is computed, never accumulated, so an empty cache costs one
     * recompute and loses nothing.
     */
    open suspend fun dropScoreCache() = deleteAllScores()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertScores(scores: List<ScoreEntity>)

    /**
     * Replaces the cache wholesale.
     *
     * Deleting first rather than upserting: a track that has been unliked, or
     * merged away, must not linger with the rank it held last time. A cache
     * that only ever grew would quietly keep ranking tracks that no longer
     * exist.
     */
    @Transaction
    open suspend fun replaceScores(scores: List<ScoreEntity>) {
        deleteAllScores()
        insertScores(scores)
    }

    /**
     * True when the cache cannot be trusted for these settings.
     *
     * This is the check that keeps invariant 1a honest across the cache. A
     * score is a pure function of its inputs and the parameters; a cached row
     * computed under different parameters is not a stale copy of the right
     * answer, it is the right answer to a different question. So the test is
     * equality of the hash, not age.
     *
     * An empty cache is stale by definition, which is what makes "drop the
     * table and rebuild" always a valid repair.
     */
    open suspend fun isCacheStale(parameters: ScoringParameters): Boolean {
        if (cachedScoreCount() == 0) return true
        return rowsUnderOtherParameters(parameters.hash()) > 0
    }

    /**
     * The ranked list with enough of each track to read it, for the Scores tab.
     *
     * Joined here rather than looked up per row, because the list shows four
     * hundred tracks and a query per row would make scrolling stutter on the
     * NW-A307.
     */
    @Query(
        """
        SELECT s.trackId AS trackId,
               s.score AS score,
               -- `rank` is a reserved word in SQLite (the window function), so
               -- the alias has to be quoted or the parser stops here.
               s.`rank` AS `rank`,
               t.title AS title,
               t.artist AS artist,
               t.durationMs AS durationMs,
               EXISTS(
                   SELECT 1 FROM evergreen_membership m
                    WHERE m.trackId = s.trackId AND m.leftAt IS NULL
               ) AS inPlaylist
          FROM scores s
          JOIN tracks t ON t.id = s.trackId
         ORDER BY s.`rank`
        """,
    )
    abstract fun rankedTracks(): Flow<List<RankedTrackRow>>

    /** The liked service URI for a track, preferring one that is liked now. */
    @Query(
        """
        SELECT st.serviceTrackId
          FROM service_tracks st
          LEFT JOIN likes l ON l.serviceTrackRowId = st.id
         WHERE st.trackId = :trackId
           AND st.service = :service
           AND st.serviceTrackId LIKE :trackUriPrefix || '%'
         ORDER BY (l.unlikedAt IS NULL AND l.id IS NOT NULL) DESC, st.id
         LIMIT 1
        """,
    )
    abstract suspend fun preferredServiceUri(
        trackId: Long,
        service: String,
        trackUriPrefix: String,
    ): String?

    /**
     * [preferredServiceUri] for many tracks at once.
     *
     * Ordered so the first row for each track is the preferred one; the caller
     * keeps the first and discards the rest. One query rather than four hundred
     * matters on the NW-A307, and a snapshot needs a URI for every ranked
     * track, not just the hundred in the playlist.
     */
    @Query(
        """
        SELECT st.trackId AS trackId, st.serviceTrackId AS serviceTrackId
          FROM service_tracks st
          LEFT JOIN likes l ON l.serviceTrackRowId = st.id
         WHERE st.trackId IN (:trackIds)
           AND st.service = :service
           AND st.serviceTrackId LIKE :trackUriPrefix || '%'
         ORDER BY st.trackId, (l.unlikedAt IS NULL AND l.id IS NOT NULL) DESC, st.id
        """,
    )
    abstract suspend fun preferredServiceUris(
        trackIds: List<Long>,
        service: String,
        trackUriPrefix: String,
    ): List<TrackServiceUri>

    // --- Evergreen membership -------------------------------------------------

    @Query("SELECT * FROM evergreen_membership WHERE leftAt IS NULL")
    abstract suspend fun currentMembership(): List<EvergreenMembershipEntity>

    @Query("SELECT trackId FROM evergreen_membership WHERE leftAt IS NULL")
    abstract suspend fun currentMemberTrackIds(): List<Long>

    @Query("SELECT COUNT(*) FROM evergreen_membership WHERE leftAt IS NULL")
    abstract fun currentMemberCountFlow(): Flow<Int>

    @Query(
        """
        SELECT * FROM evergreen_membership
         ORDER BY COALESCE(leftAt, joinedAt) DESC, id DESC
         LIMIT :limit
        """,
    )
    abstract fun recentMembershipChanges(limit: Int): Flow<List<EvergreenMembershipEntity>>

    /**
     * Joins and leaves since [sinceMs], readable — with the titles attached.
     *
     * The home screen's "what changed" reads this. Joined here rather than
     * looked up per row for the same reason the ranked list is: a query per row
     * makes a list stutter on the NW-A307.
     *
     * A row appears once, dated by whichever of its two timestamps is later, so
     * a track that joined in July and left last night shows up as last night's
     * departure rather than twice.
     */
    @Query(
        """
        SELECT m.trackId AS trackId,
               m.joinedAt AS joinedAt,
               m.leftAt AS leftAt,
               m.joinReason AS joinReason,
               m.leaveReason AS leaveReason,
               m.rankAtJoin AS rankAtJoin,
               m.rankAtLeave AS rankAtLeave,
               t.title AS title,
               t.artist AS artist
          FROM evergreen_membership m
          JOIN tracks t ON t.id = m.trackId
         WHERE COALESCE(m.leftAt, m.joinedAt) >= :sinceMs
         ORDER BY COALESCE(m.leftAt, m.joinedAt) DESC, m.id DESC
         LIMIT :limit
        """,
    )
    abstract fun membershipChangesSince(sinceMs: Long, limit: Int): Flow<List<MembershipChangeRow>>

    /** When the playlist last actually changed, for "nothing has moved since". */
    @Query("SELECT MAX(COALESCE(leftAt, joinedAt)) FROM evergreen_membership")
    abstract fun lastMembershipChangeAtMs(): Flow<Long?>

    /**
     * Every month this track has appeared in a snapshot, oldest first.
     *
     * "Following one song through time" on the track detail screen. Read from
     * `snapshot_entries` rather than recomputed, because a snapshot is the
     * record of what was actually true that month under the settings then in
     * force — recomputing would answer a different question.
     */
    @Query(
        """
        SELECT s.monthKey AS monthKey,
               s.label AS label,
               e.`rank` AS `rank`,
               e.score AS score,
               e.inPlaylist AS inPlaylist
          FROM snapshot_entries e
          JOIN snapshots s ON s.id = e.snapshotId
         WHERE e.trackId = :trackId
         ORDER BY s.monthKey
        """,
    )
    abstract suspend fun trackMonthHistory(trackId: Long): List<TrackMonthRow>

    @Insert
    protected abstract suspend fun insertMembership(row: EvergreenMembershipEntity)

    @Query(
        """
        UPDATE evergreen_membership
           SET leftAt = :at, leaveReason = :reason, rankAtLeave = :rank
         WHERE trackId = :trackId AND leftAt IS NULL
        """,
    )
    protected abstract suspend fun closeMembership(
        trackId: Long,
        at: Long,
        reason: MembershipReason,
        rank: Int?,
    )

    /**
     * Records the joins and leaves a sync applied.
     *
     * Rows are closed rather than deleted, so "when did this song leave, and
     * why" stays answerable — the same discipline as the like archive.
     */
    @Transaction
    open suspend fun recordMembershipChanges(
        joined: List<Triple<Long, MembershipReason, Int>>,
        left: List<Triple<Long, MembershipReason, Int?>>,
        at: Long,
    ) {
        left.forEach { (trackId, reason, rank) -> closeMembership(trackId, at, reason, rank) }
        joined.forEach { (trackId, reason, rank) ->
            insertMembership(
                EvergreenMembershipEntity(
                    trackId = trackId,
                    joinedAt = at,
                    joinReason = reason,
                    rankAtJoin = rank,
                ),
            )
        }
    }

    // --- Snapshots ------------------------------------------------------------

    @Query("SELECT * FROM snapshots WHERE monthKey = :monthKey LIMIT 1")
    abstract suspend fun snapshotForMonth(monthKey: String): SnapshotEntity?

    @Query("SELECT * FROM snapshots WHERE id = :id LIMIT 1")
    abstract suspend fun snapshotById(id: Long): SnapshotEntity?

    /**
     * The snapshot immediately before this month's, or null when it is the
     * first.
     *
     * Consecutive snapshots subtract: "new this month" and "gone since last
     * month" are a set difference over data that is already stored, which is
     * why a month view needs no new table to lead with its diff. Ordered by
     * `monthKey` rather than `takenAt` because the key is what defines the
     * sequence -- a snapshot taken late still belongs to its own month.
     */
    @Query(
        """
        SELECT * FROM snapshots
         WHERE monthKey < :monthKey
         ORDER BY monthKey DESC
         LIMIT 1
        """,
    )
    abstract suspend fun snapshotBefore(monthKey: String): SnapshotEntity?

    /** Titles for the snapshot's entries, so a snapshot reads without a join later. */
    @Query("SELECT id, title, artist FROM tracks WHERE id IN (:trackIds)")
    abstract suspend fun trackTitles(trackIds: List<Long>): List<TrackTitle>

    @Query(
        """
        SELECT id, monthKey, label, takenAt, playlistSize, trackCount,
               exportedPlaylistId, exportedAt
          FROM snapshots ORDER BY monthKey DESC
        """,
    )
    abstract fun snapshots(): Flow<List<SnapshotSummary>>

    @Query("SELECT COUNT(*) FROM snapshots")
    abstract suspend fun snapshotCount(): Int

    @Query(
        """
        SELECT * FROM snapshot_entries
         WHERE snapshotId = :snapshotId
         ORDER BY `rank`
        """,
    )
    abstract suspend fun snapshotEntries(snapshotId: Long): List<SnapshotEntryEntity>

    /** The top of a snapshot, in order, for exporting it to a playlist. */
    @Query(
        """
        SELECT * FROM snapshot_entries
         WHERE snapshotId = :snapshotId AND inPlaylist = 1
         ORDER BY `rank`
        """,
    )
    abstract suspend fun snapshotPlaylistEntries(snapshotId: Long): List<SnapshotEntryEntity>

    @Insert
    protected abstract suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Insert
    protected abstract suspend fun insertSnapshotEntries(entries: List<SnapshotEntryEntity>)

    @Query("UPDATE snapshots SET exportedPlaylistId = :playlistId, exportedAt = :at WHERE id = :id")
    abstract suspend fun recordSnapshotExport(id: Long, playlistId: String, at: Long)

    /**
     * Stores a snapshot and its whole ranking in one transaction.
     *
     * One or the other alone would be worse than neither: a snapshot with no
     * entries reads as a month in which nothing was alive, which is a claim
     * about the user's listening rather than a missing row.
     */
    @Transaction
    open suspend fun saveSnapshot(
        snapshot: SnapshotEntity,
        entries: (Long) -> List<SnapshotEntryEntity>,
    ): Long {
        val id = insertSnapshot(snapshot)
        insertSnapshotEntries(entries(id))
        return id
    }

    // --- Scoring inputs -------------------------------------------------------

    /** Canonical tracks with at least one currently liked service row. */
    @Query(
        """
        SELECT DISTINCT st.trackId FROM likes l
          JOIN service_tracks st ON st.id = l.serviceTrackRowId
         WHERE l.unlikedAt IS NULL
        """,
    )
    abstract suspend fun likedTrackIds(): List<Long>

    /**
     * Segments for scoring, bounded by the load window.
     *
     * The window is what keeps this usable on the NW-A307 with 77,000 imported
     * rows in the table. See [com.example.evergreen.scoring.Ranker.loadFromMs].
     */
    @Query(
        """
        SELECT * FROM listening_segments
         WHERE trackId IN (:trackIds) AND endedAtMs >= :fromMs
         ORDER BY endedAtMs
        """,
    )
    abstract suspend fun segmentsForScoring(trackIds: List<Long>, fromMs: Long): List<ListeningSegmentEntity>

    /**
     * The timestamps of every DJ commentary segment in the window.
     *
     * Commentary is anything whose service URI is not a track URI; see
     * `docs/PLAN.md` on the Phase 2 DJ session.
     */
    @Query(
        """
        SELECT s.endedAtMs FROM listening_segments s
          JOIN service_tracks st ON st.id = s.serviceTrackRowId
         WHERE st.serviceTrackId NOT LIKE :trackUriPrefix || '%'
           AND s.endedAtMs >= :fromMs
         ORDER BY s.endedAtMs
        """,
    )
    abstract suspend fun commentaryTimestamps(trackUriPrefix: String, fromMs: Long): List<Long>

    @Query("SELECT * FROM service_tracks")
    abstract suspend fun allServiceTracks(): List<ServiceTrackEntity>

    @Query("SELECT id, durationMs FROM tracks WHERE id IN (:trackIds)")
    abstract suspend fun trackDurations(trackIds: List<Long>): List<TrackDuration>

    /**
     * Like events for scoring, dated by when the user actually liked the track.
     *
     * `serviceAddedAtMs` is Spotify's own `added_at`, falling back to when this
     * app first observed the like (decision 10.4). Without the fallback every
     * existing like would be dated the day the app was installed and would give
     * all 380 tracks the same three-play boost, fading together and drowning
     * out the listening history for months.
     */
    @Query(
        """
        SELECT st.trackId AS trackId,
               e.event AS event,
               COALESCE(e.serviceAddedAtMs, e.observedAt) AS atMs
          FROM like_events e
          JOIN service_tracks st ON st.id = e.serviceTrackRowId
         WHERE st.trackId IN (:trackIds)
         ORDER BY atMs
        """,
    )
    abstract suspend fun likeEventsForScoring(trackIds: List<Long>): List<LikeEventForScoring>
}

/** A canonical track's best known length. */
data class TrackDuration(val id: Long, val durationMs: Long)

/** A canonical track's display fields. */
data class TrackTitle(val id: Long, val title: String?, val artist: String?)

/** One service URI for a track, in preference order. */
data class TrackServiceUri(val trackId: Long, val serviceTrackId: String)

/** A snapshot with the size of its ranking, for the list on the debug tab. */
data class SnapshotSummary(
    val id: Long,
    val monthKey: String,
    val label: String,
    val takenAt: Long,
    val playlistSize: Int,
    val trackCount: Int,
    val exportedPlaylistId: String?,
    val exportedAt: Long?,
)

/** One row of the Scores tab's ranked list. */
data class RankedTrackRow(
    val trackId: Long,
    val score: Double,
    val rank: Int,
    val title: String?,
    val artist: String?,
    val durationMs: Long,
    val inPlaylist: Boolean,
)

/**
 * One join or leave, with enough of the track to read it.
 *
 * [leftAt] null means the track is still in the playlist, so the row describes
 * a join; otherwise it describes the departure.
 */
data class MembershipChangeRow(
    val trackId: Long,
    val joinedAt: Long,
    val leftAt: Long?,
    val joinReason: MembershipReason,
    val leaveReason: MembershipReason?,
    val rankAtJoin: Int,
    val rankAtLeave: Int?,
    val title: String?,
    val artist: String?,
) {
    /** Whichever of the two timestamps this row is really about. */
    val atMs: Long get() = leftAt ?: joinedAt

    val isJoin: Boolean get() = leftAt == null
}

/** One track's place in one month's snapshot. */
data class TrackMonthRow(
    val monthKey: String,
    val label: String,
    val rank: Int,
    val score: Double,
    val inPlaylist: Boolean,
)

/** One like or unlike, ready for the scorer. */
data class LikeEventForScoring(
    val trackId: Long,
    val event: LikeEventType,
    val atMs: Long,
)
