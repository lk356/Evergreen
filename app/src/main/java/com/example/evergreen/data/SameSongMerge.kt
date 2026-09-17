package com.example.evergreen.data

/** One group of canonical tracks that a merge would collapse into one. */
data class MergePlanGroup(
    val isrc: String,
    val survivor: MergeCandidate,
    val losers: List<MergeCandidate>,
) {
    val segmentsToMove: Int get() = losers.sumOf { it.segmentCount }
}

/**
 * Decides what a same-song merge *would* do, without touching anything.
 *
 * `trackKey` includes the album, so the single and the album release of one
 * song are two canonical tracks. An ISRC identifies a *recording*, which makes
 * two tracks sharing one the same song by the only definition the industry
 * offers.
 *
 * Planning is separated from executing so the plan can be shown, read and
 * approved first. Nothing here writes.
 *
 * **Known limit:** a remaster is issued a fresh ISRC, so it will not merge with
 * its original. That is arguably correct — it genuinely is a different
 * recording — but it means this catches same-recording pairs only, not every
 * pair a listener would call "the same song".
 */
object SameSongMerge {

    /**
     * Groups candidates by ISRC and picks a survivor for each.
     *
     * The survivor is whichever track holds the most listening segments, so the
     * merge moves as little history as possible; ties go to the lowest id,
     * which is the one seen first. Both rules are arbitrary in isolation, but
     * being deterministic matters: the same data must plan the same merge every
     * time, or a dry run says nothing about what the real run will do.
     */
    fun plan(candidates: List<MergeCandidate>): List<MergePlanGroup> =
        candidates
            .groupBy { it.isrc }
            .mapNotNull { (isrc, rows) ->
                val distinct = rows.distinctBy { it.trackId }
                if (distinct.size < 2) return@mapNotNull null

                val ordered = distinct.sortedWith(
                    compareByDescending<MergeCandidate> { it.segmentCount }.thenBy { it.trackId },
                )
                MergePlanGroup(
                    isrc = isrc,
                    survivor = ordered.first(),
                    losers = ordered.drop(1),
                )
            }
            .sortedBy { it.isrc }
}
