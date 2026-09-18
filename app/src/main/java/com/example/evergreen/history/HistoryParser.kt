package com.example.evergreen.history

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Reads a Spotify "extended streaming history" JSON file, one row at a time.
 *
 * **Streaming, not loading.** The largest file in the export is about 12 MB and
 * the whole export is around 77,000 rows. The NW-A307 has limited memory and a
 * slow CPU, so the file is pulled through [JsonReader] a token at a time and
 * rows are handed to the caller as they are read. Nothing ever holds the file,
 * or the list of rows, in memory.
 *
 * **A field allowlist, not a field blocklist.** Every key is matched by name
 * against the seven the app is allowed to read; everything else goes to
 * [JsonReader.skipValue] without being assigned to anything. The export carries
 * an IP address on every row, plus the country, the platform and offline
 * timestamps. None of it is read into a variable, and [HistoryRow] has nowhere
 * to put it even if it were. A blocklist would have to be updated every time
 * Spotify added a field; an allowlist is safe by default when they do.
 */
class HistoryParser {

    /**
     * Reads [input] to the end, calling [onRow] for each usable row.
     *
     * The stream is not closed; the caller owns it, because a zipped export is
     * read as a series of entries on one underlying stream.
     *
     * @return counts of what was found. Counts only — never a row, per the
     *   privacy rule for this phase.
     * @throws java.io.IOException if the file is not readable as JSON. An
     *   unreadable file stops the import rather than being silently treated as
     *   an empty one, which would look like a successful import of nothing.
     */
    suspend fun parse(input: InputStream, onRow: suspend (HistoryRow) -> Unit): HistoryParseSummary {
        var rowsRead = 0
        var rowsAccepted = 0
        var rowsWithoutTrackUri = 0
        var rowsWithoutTimestamp = 0

        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.beginArray()
        while (reader.hasNext()) {
            rowsRead++
            when (val row = readRow(reader)) {
                is RowOutcome.Usable -> {
                    rowsAccepted++
                    onRow(row.row)
                }
                RowOutcome.NoTrackUri -> rowsWithoutTrackUri++
                RowOutcome.NoTimestamp -> rowsWithoutTimestamp++
            }
        }
        reader.endArray()

        return HistoryParseSummary(
            rowsRead = rowsRead,
            rowsAccepted = rowsAccepted,
            rowsWithoutTrackUri = rowsWithoutTrackUri,
            rowsWithoutTimestamp = rowsWithoutTimestamp,
        )
    }

    private fun readRow(reader: JsonReader): RowOutcome {
        var timestamp: String? = null
        var msPlayed = 0L
        var trackUri: String? = null
        var trackName: String? = null
        var artistName: String? = null
        var albumName: String? = null
        var reasonEnd: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            // The allowlist. Every branch names the one field it reads; the
            // `else` never looks at the value at all.
            when (reader.nextName()) {
                FIELD_TS -> timestamp = reader.nextStringOrNull()
                FIELD_MS_PLAYED -> msPlayed = reader.nextLongOrZero()
                FIELD_TRACK_URI -> trackUri = reader.nextStringOrNull()
                FIELD_TRACK_NAME -> trackName = reader.nextStringOrNull()
                FIELD_ARTIST_NAME -> artistName = reader.nextStringOrNull()
                FIELD_ALBUM_NAME -> albumName = reader.nextStringOrNull()
                FIELD_REASON_END -> reasonEnd = reader.nextStringOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        // Podcasts and audiobooks carry an episode URI instead, and 27 such
        // rows are expected in this export. Anything that is not a track URI is
        // skipped for the same reason the scorer drops `spotify:media:`
        // commentary: it is not a song and has no canonical track to score.
        if (trackUri == null || !trackUri.startsWith(HistoryRow.TRACK_URI_PREFIX)) {
            return RowOutcome.NoTrackUri
        }
        val endedAtMs = HistoryRow.parseTimestamp(timestamp) ?: return RowOutcome.NoTimestamp

        return RowOutcome.Usable(
            HistoryRow(
                endedAtMs = endedAtMs,
                // Clamped at zero rather than trusted: a negative duration
                // would produce a segment that ended before it began.
                listenedMs = msPlayed.coerceAtLeast(0L),
                spotifyTrackUri = trackUri,
                trackName = trackName,
                artistName = artistName,
                albumName = albumName,
                reasonEnd = reasonEnd,
            ),
        )
    }

    /** Reads a string, tolerating the JSON null that podcast rows carry. */
    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private fun JsonReader.nextLongOrZero(): Long = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            0L
        }
        JsonToken.NUMBER -> nextLong()
        // Some exports quote the number. Reading it as a string and converting
        // is better than skipping the row's whole duration.
        JsonToken.STRING -> nextString().trim().toLongOrNull() ?: 0L
        else -> {
            skipValue()
            0L
        }
    }

    private sealed interface RowOutcome {
        data class Usable(val row: HistoryRow) : RowOutcome
        data object NoTrackUri : RowOutcome
        data object NoTimestamp : RowOutcome
    }

    companion object {
        // The complete list of what this app reads from the export. Anything
        // not named here is skipped by name and never enters a variable.
        const val FIELD_TS = "ts"
        const val FIELD_MS_PLAYED = "ms_played"
        const val FIELD_TRACK_URI = "spotify_track_uri"
        const val FIELD_TRACK_NAME = "master_metadata_track_name"
        const val FIELD_ARTIST_NAME = "master_metadata_album_artist_name"
        const val FIELD_ALBUM_NAME = "master_metadata_album_album_name"
        const val FIELD_REASON_END = "reason_end"

        /** Exactly the keys above, for the tests that pin the allowlist. */
        val ALLOWED_FIELDS: Set<String> = setOf(
            FIELD_TS,
            FIELD_MS_PLAYED,
            FIELD_TRACK_URI,
            FIELD_TRACK_NAME,
            FIELD_ARTIST_NAME,
            FIELD_ALBUM_NAME,
            FIELD_REASON_END,
        )
    }
}
