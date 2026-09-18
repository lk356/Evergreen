package com.example.evergreen.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 1 of `docs/PHASE3_PLAN.md` section 9.
 *
 * The numbers here are the ones the plan names: fraction 0 → −0.5; 0.15 → −0.5;
 * 0.20 → mid-ramp; 0.5 → 0; 0.85 → mid-ramp; 0.95 → +1; 1.0 → +1; monotonic
 * across [0, 1]; unknown duration → 0 with a stated reason.
 */
class ScoringCurveTest {

    private val defaults = ScoringParameters()

    private fun at(fraction: Double) = ScoringCurve.playEquivalents(fraction, defaults)

    // --- The points the plan names -------------------------------------------

    @Test
    fun `nothing heard is a full rejection`() {
        assertEquals(-0.5, at(0.0), TOLERANCE)
    }

    @Test
    fun `below the lower ramp is a full rejection`() {
        assertEquals(-0.5, at(0.05), TOLERANCE)
        assertEquals(-0.5, at(0.10), TOLERANCE)
        // The ramp starts here, so this is still the full rejection.
        assertEquals(-0.5, at(0.15), TOLERANCE)
    }

    /** The breakpoint sits in the middle of its ramp, so it scores half. */
    @Test
    fun `the lower breakpoint is mid-ramp`() {
        assertEquals(-0.25, at(0.20), TOLERANCE)
    }

    @Test
    fun `above the lower ramp is neutral`() {
        assertEquals(0.0, at(0.25), TOLERANCE)
        assertEquals(0.0, at(0.5), TOLERANCE)
        assertEquals(0.0, at(0.75), TOLERANCE)
        assertEquals(0.0, at(0.80), TOLERANCE)
    }

    @Test
    fun `the upper breakpoint is mid-ramp`() {
        assertEquals(0.5, at(0.85), TOLERANCE)
    }

    @Test
    fun `above the upper ramp is a full play`() {
        assertEquals(1.0, at(0.90), TOLERANCE)
        assertEquals(1.0, at(0.95), TOLERANCE)
        assertEquals(1.0, at(1.0), TOLERANCE)
    }

    // --- Shape ---------------------------------------------------------------

    @Test
    fun `the curve never decreases as more is heard`() {
        var previous = Double.NEGATIVE_INFINITY
        var fraction = 0.0
        while (fraction <= 1.0001) {
            val value = at(fraction)
            assertTrue("fell at $fraction: $previous -> $value", value >= previous - TOLERANCE)
            previous = value
            fraction += 0.001
        }
    }

    @Test
    fun `the ramps are linear`() {
        // Lower ramp: -0.5 at 0.15, -0.25 at 0.20, 0 at 0.25.
        assertEquals(-0.375, at(0.175), TOLERANCE)
        // Upper ramp: 0 at 0.80, 0.5 at 0.85, 1.0 at 0.90.
        assertEquals(0.25, at(0.825), TOLERANCE)
        assertEquals(0.75, at(0.875), TOLERANCE)
    }

    /**
     * A folded replay sums its listened time, so a track looped twice inside one
     * segment group reads as 2.0. It is worth one play here; repeated listening
     * is limited by the daily cap (decisions 2e), not by this curve.
     */
    @Test
    fun `hearing more than the whole track is still one play`() {
        assertEquals(1.0, at(1.5), TOLERANCE)
        assertEquals(1.0, at(40.0), TOLERANCE)
    }

    // --- Unknown duration ----------------------------------------------------

    /**
     * "No duration known" is not "heard none of it".
     *
     * Spotify DJ commentary carries a zero duration, and so does an imported row
     * for a track never played or liked since. Scoring either as a 0% listen
     * would charge a rejection penalty for something that was never observed.
     */
    @Test
    fun `an unknown duration contributes nothing, with a reason`() {
        val outcome = ScoringCurve.outcome(listenedMs = 30_000, durationMs = 0, parameters = defaults)

        assertEquals(0.0, outcome.playEquivalents, TOLERANCE)
        assertEquals(CurveNote.UNKNOWN_DURATION, outcome.note)
        assertNull(outcome.fractionHeard)
    }

    @Test
    fun `a negative duration is treated as unknown, not as a huge fraction`() {
        val outcome = ScoringCurve.outcome(listenedMs = 30_000, durationMs = -1, parameters = defaults)

        assertEquals(0.0, outcome.playEquivalents, TOLERANCE)
        assertEquals(CurveNote.UNKNOWN_DURATION, outcome.note)
    }

    @Test
    fun `a known duration is scored and reports its fraction`() {
        val outcome = ScoringCurve.outcome(listenedMs = 180_000, durationMs = 200_000, defaults)

        assertEquals(0.9, outcome.fractionHeard!!, TOLERANCE)
        assertEquals(1.0, outcome.playEquivalents, TOLERANCE)
        assertEquals(CurveNote.SCORED, outcome.note)
    }

    @Test
    fun `hearing none of a track of known length is a rejection`() {
        val outcome = ScoringCurve.outcome(listenedMs = 0, durationMs = 200_000, defaults)

        assertEquals(0.0, outcome.fractionHeard!!, TOLERANCE)
        assertEquals(-0.5, outcome.playEquivalents, TOLERANCE)
        assertEquals(CurveNote.SCORED, outcome.note)
    }

    // --- The breakpoints are settings ----------------------------------------

    /** Decision 10.7 keeps 0.80 available; moving it must move the curve. */
    @Test
    fun `moving the upper breakpoint to 0-80 moves the full-credit point`() {
        val tuned = defaults.copy(upperBreakpoint = 0.80)

        assertEquals(0.5, ScoringCurve.playEquivalents(0.80, tuned), TOLERANCE)
        assertEquals(1.0, ScoringCurve.playEquivalents(0.85, tuned), TOLERANCE)
    }

    @Test
    fun `a gentler negative weight moves only the rejection end`() {
        val tuned = defaults.copy(negativeWeight = -0.2)

        assertEquals(-0.2, ScoringCurve.playEquivalents(0.0, tuned), TOLERANCE)
        assertEquals(0.0, ScoringCurve.playEquivalents(0.5, tuned), TOLERANCE)
        assertEquals(1.0, ScoringCurve.playEquivalents(0.95, tuned), TOLERANCE)
    }

    /**
     * The breakpoints are editable by hand, so they can be set closer together
     * than the ramps are wide. The curve must stay monotonic there — a shape
     * that scored a longer listen lower than a shorter one would be a silent
     * scoring bug, and this is why the curve is a sum of ramps rather than a
     * five-way branch.
     */
    @Test
    fun `overlapping ramps stay monotonic`() {
        val cramped = defaults.copy(lowerBreakpoint = 0.40, upperBreakpoint = 0.44)

        var previous = Double.NEGATIVE_INFINITY
        var fraction = 0.0
        while (fraction <= 1.0001) {
            val value = ScoringCurve.playEquivalents(fraction, cramped)
            assertTrue("fell at $fraction: $previous -> $value", value >= previous - TOLERANCE)
            previous = value
            fraction += 0.001
        }
        assertEquals(-0.5, ScoringCurve.playEquivalents(0.0, cramped), TOLERANCE)
        assertEquals(1.0, ScoringCurve.playEquivalents(1.0, cramped), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
