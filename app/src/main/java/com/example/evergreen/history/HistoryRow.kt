package com.example.evergreen.history

import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One row of a Spotify "extended streaming history" export, reduced to the
 * seven fields this app is allowed to read.
 *
 * **This type is a privacy boundary, and its shape is the guarantee.** The
 * export carries far more than this — an IP address on every row, the country
 * it was seen from, the platform, offline flags, podcast and audiobook fields.
 * The app has no business with any of it, and the strongest way to promise that
 * is not a careful parser but a type that has nowhere to put it. If `ip_addr`
 * cannot be represented, it cannot be stored, logged or leaked by a later
 * change to code that never imagined it was handling an IP address.
 *
 * `HistoryRowTest` asserts this class has exactly these seven properties, so
 * adding an eighth is a deliberate act with a test to update, rather than a
 * quiet widening of what the app reads about the user.
 *
 * @param endedAtMs from the export's `ts`, which is the moment the stream
 *   **stopped**, in UTC.
 * @param listenedMs from `ms_played`.
 * @param spotifyTrackUri from `spotify_track_uri`. Always a `spotify:track:`
 *   URI; rows carrying anything else, or nothing, are skipped by the parser.
 * @param trackName from `master_metadata_track_name`.
 * @param artistName from `master_metadata_album_artist_name`.
 * @param albumName from `master_metadata_album_album_name`.
 * @param reasonEnd from `reason_end` — `trackdone`, `fwdbtn`, `endplay` and
 *   the like. Stored but not scored; see [com.example.evergreen.data.ListeningSegmentEntity.sourceEndReason].
 */
data class HistoryRow(
    val endedAtMs: Long,
    val listenedMs: Long,
    val spotifyTrackUri: String,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val reasonEnd: String?,
) {
    /**
     * When the stream started, derived rather than stored.
     *
     * The export gives only the end and the duration. It also carries an
     * `offline_timestamp`, which is **not** used: it is only meaningful for
     * offline plays, is zero or absent otherwise, and mixing the two would make
     * some rows' start times mean something different from others'. Recorded as
     * a known simplification rather than silently ignored.
     */
    val startedAtMs: Long get() = endedAtMs - listenedMs

    companion object {
        /** The only URI scheme the importer accepts. */
        const val TRACK_URI_PREFIX = "spotify:track:"

        /**
         * Parses the export's `ts` into epoch milliseconds, or null.
         *
         * Spotify writes ISO-8601 in UTC (`2020-01-24T15:32:10Z`). Older
         * exports have been seen using a space instead of the `T` and no zone,
         * so that shape is accepted too rather than silently dropping years of
         * history over a formatting change. Anything else returns null and is
         * counted, never guessed at.
         */
        fun parseTimestamp(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            val trimmed = text.trim()
            asInstant(trimmed)?.let { return it }
            val normalised = buildString {
                append(trimmed.replace(' ', 'T'))
                if (!endsWith("Z") && !contains('+')) append('Z')
            }
            return asInstant(normalised)
        }

        private fun asInstant(text: String): Long? = try {
            Instant.parse(text).toEpochMilli()
        } catch (expected: DateTimeParseException) {
            null
        } catch (expected: DateTimeException) {
            null
        }
    }
}

/**
 * What one pass over an export file found.
 *
 * Counts only. Nothing here identifies a row, because the import summary is
 * shown on screen and the rule for this phase is aggregates only — never a row
 * of the export or of the database.
 */
data class HistoryParseSummary(
    /** Objects seen in the file, whether or not they were usable. */
    val rowsRead: Int = 0,
    /** Rows accepted and handed to the caller. */
    val rowsAccepted: Int = 0,
    /** Rows with no `spotify_track_uri` — podcasts and audiobooks. */
    val rowsWithoutTrackUri: Int = 0,
    /** Rows whose `ts` could not be read. */
    val rowsWithoutTimestamp: Int = 0,
) {
    operator fun plus(other: HistoryParseSummary) = HistoryParseSummary(
        rowsRead = rowsRead + other.rowsRead,
        rowsAccepted = rowsAccepted + other.rowsAccepted,
        rowsWithoutTrackUri = rowsWithoutTrackUri + other.rowsWithoutTrackUri,
        rowsWithoutTimestamp = rowsWithoutTimestamp + other.rowsWithoutTimestamp,
    )
}
