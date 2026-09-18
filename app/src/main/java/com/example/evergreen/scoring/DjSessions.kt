package com.example.evergreen.scoring

/**
 * A stretch of time during which Spotify DJ was probably running.
 *
 * "Probably" is the honest word, and it is why this type exists rather than a
 * column on the segment table. Whether a *song* was chosen by the DJ is not
 * observable: `appPackage` is `com.spotify.music` either way and the URI is an
 * ordinary `spotify:track:`. Only the commentary rows around it hint that a DJ
 * session was in progress.
 */
data class DjWindow(val startMs: Long, val endMs: Long) {
    fun contains(atMs: Long): Boolean = atMs in startMs..endMs
}

/**
 * Works out when Spotify DJ was talking, from the commentary rows capture
 * stored.
 *
 * Commentary is observable and distinguishable: it arrives as
 * `spotify:media:<id>` rather than `spotify:track:<id>`, with a zero duration
 * (Phase 2 device findings). Capture stores it like any other row, because the
 * project's rule is that capture records facts and scoring interprets them.
 * This is the interpreting.
 *
 * **What the guess is used for, and why being wrong is cheap.** Under decision
 * 2g a listen inside a DJ window has its *negative* contribution suppressed and
 * its positive one left alone. Skipping through DJ picks is browsing, not
 * rejection — the user is being played songs they did not choose. But letting
 * one play through is real information about the song whoever queued it. So a
 * misfiring inference costs at most a penalty that might have been deserved,
 * and can never invent a rejection or a play that did not happen.
 *
 * That asymmetry is what justifies guessing at all.
 */
object DjSessions {

    /**
     * How long after a commentary row a listen is still "during a DJ session".
     *
     * A constant with a comment rather than a setting, deliberately. It
     * expresses how often the DJ speaks — every few songs — which is a fact
     * about Spotify's product, not a preference of the user's. And because
     * being wrong only ever forgives a penalty, there is nothing here worth
     * tuning: the cost of the window being slightly too long or too short is
     * not something the user would be able to perceive, let alone adjust
     * sensibly.
     */
    const val WINDOW_MS: Long = 20 * 60 * 1000

    /**
     * Turns commentary timestamps into windows.
     *
     * @param commentaryEndedAtMs when each commentary segment ended, in any
     *   order.
     * @return non-overlapping windows, oldest first. Adjacent commentary rows
     *   are merged into one window rather than left as overlapping pairs, so
     *   that a long DJ session is one window and [isDuringSession] does not
     *   have to scan a list of near-duplicates.
     */
    fun infer(commentaryEndedAtMs: List<Long>, windowMs: Long = WINDOW_MS): List<DjWindow> {
        if (commentaryEndedAtMs.isEmpty()) return emptyList()

        val sorted = commentaryEndedAtMs.sorted()
        val windows = mutableListOf<DjWindow>()
        var start = sorted.first()
        var end = start + windowMs

        for (at in sorted.drop(1)) {
            if (at <= end) {
                // Still inside the running window: extend it rather than
                // starting a second one that overlaps the first.
                end = at + windowMs
            } else {
                windows += DjWindow(start, end)
                start = at
                end = at + windowMs
            }
        }
        windows += DjWindow(start, end)
        return windows
    }

    /**
     * True when a listen starting at [listenStartedAtMs] falls inside a session.
     *
     * Judged on when the listen *started*, because that is when the choice of
     * what to play was made. A song begun during a DJ session and finished
     * after the window closes was still a song the DJ chose.
     */
    fun isDuringSession(windows: List<DjWindow>, listenStartedAtMs: Long): Boolean =
        windows.any { window -> window.contains(listenStartedAtMs) }
}
