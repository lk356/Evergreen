package com.example.evergreen.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A canonical track: one row per song, independent of which service played it.
 *
 * This is the row Phase 3 will score and Phase 5 will match across services.
 * Per-service identifiers live in [ServiceTrackEntity], so adding a second
 * streaming service later needs no change to this table.
 *
 * Only the metadata the privacy rules allow: title, artist, album, duration.
 */
@Entity(
    tableName = "tracks",
    indices = [Index(value = ["trackKey"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Identity for de-duplication: title, artist and album folded to lower case
     * and joined with '|'.
     *
     * A unique index across the three nullable columns would not work: SQLite
     * treats two NULLs as different values, so rows with a missing album would
     * duplicate freely. One non-null derived key avoids that. See [trackKeyOf].
     */
    val trackKey: String,

    val title: String?,
    val artist: String?,
    val album: String?,

    /** Best known length in milliseconds, or 0 if it was never reported. */
    val durationMs: Long,

    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

/**
 * Builds the identity key for a track.
 *
 * Case and surrounding whitespace are normalised because media sessions are not
 * consistent about either between callbacks.
 */
fun trackKeyOf(title: String?, artist: String?, album: String?): String {
    fun part(value: String?) = value.orEmpty().trim().lowercase()
    return "${part(title)}|${part(artist)}|${part(album)}"
}
