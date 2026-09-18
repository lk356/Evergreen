package com.example.evergreen.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The like **archive**: every song ever liked, kept for good.
 *
 * Two tables carry this, for the same reason Phase 1 splits facts from
 * conclusions:
 *
 * - [LikeEventEntity] is the record. It is append-only and nothing ever deletes
 *   from it, so the full history of likes, unlikes and re-likes survives.
 * - [LikeEntity] (this one) is the current state, one row per service track,
 *   updated in place so "what is liked right now" is a cheap query.
 *
 * **Unliking never deletes anything.** It sets [unlikedAt] and appends an
 * [LikeEventType.UNLIKED] event. A song that disappears from Spotify becomes
 * historical, not erased — which is the whole point of keeping an archive
 * separate from the live playlist.
 *
 * Note what this table does *not* do: it draws no conclusion about whether an
 * unliked song should leave Evergreen. That is a scoring question and belongs
 * to Phase 3, which has everything it needs here to decide either way.
 */
@Entity(
    tableName = "likes",
    foreignKeys = [
        ForeignKey(
            entity = ServiceTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["serviceTrackRowId"],
            // Same rule as everywhere else in v2: a merge must re-point these
            // rows deliberately rather than have the archive quietly shrink.
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["serviceTrackRowId"], unique = true),
        Index(value = ["unlikedAt"]),
    ],
)
data class LikeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Which service identifier was liked. Likes belong to a service, not to a song. */
    val serviceTrackRowId: Long,

    /**
     * Spotify's own `added_at` for this saved track, as epoch milliseconds.
     *
     * This is when *you* liked it, which is generally earlier — often years
     * earlier — than when this app first saw it. Null if the service did not
     * report one.
     */
    val serviceAddedAtMs: Long? = null,

    /** The first sync in which this app observed the track as liked. */
    val firstSeenLikedAt: Long,

    /** The most recent sync in which it was still liked. */
    val lastSeenLikedAt: Long,

    /**
     * When a sync first found it *missing* from the liked set, or null if it is
     * liked now.
     *
     * Null is the only meaning of "currently liked". Re-liking clears it again,
     * and [LikeEventEntity] still holds the round trip.
     */
    val unlikedAt: Long? = null,
)

/** What a [LikeEventEntity] records. Both are observations, not judgements. */
enum class LikeEventType {
    /** Seen in the liked set, having not been there before. */
    LIKED,

    /** Absent from the liked set, having been there before. */
    UNLIKED,
}

/**
 * One observed change in like state. Append-only; never deleted, never updated.
 *
 * A row is written only on a *transition*, not on every sync — otherwise a
 * daily sync of a few thousand likes would add a few thousand rows a day
 * forever. "Still liked, as it was yesterday" is carried by
 * [LikeEntity.lastSeenLikedAt] instead.
 */
@Entity(
    tableName = "like_events",
    foreignKeys = [
        ForeignKey(
            entity = ServiceTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["serviceTrackRowId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["serviceTrackRowId"]),
        Index(value = ["observedAt"]),
    ],
)
data class LikeEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val serviceTrackRowId: Long,

    val event: LikeEventType,

    /**
     * When this app observed the change — not when it happened.
     *
     * An unlike is noticed at the next sync, so this is an upper bound on the
     * real time. Recording the observation rather than guessing the event time
     * is the same discipline the capture layer follows.
     */
    val observedAt: Long,

    /** Spotify's `added_at` at the time of the observation, where known. */
    val serviceAddedAtMs: Long? = null,
)
