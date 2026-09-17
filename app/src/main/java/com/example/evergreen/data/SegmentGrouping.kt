package com.example.evergreen.data

/**
 * One or more stored segments, viewed as a single listen.
 *
 * Produced by [SegmentGrouping.merge]. Nothing of this shape is ever stored:
 * it is derived on demand, because the rule that produces it is a judgement
 * that has not been settled yet.
 */
data class MergedSegment(
    val rows: List<ListeningSegmentEntity>,
    val trackId: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val listenedMs: Long,
    val trackDurationMs: Long,
    val forwardSeekCount: Int,
    val seekedForwardMs: Long,
    val endReason: EndReason,
) {
    /** True when more than one stored row was folded together. */
    val isMerged: Boolean get() = rows.size > 1

    /** How far through the track this listen got, as a fraction, or null. */
    val positionReachedFraction: Double?
        get() = if (trackDurationMs > 0) endPositionMs.toDouble() / trackDurationMs else null

    /** How much of the track was actually heard, as a fraction, or null. */
    val listenedFraction: Double?
        get() = if (trackDurationMs > 0) listenedMs.toDouble() / trackDurationMs else null
}

/**
 * Folds segments back together according to a replay threshold.
 *
 * Capture cuts a new segment at *every* backwards jump bigger than the noise
 * floor, because cutting finely is reversible and cutting coarsely is not. This
 * is the reverse operation: given a view about how large a backwards jump has to
 * be before it counts as replaying the track, it puts the over-cut rows back
 * together.
 *
 * That threshold is a Phase 3 decision and is deliberately not stored anywhere.
 * Passing a different value here answers the question differently, over the same
 * recorded history, with nothing needing to be captured again.
 */
object SegmentGrouping {

    /**
     * @param newestFirst segments ordered newest first, as the DAO returns them.
     * @param replayThresholdMs a backwards jump of at least this size is treated
     *   as a replay and keeps the segments apart. Null treats every recorded
     *   jump as a replay, i.e. no merging at all.
     */
    fun merge(
        newestFirst: List<ListeningSegmentEntity>,
        replayThresholdMs: Long?,
    ): List<MergedSegment> {
        if (newestFirst.isEmpty()) return emptyList()

        val oldestFirst = newestFirst.asReversed()
        val groups = mutableListOf<MutableList<ListeningSegmentEntity>>()

        for (row in oldestFirst) {
            val currentGroup = groups.lastOrNull()
            if (currentGroup != null && joinsOnto(currentGroup.last(), row, replayThresholdMs)) {
                currentGroup += row
            } else {
                groups += mutableListOf(row)
            }
        }

        return groups.map { fold(it) }.asReversed()
    }

    /** True when [next] is a continuation of [previous] rather than a new listen. */
    private fun joinsOnto(
        previous: ListeningSegmentEntity,
        next: ListeningSegmentEntity,
        replayThresholdMs: Long?,
    ): Boolean {
        if (replayThresholdMs == null) return false
        if (previous.trackId != next.trackId) return false
        if (previous.endReason != EndReason.POSITION_JUMP_BACK) return false
        val from = previous.endJumpFromMs ?: return false
        val to = previous.endJumpToMs ?: return false
        return (from - to) < replayThresholdMs
    }

    private fun fold(rows: List<ListeningSegmentEntity>): MergedSegment {
        val first = rows.first()
        val last = rows.last()
        return MergedSegment(
            rows = rows,
            trackId = first.trackId,
            startedAtMs = first.startedAtMs,
            endedAtMs = last.endedAtMs,
            startPositionMs = first.startPositionMs,
            endPositionMs = last.endPositionMs,
            // Listened time adds up across a merge; position reached does not,
            // which is exactly why the two are stored separately.
            listenedMs = rows.sumOf { it.listenedMs },
            trackDurationMs = rows.maxOf { it.trackDurationMs },
            forwardSeekCount = rows.sumOf { it.forwardSeekCount },
            seekedForwardMs = rows.sumOf { it.seekedForwardMs },
            endReason = last.endReason,
        )
    }
}
