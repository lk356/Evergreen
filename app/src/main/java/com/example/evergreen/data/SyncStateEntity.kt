package com.example.evergreen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A tiny key/value table for "when did this last run".
 *
 * Deliberately not SharedPreferences: these values are read inside the same
 * transactions that write likes, and a sync that commits its rows but loses its
 * cursor would re-do work or, worse, skip it.
 *
 * Nothing secret goes here. Tokens live in encrypted storage outside the
 * database entirely, so exporting the database can never leak them.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val key: String,

    /** Epoch milliseconds, or null if the event has not happened yet. */
    val valueMs: Long? = null,

    /** Free-form small value for non-timestamp state. */
    val valueText: String? = null,
)

object SyncKeys {
    /** Completion time of the last full like sync. */
    const val LIKES_LAST_SYNCED_AT = "likes_last_synced_at"

    /** Completion time of the last ISRC backfill pass. */
    const val ISRC_LAST_BACKFILL_AT = "isrc_last_backfill_at"

    /**
     * When the API told us to stop asking — a 429 `Retry-After` or a
     * `QUOTA_EXCEEDED`. Work scheduled before this instant does nothing.
     */
    const val API_BACKOFF_UNTIL = "api_backoff_until"

    /** Why we are backing off, for the debug screen. */
    const val API_BACKOFF_REASON = "api_backoff_reason"

    /**
     * The scoring parameters, as one JSON object in `valueText`.
     *
     * Here rather than in a table of their own because they are a single small
     * value read as a unit, and because a snapshot stores the same JSON string
     * verbatim — the two have to be the same shape or a snapshot could not be
     * read back with the settings that produced it.
     */
    const val SCORING_PARAMETERS = "scoring_parameters"
}
