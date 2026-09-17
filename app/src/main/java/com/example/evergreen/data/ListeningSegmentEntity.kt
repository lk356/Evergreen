package com.example.evergreen.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Why a segment stopped.
 *
 * Every value here is something that was *observed*, not something that was
 * concluded. There is deliberately no `REPLAY` value: what the capture layer
 * can see is that the reported position jumped backwards, which is
 * [POSITION_JUMP_BACK]. Whether such a jump means "the user replayed this song"
 * is a judgement about intent, it depends on a threshold that has not been
 * chosen yet, and it belongs to Phase 3 scoring — not to this table.
 *
 * Stored as text rather than as the enum's ordinal number, so that reordering
 * this list can never silently reinterpret existing rows.
 */
enum class EndReason {
    /** Metadata changed to a genuinely different track. */
    TRACK_CHANGE,

    /**
     * The position jumped backwards within the same track, by more than
     * [com.example.evergreen.capture.CaptureThresholds.POSITION_JUMP_BACK_CUT_MS].
     *
     * Covers a manual restart, a repeat-one loop and "previous" pressed near
     * the end of a track. Those are indistinguishable at capture time; the
     * difference between them is the position reached, which this row stores.
     */
    POSITION_JUMP_BACK,

    /** The media session was destroyed (e.g. Spotify was force-stopped). */
    SESSION_END,

    /**
     * No further readings arrived for a long time, so the segment was closed to
     * avoid losing it. Says nothing about what the user did.
     */
    STALE,

    /** The segment ended but there was not enough information to say how. */
    UNKNOWN,
}

/**
 * One **play segment**: a continuous stretch of listening to a single track.
 *
 * Not one row per callback. The media session fires several callbacks a second
 * during transitions; those are collapsed in memory and only a finished segment
 * is ever written here, which works out at roughly one row per song played.
 *
 * Every column is a raw observation. There is no outcome column — no
 * COMPLETED, no SKIPPED. Those are conclusions, they depend on thresholds that
 * are still open questions, and anything Phase 3 wants to conclude can be
 * recomputed from the facts stored here. Storing a verdict would freeze a guess
 * about the user's intent into the database, which is exactly the Phase 0 bug
 * that recorded a deliberately looped song as a 3% skip.
 */
@Entity(
    tableName = "listening_segments",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ServiceTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["serviceTrackRowId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["startedAtMs"]),
        Index(value = ["deviceId"]),
        Index(value = ["serviceTrackRowId"]),
    ],
)
data class ListeningSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val trackId: Long,

    /** Null when the media session published no service identifier. */
    val serviceTrackRowId: Long?,

    /** Package that played it, always from the capture allowlist. */
    val appPackage: String,

    /**
     * Which device recorded this. A random identifier generated once per
     * install — not a hardware ID. Phase 6 needs it to tell segments synced
     * from another device apart from local ones.
     */
    val deviceId: String,

    /** Wall-clock start and end, epoch milliseconds. */
    val startedAtMs: Long,
    val endedAtMs: Long,

    /**
     * Position within the track at the start and end of the segment.
     *
     * [endPositionMs] is the "position reached", and it is the fact that tells a
     * repeat-one loop (ends near the duration) apart from a mid-track restart
     * (ends somewhere in the middle).
     */
    val startPositionMs: Long,
    val endPositionMs: Long,

    /**
     * Milliseconds actually heard.
     *
     * Accumulated only while playback was really running, scaled by
     * `playbackSpeed`, and excluding any jump caused by seeking. This is
     * deliberately *not* the same as the position span: seeking forward to the
     * end of a track produces a large [endPositionMs] and a tiny [listenedMs],
     * which is what stops a seek-to-end being mistaken for a completed play.
     */
    val listenedMs: Long,

    /**
     * Track duration as reported by the media session at the time this segment
     * was captured, so any percentage derived from this row uses a numerator
     * and denominator from the same source.
     */
    val trackDurationMs: Long,

    /** How many forward jumps were seen, and how much was jumped over in total. */
    val forwardSeekCount: Int,
    val seekedForwardMs: Long,

    val endReason: EndReason,

    /**
     * For [EndReason.POSITION_JUMP_BACK], where the position jumped from and to.
     *
     * This pair is what makes the segmentation reversible. Phase 3 can decide
     * that jumps below some size are not replays and add the two rows back
     * together; it can never recover a jump that was not recorded. Null for
     * every other end reason.
     */
    val endJumpFromMs: Long?,
    val endJumpToMs: Long?,

    /**
     * How old the last position reading was when the segment closed.
     *
     * Position is a snapshot, so closing a segment means extrapolating from the
     * most recent reading. A large value here means [listenedMs] leans on that
     * extrapolation rather than on something observed, and Phase 3 can discount
     * the row accordingly. Recording the uncertainty beats silently presenting a
     * guess as a measurement.
     */
    val lastSampleAgeMs: Long,

    val createdAt: Long,
)
