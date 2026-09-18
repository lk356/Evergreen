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
     * Stores new settings.
     *
     * Note what this does *not* do: it writes no scores and touches no
     * migration. Changing a parameter invalidates the score cache because every
     * cached row carries the hash of the parameters that produced it, so the
     * next recompute notices the mismatch and rebuilds (decisions 1a and 1d).
     */
    open suspend fun putScoringParameters(parameters: ScoringParameters) {
        val problems = parameters.problems()
        require(problems.isEmpty()) { problems.joinToString(separator = "; ") }
        putSyncState(
            SyncStateEntity(
                key = SyncKeys.SCORING_PARAMETERS,
                valueText = parameters.toJson(),
            ),
        )
    }

    /** True once settings have been stored, i.e. the user has edited them. */
    open suspend fun hasStoredScoringParameters(): Boolean =
        syncState(SyncKeys.SCORING_PARAMETERS)?.valueText != null

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

/** One like or unlike, ready for the scorer. */
data class LikeEventForScoring(
    val trackId: Long,
    val event: LikeEventType,
    val atMs: Long,
)
