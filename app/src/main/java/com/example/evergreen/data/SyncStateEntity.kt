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

    /**
     * Prefix for "this export file has been imported", one row per file, keyed
     * by the SHA-256 of its contents.
     *
     * A prefix and many rows rather than one row holding a list: the lookup is
     * a primary-key hit, and two imports finishing near each other cannot
     * overwrite one another's record.
     */
    const val HISTORY_IMPORT_FILE_PREFIX = "history_import_file:"

    /**
     * Whether *this* device is the one that writes the Evergreen playlist.
     *
     * Off by default, and turned on once, on the Walkman. Until Phase 6 each
     * device has its own database, so two devices running the sync would fight
     * over one playlist with two different rankings — each undoing the other's
     * writes every day, against a quota neither can see. The import runs only
     * on the writer device for the same reason.
     */
    const val EVERGREEN_WRITER_DEVICE = "evergreen_writer_device"

    /**
     * The id of the playlist this app created, or adopted by name once.
     *
     * The app writes only to this playlist. Discovery by name happens only when
     * this is absent, so a playlist belonging to someone else that happens to
     * be called Evergreen can never be adopted after the first run.
     */
    const val EVERGREEN_PLAYLIST_ID = "evergreen_playlist_id"

    /** When Evergreen was last written, enforcing at most one write a day. */
    const val EVERGREEN_LAST_WRITTEN_AT = "evergreen_last_written_at"

    /**
     * When the scoring parameters were last changed, cleared by the next write.
     *
     * Set by the settings screen, read and cleared by the Evergreen sync
     * worker, which skips its "joined / left" notification once when it finds
     * it. Forty joins and leaves after nudging the skip weight is not news
     * about the user's listening -- it is the arithmetic doing what it was just
     * told to -- and the settings screen makes that easy to trigger.
     *
     * Deliberately *not* cleared when the parameters are saved back to their
     * old value: the recompute still happened, and the next write still
     * reflects two changes rather than a day of listening.
     */
    const val PARAMETERS_CHANGED_AT = "parameters_changed_at"

    /**
     * How many days of silence before the "capture has gone quiet" alert.
     *
     * Stored as a number of days in `valueMs` -- a count, not a timestamp,
     * which is a small abuse of the column name and cheaper than a table. Zero
     * turns the alert off.
     */
    const val CAPTURE_SILENT_DAYS = "capture_silent_days"

    /** When the capture-silence alert was last posted, so it does not repeat daily. */
    const val CAPTURE_SILENT_NOTIFIED_AT = "capture_silent_notified_at"

    /**
     * The model name of the device this database belongs to, e.g. `NW-A307`.
     *
     * Stamped by the backup button immediately before it takes its copy, so a
     * backup file carries the name of the device that produced it and the
     * Pixel can say whose mirror it is holding. Nowhere else needs it, and
     * writing it at any other moment would make it a claim about the wrong
     * device once the file has been copied.
     *
     * `Build.MODEL` is a model name, not an identifier: every NW-A307 in the
     * world reports the same string. It never leaves the device except inside
     * a backup file the user moves themselves.
     */
    const val DEVICE_NAME = "device_name"

    /**
     * Present only when this database is a **mirror** of another device's.
     *
     * `valueText` is the source device's name as stamped above, absent if the
     * backup predates the stamp; `valueMs` is when that backup was taken.
     *
     * The presence of this row *is* mirror mode — there is no separate boolean
     * that could disagree with it, and clearing the row is the whole of turning
     * mirror mode off. Everything that writes to Spotify checks it, because a
     * mirror arrives carrying the writer device's own `sync_state`, writer flag
     * and playlist id: the imported copy believes it is the Walkman.
     */
    const val MIRROR_SOURCE = "mirror_source"

    /** When the mirror was imported onto *this* device. */
    const val MIRROR_IMPORTED_AT = "mirror_imported_at"

    /**
     * When the user confirmed the first write.
     *
     * Until this is set, every sync computes its diff and writes nothing. The
     * same pattern as the same-song merge: the first irreversible action is
     * shown in full and confirmed once.
     */
    const val EVERGREEN_WRITE_CONFIRMED_AT = "evergreen_write_confirmed_at"

    /**
     * The highest originating id this device has **exported**, per origin.
     *
     * One row per origin device, keyed by this prefix plus the device id, with
     * the id itself in `valueMs`. A prefix and many rows rather than one row
     * holding a list, for the same reason as the history import's file record:
     * the lookup is a primary-key hit, and a database holds facts from more
     * than one origin -- its own install, the fixed `spotify-history-import`,
     * and any install id left behind by a reinstall.
     *
     * This is a *convenience*, not a correctness mechanism. It is what lets the
     * export screen offer "everything since last time" instead of the whole
     * history, and losing it costs one larger batch and nothing else. The
     * watermark that actually matters is the **receiver's**, computed from the
     * rows it holds -- a watermark only the producer remembers could not be
     * checked by anybody, and a batch that quietly began after what the
     * receiver had would lose facts in silence.
     */
    const val SYNC_EXPORT_THROUGH_PREFIX = "sync_export_through:"

    /** When the last delta export finished, for the export screen to say. */
    const val SYNC_EXPORT_AT = "sync_export_at"

    /** When the last delta import finished, with its counts in `valueText`. */
    const val SYNC_IMPORT_AT = "sync_import_at"
}
