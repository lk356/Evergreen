package com.example.evergreen.capture

/**
 * Tuning constants for the capture layer.
 *
 * An important distinction runs through this file. These thresholds decide
 * **what the sensor can see** — whether a movement was real or was noise. None
 * of them decides **what a movement means**. "Was that a replay?" is a question
 * about the user's intent, it needs a threshold nobody has chosen yet, and it
 * belongs to Phase 3. Because every jump is stored with its size, Phase 3 can
 * answer it later, change its mind, and answer it differently — without any of
 * the listening history having to be recorded again.
 */
object CaptureThresholds {

    /**
     * A backwards position jump larger than this closes the current segment and
     * opens a new one.
     *
     * This is a noise floor, not a judgement. Device testing measured backwards
     * jitter of about 80 ms with no user action at all (`reported=128ms` then
     * `reported=52ms`), so this sits roughly 12x above it, and comfortably below
     * any deliberate button press or scrub.
     *
     * Cutting finely is safe because it is reversible: two adjacent rows for the
     * same track can always be added back together later, using the jump size
     * stored on the closing row. Cutting coarsely is not reversible — a jump
     * that was never recorded cannot be recovered. So this stays low, and the
     * real "is that a replay?" threshold is left to Phase 3.
     */
    const val POSITION_JUMP_BACK_CUT_MS = 1_000L

    /**
     * A forward jump larger than this is counted as a seek rather than as
     * ordinary playback drift.
     *
     * Forward seeks deliberately do *not* close a segment: skipping ahead does
     * not restart anything, so the listen is still continuous. The amount jumped
     * over is recorded instead, and it is excluded from listened time.
     */
    const val FORWARD_SEEK_CUT_MS = 1_000L

    /**
     * Beyond this age, a position reading is old enough that extrapolating from
     * it is guesswork rather than measurement.
     *
     * Carried over from the Phase 0 spike, where it never actually fired — not
     * even with the screen locked for a whole track. Segments record how stale
     * their final reading was, so Phase 3 can discount rows that lean on a long
     * extrapolation instead of trusting them blindly.
     */
    const val STALE_POSITION_MS = 30_000L

    /**
     * A segment with no readings for this long is closed and written out.
     *
     * Purely a safety net against losing data if the process is killed while a
     * segment is open. It invents nothing: listened time only ever accumulates
     * from readings that actually arrived.
     */
    const val STALE_CLOSE_MS = 30L * 60L * 1_000L

    /**
     * How long a freshly opened segment stays amendable.
     *
     * At a real track change the media session sometimes publishes the new
     * track's position (near zero) *before* it publishes the new metadata. For
     * that instant a track change is indistinguishable from a jump back to the
     * start. Rather than guess, the closing segment is held briefly: if the
     * metadata change turns up within this window and nothing was heard in the
     * meantime, the jump is recognised for what it was and the reason is
     * corrected to a track change.
     */
    const val AMEND_WINDOW_MS = 2_000L
}
