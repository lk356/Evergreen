package com.example.evergreen.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One streaming service's identifier for a canonical track.
 *
 * Many rows may point at the same [TrackEntity]: the album version and the
 * single version of a song are different Spotify URIs but the same song, and a
 * second service added in Phase 5 contributes its own rows alongside Spotify's.
 *
 * [isrc] lives here rather than on [TrackEntity] deliberately. An ISRC is
 * learned *from* a service and describes that service's particular recording,
 * so it is a property of this row, not of the song.
 */
@Entity(
    tableName = "service_tracks",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            // RESTRICT since schema v2. Under the previous CASCADE, deleting a
            // canonical track silently deleted its service rows — and with them
            // the Spotify URI and the ISRC. That matters most during a merge,
            // which deletes the losing track by design: the segments would be
            // re-pointed and survive, but every record of *which recording*
            // they were would vanish through this key. A merge must re-point
            // these rows too, and RESTRICT is what forces it to.
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["service", "serviceTrackId"], unique = true),
        Index(value = ["trackId"]),
        // The ISRC backfill's work query is "rows still missing an ISRC".
        Index(value = ["isrc"]),
    ],
)
data class ServiceTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val trackId: Long,

    /** Which service this identifier belongs to, e.g. [SERVICE_SPOTIFY]. */
    val service: String,

    /** The service's own identifier, e.g. `spotify:track:4h3K5he5ItFqJB1MVHMKJV`. */
    val serviceTrackId: String,

    /**
     * International Standard Recording Code, used to match the same recording
     * across services and to propose same-song merges.
     *
     * Filled in by Phase 2 from the Spotify Web API — for liked tracks it
     * arrives free inside the like sync, for everything else it costs one
     * request per track. Null means "not known yet", never "does not exist";
     * [isrcLookupAt] and [isrcLookupError] say whether we have tried.
     */
    val isrc: String? = null,

    /**
     * When the ISRC lookup last ran for this row, or null if never tried.
     *
     * Separating "never tried" from "tried and failed" is what lets the backfill
     * resume after a crash, a 429 or a quota block without re-fetching rows it
     * already resolved. Spotify removed the batch track endpoint in February
     * 2026, so a re-fetch costs one request per track and is worth avoiding.
     */
    val isrcLookupAt: Long? = null,

    /**
     * How many times the lookup has been attempted, for backing off bad rows.
     *
     * The SQL default is what lets the v1 -> v2 migration add this column to a
     * table that already has rows: every existing service track is "never
     * attempted", which is exactly right.
     */
    @ColumnInfo(defaultValue = "0")
    val isrcLookupAttempts: Int = 0,

    /**
     * Why the last lookup failed, or null if it succeeded or never ran.
     *
     * A short reason code, not a raw response body: nothing from the network is
     * stored verbatim.
     */
    val isrcLookupError: String? = null,

    /**
     * Duration as reported alongside *this* service identifier.
     *
     * Kept separately from [TrackEntity.durationMs] because sources disagree:
     * the media session rounds to the second (109000 ms) while the Spotify
     * broadcast is exact (108518 ms). Mixing the two in one calculation
     * produces quietly wrong percentages.
     */
    val durationMs: Long,

    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

const val SERVICE_SPOTIFY = "spotify"

/** Reason codes stored in [ServiceTrackEntity.isrcLookupError]. */
object IsrcLookupError {
    /** The track was fetched but carried no ISRC in `external_ids`. */
    const val ABSENT = "ABSENT"

    /** Spotify returned 404 — the URI no longer resolves. */
    const val NOT_FOUND = "NOT_FOUND"

    /** Network or server problem; worth retrying. */
    const val TRANSIENT = "TRANSIENT"

    /** The identifier was not in a shape we could turn into an API call. */
    const val UNSUPPORTED_ID = "UNSUPPORTED_ID"
}
