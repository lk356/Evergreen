package com.example.evergreen.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 2 of `docs/PHASE3_PLAN.md` section 9: weight 1.0 at 0 days, 0.5 at 45,
 * 0.25 at 90; and invariant 1c, that a 2019 play is worth under 0.001 today.
 */
class DecayTest {

    private val defaults = ScoringParameters()

    private fun weightAfterDays(days: Double, parameters: ScoringParameters = defaults): Double =
        Decay.weight(
            eventAtMs = NOW - (days * ScoringParameters.MS_PER_DAY).toLong(),
            nowMs = NOW,
            parameters = parameters,
        )

    @Test
    fun `an event now has full weight`() {
        assertEquals(1.0, weightAfterDays(0.0), TOLERANCE)
    }

    @Test
    fun `one half-life halves the weight`() {
        assertEquals(0.5, weightAfterDays(45.0), TOLERANCE)
    }

    @Test
    fun `two half-lives quarter it`() {
        assertEquals(0.25, weightAfterDays(90.0), TOLERANCE)
    }

    @Test
    fun `three half-lives leave an eighth`() {
        assertEquals(0.125, weightAfterDays(135.0), TOLERANCE)
    }

    /**
     * Invariant 1c, and the reason imported history can be trusted.
     *
     * Six years of streaming history is about to land in the segment table. If
     * old plays did not decay by their own timestamps, Evergreen would be a
     * monument to whatever was on repeat in 2019 rather than a record of what is
     * still being listened to.
     */
    @Test
    fun `a 2019 play is worth almost nothing today`() {
        // 2019-06-15 to the date this was written, 2026-09-17: about 2,651 days.
        val daysSince2019 = 2_651.0

        val weight = weightAfterDays(daysSince2019)

        assertTrue("2019 play still weighs $weight", weight < 0.001)
    }

    /** The ten-half-life load window in [ScoringParameters.loadWindowMs]. */
    @Test
    fun `ten half-lives leave under a thousandth`() {
        val weight = weightAfterDays(450.0)

        assertEquals(0.5.pow(10), weight, TOLERANCE)
        assertTrue("$weight", weight < 0.001)
    }

    /**
     * A wrong device clock, or an import carrying a bad timestamp, must not be
     * able to score *more* than something happening right now. That is the only
     * direction in which a clock error could inflate a score.
     */
    @Test
    fun `an event dated in the future is clamped to full weight, never more`() {
        val weight = Decay.weight(
            eventAtMs = NOW + 30L * 24 * 60 * 60 * 1000,
            nowMs = NOW,
            parameters = defaults,
        )

        assertEquals(1.0, weight, TOLERANCE)
    }

    @Test
    fun `the weight always falls as the event gets older`() {
        var previous = Double.POSITIVE_INFINITY
        for (days in 0..2000) {
            val weight = weightAfterDays(days.toDouble())
            assertTrue("rose at $days days", weight <= previous + TOLERANCE)
            assertTrue("negative at $days days", weight >= 0.0)
            previous = weight
        }
    }

    /** The half-life is a setting, so changing it must change the weights. */
    @Test
    fun `a 90-day half-life halves at 90 days instead`() {
        val slower = defaults.copy(halfLifeDays = 90.0)

        assertEquals(0.5, weightAfterDays(90.0, slower), TOLERANCE)
        assertEquals(0.25, weightAfterDays(180.0, slower), TOLERANCE)
        // The same 45-day-old play is worth more under the slower decay.
        assertTrue(weightAfterDays(45.0, slower) > weightAfterDays(45.0, defaults))
    }

    private fun Double.pow(exponent: Int): Double = Math.pow(this, exponent.toDouble())

    private companion object {
        /** 2026-09-17T12:00:00Z, the day the calibration was run. */
        const val NOW = 1_789_128_000_000L
        const val TOLERANCE = 1e-9
    }
}
