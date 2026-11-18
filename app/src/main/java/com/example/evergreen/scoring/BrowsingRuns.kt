package com.example.evergreen.scoring

/**
 * One row of the whole listening timeline, every track, liked or not.
 *
 * Narrow on purpose: finding browsing runs needs to know *when* something was
 * heard and for *how long*, on *which* timeline, and nothing else. The ranker
 * loads full rows only for liked tracks; this is the cheap pass over the rest.
 */
data class TimelineListen(
    /** The device that recorded it. The import is a timeline of its own. */
    val timeline: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val listenedMs: Long,
    /** DJ commentary: neither part of a run nor a break in one. */
    val isCommentary: Boolean,
)

/**
 * A stretch of one timeline where the user was flicking through songs rather
 * than judging them.
 *
 * Like [DjWindow], worked out once per recompute over the whole timeline and
 * handed to the scorer, because the songs flicked past are mostly not liked
 * and so are never scored themselves.
 */
data class BrowsingWindow(val timeline: String, val startMs: Long, val endMs: Long)

/**
 * Finds browsing runs: several very short listens in a row.
 *
 * **A run is a discount, not a verdict** (decided by the user 2026-11-17,
 * `docs/PLAN.md`). A run can be a search, forwards for something new or
 * backwards for something just heard, or it can be a string of songs the user
 * no longer wants. Nothing on one evening tells those apart. What does is
 * repetition across days, which the skip streak (change D) counts, so a skip
 * inside a run is scaled by [ScoringParameters.rapidRunSkipWeight] rather than
 * forgiven outright, and a song passed in every search for a week still pays.
 *
 * Off when [ScoringParameters.rapidRunMinSkips] is 0, and then nothing here
 * runs at all.
 */
object BrowsingRuns {

    /**
     * A gap longer than this between one listen ending and the next starting
     * ends a run.
     *
     * A constant with a reason, like [DjSessions.WINDOW_MS]. Flicking leaves
     * gaps of a second or two; thirty seconds still allows a pause to look at
     * the screen, and anything longer is a different sitting. Approved by the
     * user 2026-11-17.
     */
    const val MAX_GAP_MS: Long = 30_000L

    /**
     * A run listen of a track heard on the same timeline this recently is a
     * **revisit**: passing back over a song while looking for another one, and
     * worth nothing whatever the weight.
     *
     * The app cannot see which button was pressed, only that the track
     * changed, but going *back* passes through songs just heard. Two hours
     * covers a long listening session. A constant, not a setting, for the same
     * reason as [MAX_GAP_MS].
     */
    const val REVISIT_WINDOW_MS: Long = 2 * 60 * 60 * 1000L

    /**
     * @param listens the timeline, any order. Rows recorded from a remote
     *   session should already be left out: they are another device playing.
     * @return one window per run of at least [minSkips] listens, each no
     *   longer than [maxListenMs], with no gap over [MAX_GAP_MS] between them.
     *   Oldest first within each timeline, timelines in name order.
     */
    fun find(listens: List<TimelineListen>, minSkips: Int, maxListenMs: Long): List<BrowsingWindow> {
        if (minSkips <= 0) return emptyList()
        val windows = mutableListOf<BrowsingWindow>()

        listens.groupBy { it.timeline }.toSortedMap().forEach { (timeline, rows) ->
            // Ties broken on the end and the length so that the same facts give
            // the same windows whatever order the database returned them in.
            val ordered = rows.sortedWith(
                compareBy<TimelineListen> { it.startedAtMs }.thenBy { it.endedAtMs }.thenBy { it.listenedMs },
            )
            var runStart = 0L
            var runEnd = 0L
            var runLength = 0
            var previousEnd: Long? = null

            fun close() {
                if (runLength >= minSkips) windows += BrowsingWindow(timeline, runStart, runEnd)
                runLength = 0
            }

            for (listen in ordered) {
                val gapOk = previousEnd != null && listen.startedAtMs - previousEnd <= MAX_GAP_MS
                if (listen.isCommentary) {
                    // The DJ talking between two flicks is not a pause in
                    // flicking: it moves the clock on without ending the run.
                    if (!gapOk) close()
                    previousEnd = maxOf(previousEnd ?: listen.endedAtMs, listen.endedAtMs)
                    continue
                }
                if (listen.listenedMs <= maxListenMs) {
                    if (runLength > 0 && gapOk) {
                        runEnd = maxOf(runEnd, listen.endedAtMs)
                        runLength++
                    } else {
                        close()
                        runStart = listen.startedAtMs
                        runEnd = listen.endedAtMs
                        runLength = 1
                    }
                } else {
                    close()
                }
                previousEnd = maxOf(previousEnd ?: listen.endedAtMs, listen.endedAtMs)
            }
            close()
        }
        return windows
    }

    /** True when a listen on [timeline] starting at [startedAtMs] is inside a run. */
    fun isInRun(windows: List<BrowsingWindow>, timeline: String, startedAtMs: Long): Boolean =
        windows.any { it.timeline == timeline && startedAtMs in it.startMs..it.endMs }
}
