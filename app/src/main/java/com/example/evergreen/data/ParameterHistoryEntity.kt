package com.example.evergreen.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every time the scoring parameters changed, and what they changed to.
 *
 * **Append-only, and not a cache.** Every other derived thing in this app can
 * be dropped and rebuilt — `scores` carries its parameters hash precisely so it
 * can be thrown away at any moment. This table is the opposite: it records
 * something that is not recoverable from anywhere else once it has happened.
 *
 * Before this existed, only the *current* parameters were stored, in one
 * `sync_state` row that each save overwrote, and past values survived only
 * inside whatever snapshots happened to be taken while they were in force. A
 * score means nothing without the settings that produced it, so the first
 * retune would have left a hole in the record of what every earlier score
 * meant — and Phase 4 is about to make retuning easy, with a settings screen
 * built for exactly that.
 *
 * It is also the one thing standing between the app and "the ranking as it
 * stood on any past date", which is otherwise already recomputable: segments
 * and `like_events` are append-only, `evergreen_membership` records every join
 * and leave with a timestamp, and a score is a pure function of
 * (segments, like events, parameters, now). Parameters were the only input with
 * no history. Now they have one.
 *
 * Rows are never updated and never deleted. It grows by one row per parameter
 * change, which over a lifetime of use is a handful.
 */
@Entity(
    tableName = "parameter_history",
    indices = [Index(value = ["changedAt"])],
)
data class ParameterHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** When the change was saved, in epoch milliseconds. */
    val changedAt: Long,

    /**
     * [com.example.evergreen.scoring.ScoringParameters.toJson] of the settings
     * that came into force at [changedAt].
     *
     * The same canonical JSON a snapshot stores, deliberately: the two have to
     * be the same shape, or a snapshot could not be matched to the entry in
     * this log that explains it.
     */
    val parametersJson: String,

    /**
     * [com.example.evergreen.scoring.ScoringParameters.hash] of [parametersJson].
     *
     * Stored rather than recomputed on read so that a row can be matched
     * against the `scores` cache's `parametersHash` directly — "which settings
     * produced the numbers currently on screen" becomes a lookup.
     */
    val parametersHash: String,

    /**
     * What was in force immediately before, or null for the first row.
     *
     * Redundant with the previous row's [parametersJson] in the ordinary case,
     * and kept anyway for the one case where it is not: the very first change.
     * Until then nothing was ever written, so the settings in force were the
     * shipped defaults of whatever build was installed — a fact that exists
     * nowhere else and would otherwise be lost the moment it stopped being
     * true.
     */
    val previousJson: String?,
)
