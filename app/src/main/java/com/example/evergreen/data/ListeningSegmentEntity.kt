package com.example.evergreen.data

import androidx.room.ColumnInfo
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

    /**
     * The end was not observed by this app; the row came from an import.
     *
     * An observation about provenance, not a conclusion about what the user
     * did. The export's own code for why the stream stopped is kept verbatim in
     * [ListeningSegmentEntity.sourceEndReason], where it stays a fact about the
     * play rather than being translated into one of the values above -- which
     * would be this app claiming to have seen something it did not.
     */
    IMPORTED,
}

/**
 * Where a segment row came from.
 *
 * Provenance is a fact, which is why this column is allowed under the rule that
 * segments store facts and not conclusions. It exists so that the debug screen,
 * the import's de-duplication and the tests can tell a captured row from an
 * imported one without inferring it from which columns happen to be null.
 */
enum class SegmentSource {
    /** Observed live by this app's capture path. */
    CAPTURE,

    /** Read from a Spotify "extended streaming history" export. */
    IMPORT_SPOTIFY_HISTORY,
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
            // RESTRICT since schema v2, previously CASCADE.
            //
            // Under CASCADE, deleting one canonical track silently deleted
            // every segment recorded against it — the listening history this
            // app exists to accumulate, destroyed by a single DELETE. The risk
            // is not hypothetical: merging two canonical tracks means deleting
            // one of them.
            //
            // RESTRICT makes the database refuse such a delete. A merge must
            // therefore re-point its segments first and say so explicitly,
            // rather than losing them by omission.
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ServiceTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["serviceTrackRowId"],
            // RESTRICT since schema v2, previously SET NULL.
            //
            // SET NULL was the same loss wearing a quieter coat: deleting a
            // service row blanked this column, leaving segments that still knew
            // how long they were listened to but no longer which Spotify
            // recording they were. The column stays nullable — plenty of
            // segments never had a service id at all — but a row that *has* one
            // may not have it taken away.
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["startedAtMs"]),
        Index(value = ["deviceId"]),
        Index(value = ["serviceTrackRowId"]),
        // The importer asks "is there already an imported row for this service
        // track at this instant?" once per row, 77,000 times.
        Index(value = ["source"]),
        // Schema v7. A segment's global name, and the thing that makes
        // applying a delta batch twice a no-op rather than a duplication.
        //
        // Unique, and the uniqueness leans on a SQLite rule that is usually a
        // nuisance and here is exactly right: two NULLs are *different* values
        // to a unique index. Every locally captured row carries NULL in
        // `originRowId`, so local capture collides with nothing and needs no
        // change; every imported row carries a real number, so the same batch
        // arriving a second time hits this index and is rejected by the
        // database rather than by the import code remembering to check.
        Index(value = ["deviceId", "originRowId"], unique = true),
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
     *
     * **Nullable since schema v4, and null on every imported row.** The Spotify
     * export records how long a stream lasted but never where in the track it
     * got to. Null therefore means "not observed", which is different from 0
     * ("observed at the start") -- and the difference matters, because a row
     * reaching position 0 is a track that was cut off immediately while a row
     * with no position is a play whose shape is simply unknown. Storing 0 for
     * both would be inventing a measurement.
     */
    val startPositionMs: Long?,
    val endPositionMs: Long?,

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

    /**
     * How many forward jumps were seen, and how much was jumped over in total.
     *
     * Nullable since schema v4, for the same reason as the position fields: the
     * export cannot say, and "no seeks were seen" is a different statement from
     * "nobody was watching".
     */
    val forwardSeekCount: Int?,
    val seekedForwardMs: Long?,

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
     *
     * Nullable since schema v4: an imported row rests on no extrapolation at
     * all, so there is no age to report.
     */
    val lastSampleAgeMs: Long?,

    val createdAt: Long,

    /**
     * Where this row came from. Defaults to [SegmentSource.CAPTURE], which is
     * what every row written before schema v4 was.
     */
    @ColumnInfo(defaultValue = "CAPTURE")
    val source: SegmentSource = SegmentSource.CAPTURE,

    /**
     * The export's own `reason_end` code for an imported row -- `trackdone`,
     * `fwdbtn`, `endplay` and the like. Null on captured rows.
     *
     * Kept even though the scorer does not read it. It is a fact about the play
     * that cost nothing to store and cannot be recovered once the export is
     * deleted, and the project's rule is that facts are stored so conclusions
     * can be drawn later. It is also the only cross-check available on whether
     * the fraction-heard curve agrees with Spotify's own view of what was a
     * skip.
     */
    val sourceEndReason: String? = null,

    /**
     * The row id this segment had on the device that recorded it. **Schema v7.**
     *
     * Null means "this row originated here", and its originating id is its own
     * [id]. A segment's global name is therefore
     * `(deviceId, COALESCE(originRowId, id))` — one expression that gives the
     * right answer for a row captured on this device, for a row that arrived in
     * a whole-file mirror import, and for a row that arrived in a delta batch.
     *
     * **Why the column has to exist at all.** Until Phase 5b the only way a
     * segment crossed between devices was inside a complete database file, and
     * a file replacement carries the ids with it. A *delta* is inserted into a
     * database that already has rows, so the receiving device's SQLite assigns
     * its own row numbers and the originating one is lost — which would make
     * the same batch applied twice insert everything twice. Idempotence is what
     * makes an unreliable transport safe rather than a corruption risk
     * (`docs/SYNC_FOUNDATION.md` Part 1), so losing it is not an option.
     *
     * **Why nullable rather than "default to my own id".** Backfilling every
     * existing row would rewrite 77,518 rows on the slowest device in the
     * project, and keeping it filled would mean `SegmentWriter` writing the
     * column after each insert — a change inside `capture/` for a decision that
     * is not capture's to make. Null costs one `COALESCE` at the two places
     * that ask, and leaves capture untouched.
     */
    val originRowId: Long? = null,
)
