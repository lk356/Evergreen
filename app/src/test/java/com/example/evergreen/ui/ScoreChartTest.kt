package com.example.evergreen.ui

import com.example.evergreen.scoring.ScorePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers under the score-over-time line.
 *
 * The sentence the chart prints is a claim about volatility, which is the
 * question the last phase left open — so it has to be arithmetically right
 * rather than roughly right. In particular the *fraction* matters more than the
 * absolute fall: 0.4 off a score of 1 and 0.4 off a score of 40 are completely
 * different judgements about whether the scoring is too jumpy.
 */
class ScoreChartTest {

    private fun points(vararg scores: Double) =
        scores.mapIndexed { index, score -> ScorePoint(index * 1000L, score) }

    @Test
    fun anEmptyLineHasNoStats() {
        assertNull(scoreChartStats(emptyList()))
    }

    @Test
    fun theStatsReadTheEndsAndTheExtremes() {
        val stats = scoreChartStats(points(1.0, 4.0, 2.0, 3.5))!!
        assertEquals(1.0, stats.lowest, 1e-9)
        assertEquals(4.0, stats.highest, 1e-9)
        assertEquals(3.5, stats.latest, 1e-9)
    }

    @Test
    fun theBiggestDropIsTheSteepestFallBetweenTwoSamples() {
        val stats = scoreChartStats(points(10.0, 9.5, 4.0, 5.0, 4.9))!!
        assertEquals(5.5, stats.biggestDrop, 1e-9)
        assertEquals(5.5 / 9.5, stats.biggestDropFraction, 1e-9)
    }

    /** A line that only ever climbs has no fall to report. */
    @Test
    fun aLineThatOnlyRisesReportsNoDrop() {
        val stats = scoreChartStats(points(0.0, 1.0, 2.0, 3.0))!!
        assertEquals(0.0, stats.biggestDrop, 1e-9)
        assertTrue(describeVolatility(stats).contains("has not fallen"))
    }

    /**
     * The same absolute fall is a different story at a different height, and
     * the sentence has to say which.
     */
    @Test
    fun theSameFallReadsDifferentlyAtDifferentHeights() {
        val small = scoreChartStats(points(1.0, 0.6))!!
        val large = scoreChartStats(points(40.0, 39.6))!!

        assertEquals(small.biggestDrop, large.biggestDrop, 1e-9)
        assertTrue(describeVolatility(small).contains("40%"))
        assertTrue(describeVolatility(large).contains("1%"))
    }

    /** A fall from zero is not an infinite proportion. */
    @Test
    fun aFallFromZeroDoesNotDivideByIt() {
        val stats = scoreChartStats(points(0.0, -0.5))!!
        assertEquals(0.5, stats.biggestDrop, 1e-9)
        assertEquals(0.0, stats.biggestDropFraction, 1e-9)
        // And it still produces a sentence rather than a NaN.
        assertTrue(describeVolatility(stats).contains("0%"))
    }

    @Test
    fun aSinglePointIsNotALine() {
        val stats = scoreChartStats(points(3.0))!!
        assertEquals(3.0, stats.latest, 1e-9)
        assertEquals(0.0, stats.biggestDrop, 1e-9)
    }
}
