package com.example.evergreen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.evergreen.scoring.ScoringParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 3's reads and writes, kept out of the capture and Spotify DAOs so that
 * neither of those paths changes when scoring does.
 *
 * At this step it only carries the scoring parameters. The score cache, the
 * Evergreen membership record and the snapshots join it at schema v5.
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
}
