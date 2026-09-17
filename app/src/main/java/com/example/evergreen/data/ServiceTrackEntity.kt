package com.example.evergreen.data

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
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["service", "serviceTrackId"], unique = true),
        Index(value = ["trackId"]),
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
     * International Standard Recording Code, used in Phase 5 to match the same
     * recording across services. Always null in Phase 1: the media session does
     * not carry it, and Phase 2 fills it in from the Spotify Web API.
     */
    val isrc: String? = null,

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
