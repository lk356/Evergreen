package com.example.evergreen.scoring

/** Why a listen scored what it did, for the explain-the-score view. */
enum class CurveNote {
    /** Scored normally from the fraction heard. */
    SCORED,

    /**
     * No duration is known for the track, so "how much of it was heard" has no
     * answer and the listen contributes nothing.
     *
     * Not the same as "heard none of it", which is a rejection. This is the
     * absence of a measurement, and the two must not be conflated: Spotify DJ
     * commentary arrives with a zero duration, and so does an imported row for
     * a track the app has never seen played or liked. Treating either as a 0%
     * listen would score a penalty for something that was never observed.
     */
    UNKNOWN_DURATION,
}

/** What the curve made of one listen. */
data class CurveOutcome(
    /** `listenedMs / durationMs`, or null when no duration is known. */
    val fractionHeard: Double?,
    /** The contribution in play-equivalents, before decay. */
    val playEquivalents: Double,
    val note: CurveNote,
)

/**
 * Turns "how much of the track was heard" into a contribution.
 *
 * One curve does the work of the two separate ideas the earlier drafts had — a
 * "skip penalty" and a "minimum listened time to count" — which turned out to
 * be the same question asked twice (decisions 2a). A two-second fragment, a
 * genuine mid-song skip and a skip at 95% are all handled by one shape:
 *
 * ```
 *  +1.0                                     ┌────────────
 *                                          /
 *   0.0  ────────┐              ┌─────────/
 *                 \            /
 *  -0.5  ──────────┘ ─ ─ ─ ─ ─ ┘
 *        0      0.15  0.25    0.80  0.90        1.0
 *                 └ 0.20 ┘      └ 0.85 ┘
 *                  lower         upper
 * ```
 *
 * Below the lower breakpoint the listen is a rejection; between the
 * breakpoints it is neutral; above the upper breakpoint it is a full play.
 * Both breakpoints were calibrated against 16,000 real plays
 * (`docs/PHASE3_DECISIONS.md` 3.1).
 *
 * **Why it ramps rather than steps.** The upper breakpoint is the softer of the
 * two readings — the calibration found completed plays overtake cut-short ones
 * at 0.86, but cut-short plays are spread evenly from 0.25 to 0.95, so there is
 * no sharp change in behaviour there to step on. A step would mean 84% heard
 * scored 0 and 86% scored a full play, which is a bigger claim than the data
 * supports. The ramp spends the uncertainty on a gradient instead.
 */
object ScoringCurve {

    /**
     * Half-width of the linear ramp either side of each breakpoint.
     *
     * A constant with a reason rather than a setting: it expresses how sharply
     * the breakpoints are known, which is a property of the calibration and not
     * something to tune per-user. `docs/PHASE3_PLAN.md` section 3.2 item 4
     * fixes it at 0.05.
     */
    const val RAMP_HALF_WIDTH: Double = 0.05

    /**
     * Scores one listen.
     *
     * @param listenedMs milliseconds actually heard — never the position
     *   reached. The two differ whenever there was a seek or a crossfade, and
     *   using position here is the Phase 0 bug that recorded a barely-heard
     *   track as a completed play.
     * @param durationMs the track's length, or 0 when it is not known.
     */
    fun outcome(listenedMs: Long, durationMs: Long, parameters: ScoringParameters): CurveOutcome {
        if (durationMs <= 0L) {
            return CurveOutcome(
                fractionHeard = null,
                playEquivalents = 0.0,
                note = CurveNote.UNKNOWN_DURATION,
            )
        }
        val fraction = listenedMs.toDouble() / durationMs.toDouble()
        return CurveOutcome(
            fractionHeard = fraction,
            playEquivalents = playEquivalents(fraction, parameters),
            note = CurveNote.SCORED,
        )
    }

    /**
     * The curve itself, in play-equivalents.
     *
     * Written as the sum of two ramps rather than as a five-way `when` on which
     * segment the fraction falls in. Both forms agree on the default settings,
     * but this one stays monotonic and well defined if the breakpoints are ever
     * moved closer together than the ramps are wide — the piecewise form would
     * have overlapping cases there and could score a longer listen lower than a
     * shorter one. Since the breakpoints are user-editable, that is not a
     * hypothetical.
     *
     * A fraction above 1.0 is possible and is handled by the same cap as a full
     * play: folded replays sum their listened time, so looping a track twice
     * within one segment group reads as 2.0. Loops are limited by the daily cap
     * (decisions 2e), not here.
     */
    fun playEquivalents(fractionHeard: Double, parameters: ScoringParameters): Double {
        val pastLower = ramp(fractionHeard, parameters.lowerBreakpoint)
        val pastUpper = ramp(fractionHeard, parameters.upperBreakpoint)
        // Starts at the negative weight, is lifted to zero across the lower
        // breakpoint, then raised to a full play across the upper one.
        return parameters.negativeWeight * (1.0 - pastLower) + pastUpper
    }

    /**
     * 0 below the breakpoint's ramp, 1 above it, linear across it.
     *
     * Non-decreasing in [fractionHeard] for any breakpoint, which is what makes
     * the sum above monotonic.
     */
    private fun ramp(fractionHeard: Double, breakpoint: Double): Double {
        val start = breakpoint - RAMP_HALF_WIDTH
        val width = 2.0 * RAMP_HALF_WIDTH
        return ((fractionHeard - start) / width).coerceIn(0.0, 1.0)
    }
}
