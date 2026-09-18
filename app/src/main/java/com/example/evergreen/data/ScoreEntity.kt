package com.example.evergreen.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A cached score. **Never a source of truth.**
 *
 * Invariant 1a says a score is a pure function of (segments, like events,
 * parameters, now) and is computed rather than accumulated. This table exists
 * only so the debug screen and the sync worker do not have to re-score four
 * hundred tracks every time they want a ranking. It can be dropped at any
 * moment and rebuilt from the segment table with no loss.
 *
 * [parametersHash] is what keeps that promise honest: every row records the
 * settings that produced it, and a row whose hash no longer matches the current
 * parameters is stale by definition and rebuilt rather than read. Without it,
 * retuning the half-life would leave a table full of numbers computed under the
 * old one, which is precisely the accumulation the invariant forbids — just
 * spread over two tables instead of one.
 *
 * `computedAt` is stored because a score is only true for the instant it was
 * computed at: decay means every score falls continuously, so a day-old cache
 * is slightly optimistic about everything.
 */
@Entity(
    tableName = "scores",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            // Consistent with every other key since v2, though this one guards
            // nothing precious: a cache row is rebuildable. RESTRICT keeps the
            // rule uniform, so nobody has to remember which tables cascade.
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["score"]),
        Index(value = ["rank"]),
    ],
)
data class ScoreEntity(
    /** One row per canonical track. */
    @PrimaryKey
    val trackId: Long,

    val score: Double,

    /** 1 is the highest-scoring track. */
    val rank: Int,

    val computedAt: Long,

    /** [com.example.evergreen.scoring.ScoringParameters.hash] of the settings used. */
    val parametersHash: String,
)

/** Why a track joined or left the Evergreen playlist. */
enum class MembershipReason {
    /** Its rank reached the join cutoff. */
    JOINED_BY_RANK,

    /** Its rank fell below the leave cutoff. */
    LEFT_BY_RANK,

    /**
     * The track was unliked.
     *
     * Removal is immediate at the next sync, and the score and the archive
     * entry are kept — so re-liking restores the track at full strength. The
     * cost of an accidental unlike is one playlist write, not lost history
     * (decisions 4).
     */
    LEFT_UNLIKED,

    /** Liked again, having previously left. */
    REJOINED_BY_LIKE,
}

/**
 * When a track joined the Evergreen playlist, and when it left.
 *
 * Append-style, like the like archive and for the same reason: "when did this
 * song leave, and why" is a question the user will ask, and a table that only
 * held the current membership could never answer it. A row is closed by setting
 * [leftAt] rather than deleted.
 *
 * Note that this is the app's own record of what it *intended*. The playlist on
 * Spotify is read back before every write rather than inferred from this table,
 * so drift caused by editing the playlist elsewhere is noticed and corrected
 * (decision 10.2).
 */
@Entity(
    tableName = "evergreen_membership",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["leftAt"]),
    ],
)
data class EvergreenMembershipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val trackId: Long,

    val joinedAt: Long,

    /** Null while the track is still in the playlist. */
    val leftAt: Long? = null,

    /** Why it joined. */
    val joinReason: MembershipReason,

    /** Why it left, or null while it is still a member. */
    val leaveReason: MembershipReason? = null,

    /** The rank it held when it joined, for the record. */
    val rankAtJoin: Int,

    /** The rank it held when it left. */
    val rankAtLeave: Int? = null,
)

/**
 * A monthly copy of the ranking.
 *
 * Taken on the last day of the month and kept in the app; exported to a real
 * Spotify playlist only on demand (decisions 5).
 *
 * [parametersJson] is not an optional extra. A snapshot records scores, and a
 * score means nothing without the settings that produced it — the first time
 * the scoring is retuned, every snapshot taken before it would otherwise become
 * unreadable, a column of numbers on a scale nobody can reconstruct.
 */
@Entity(
    tableName = "snapshots",
    indices = [Index(value = ["monthKey"], unique = true)],
)
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * `2026-08`, in the device's own timezone.
     *
     * Unique, so the monthly worker can run every day and do nothing rather
     * than having to calculate "the last day of the month" as a schedule.
     * Stored as text because that is what the user will search for.
     */
    val monthKey: String,

    /** Human-readable, e.g. `2026 August` — the phrase from the brief. */
    val label: String,

    val takenAt: Long,

    /** [com.example.evergreen.scoring.ScoringParameters.toJson] at the time. */
    val parametersJson: String,

    /** How many tracks were in the playlist that month. */
    val playlistSize: Int,

    /** Total tracks ranked, playlist and record together. */
    val trackCount: Int,

    /** The Spotify playlist this snapshot was exported to, if it ever was. */
    val exportedPlaylistId: String? = null,

    val exportedAt: Long? = null,
)

/**
 * One track's place in one snapshot.
 *
 * The **whole** ranking is stored, not only the hundred tracks that were in the
 * playlist. That is what makes both "why was this in August" and "what was
 * still alive in August" answerable later; keeping only the playlist would
 * answer the first and lose the second forever.
 *
 * The service URI is copied in rather than looked up, so a track that later
 * goes unavailable on Spotify — as several of the 380 already have — is still
 * visible in the month it was alive.
 */
@Entity(
    tableName = "snapshot_entries",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            // The one CASCADE in the database, and deliberate: a snapshot entry
            // has no meaning without its snapshot, and deleting a snapshot is
            // an explicit act on a derived record rather than on history.
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["snapshotId", "rank"]),
        Index(value = ["trackId"]),
    ],
)
data class SnapshotEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val snapshotId: Long,

    val trackId: Long,

    val rank: Int,

    val score: Double,

    /** The URI at the time, so an unavailable track stays visible in its month. */
    val serviceTrackId: String?,

    /** Title and artist as they were, for reading a snapshot back. */
    val title: String?,
    val artist: String?,

    /** Whether this track was in the Evergreen playlist that month. */
    val inPlaylist: Boolean,
)
