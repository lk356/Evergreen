package com.example.evergreen.ui

import com.example.evergreen.scoring.ScoringParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings screen's arithmetic.
 *
 * The screen replaces a JSON text box, which had one virtue: what you typed was
 * what got stored. A stepper can get that wrong in ways a text box cannot — by
 * drifting, by walking past a bound, or by producing a value the scorer then
 * refuses. So the rules live in plain data and are tested here rather than
 * being inferred from a device.
 */
class ScoringSettingsTest {

    /**
     * No sequence of taps on any control may produce settings the scorer
     * rejects.
     *
     * This is the one that matters. `problems()` is the backstop, but a user
     * who reaches an invalid state through the controls sees a save button that
     * has silently stopped working with no way to tell why.
     */
    @Test
    fun noSequenceOfTapsCanReachSettingsTheScorerRefuses() {
        SCORING_SETTINGS.forEach { setting ->
            var up = ScoringParameters()
            repeat(500) {
                up = setting.increase(up) ?: return@repeat
                assertTrue(
                    "${setting.title} went invalid going up: ${up.problems()}",
                    up.problems().isEmpty(),
                )
            }
            var down = ScoringParameters()
            repeat(500) {
                down = setting.decrease(down) ?: return@repeat
                assertTrue(
                    "${setting.title} went invalid going down: ${down.problems()}",
                    down.problems().isEmpty(),
                )
            }
        }
    }

    /** Every control eventually stops, rather than running away forever. */
    @Test
    fun everyControlHasBothBounds() {
        SCORING_SETTINGS.forEach { setting ->
            var up: ScoringParameters? = ScoringParameters()
            var steps = 0
            while (up != null && steps < 10_000) {
                up = setting.increase(up)
                steps++
            }
            assertTrue("${setting.title} has no upper bound", steps < 10_000)

            var down: ScoringParameters? = ScoringParameters()
            steps = 0
            while (down != null && steps < 10_000) {
                down = setting.decrease(down)
                steps++
            }
            assertTrue("${setting.title} has no lower bound", steps < 10_000)
        }
    }

    /**
     * Stepping up and back down again must land exactly where it started.
     *
     * Repeatedly adding 0.05 to a Double drifts — 0.2 + 0.05 is
     * 0.25000000000000006 — and the parameters' JSON is canonical, so drift
     * changes the hash, which rebuilds the whole score cache for a value the
     * user believes they put back. Worse, it would write a parameter-history
     * row for a change that did not happen.
     */
    @Test
    fun steppingUpAndBackDownReturnsTheExactSameSettings() {
        val start = ScoringParameters()
        SCORING_SETTINGS.forEach { setting ->
            var value = start
            // Count the taps that actually moved. A control sitting near its
            // bound takes fewer steps up than down, and stepping back the same
            // number of times would land somewhere else through no fault of the
            // arithmetic.
            var taken = 0
            repeat(4) {
                setting.increase(value)?.let { next ->
                    value = next
                    taken++
                }
            }
            repeat(taken) { value = setting.decrease(value) ?: value }
            assertEquals(
                "${setting.title} did not return to where it started",
                start.toJson(),
                value.toJson(),
            )
            assertEquals(
                "${setting.title} changed the hash, which would rebuild the cache",
                start.hash(),
                value.hash(),
            )
        }
    }

    /**
     * The two breakpoints cannot cross, from either direction.
     *
     * The scorer's fraction-heard curve is a sum of two ramps rather than a
     * five-way branch precisely because the breakpoints are user-editable, but
     * the lower one still has to stay below the upper or the settings are
     * refused on save.
     */
    @Test
    fun theTwoBreakpointsCannotCross() {
        val lower = SCORING_SETTINGS.single { it.title == "What counts as rejecting a song" }
        val upper = SCORING_SETTINGS.single { it.title == "What counts as a full play" }

        var raised: ScoringParameters = ScoringParameters()
        repeat(100) { raised = lower.increase(raised) ?: raised }
        assertTrue(raised.lowerBreakpoint < raised.upperBreakpoint)
        assertTrue(raised.problems().isEmpty())

        var lowered: ScoringParameters = ScoringParameters()
        repeat(100) { lowered = upper.decrease(lowered) ?: lowered }
        assertTrue(lowered.upperBreakpoint > lowered.lowerBreakpoint)
        assertTrue(lowered.problems().isEmpty())
    }

    /**
     * Raising the join rank past the leave rank drags the leave rank with it.
     *
     * Otherwise the user sets "top 150" and gets a save button that refuses,
     * over a relationship between two controls that is never stated on screen.
     */
    @Test
    fun raisingThePlaylistSizePushesTheDropOutRankAhead() {
        val join = SCORING_SETTINGS.single { it.title == "How big the playlist is" }
        // One step takes the join rank to 130, the second past it.
        var value = ScoringParameters(playlistJoinRank = 125, playlistLeaveRank = 130)
        repeat(2) { value = requireNotNull(join.increase(value)) { "the join rank should still move" } }
        assertEquals(135, value.playlistJoinRank)
        assertTrue(value.playlistLeaveRank >= value.playlistJoinRank)
        assertTrue(value.problems().isEmpty())
    }

    @Test
    fun theDropOutRankCannotFallBelowTheJoinRank() {
        val leave = SCORING_SETTINGS.single { it.title == "How far a song falls before it leaves" }
        var value: ScoringParameters = ScoringParameters()
        repeat(100) { value = leave.decrease(value) ?: value }
        assertEquals(value.playlistJoinRank, value.playlistLeaveRank)
        assertTrue(value.problems().isEmpty())
    }

    @Test
    fun aSkipCannotBeMadePositive() {
        val skip = SCORING_SETTINGS.single { it.title == "What a skip costs" }
        var value: ScoringParameters = ScoringParameters()
        repeat(100) { value = skip.increase(value) ?: value }
        assertEquals(0.0, value.negativeWeight, 1e-9)
        assertNull("it must stop at zero, not go positive", skip.increase(value))
    }

    @Test
    fun theHalfLifeCannotReachZero() {
        val halfLife = SCORING_SETTINGS.single { it.title == "How fast listening fades" }
        var value: ScoringParameters = ScoringParameters()
        repeat(200) { value = halfLife.decrease(value) ?: value }
        assertTrue("a half-life of zero divides every score by it", value.halfLifeDays > 0.0)
    }

    /** Every control explains itself in a sentence rather than naming a field. */
    @Test
    fun everySettingReadsAsEnglishRatherThanAsAKey() {
        val defaults = ScoringParameters()
        SCORING_SETTINGS.forEach { setting ->
            val explanation = setting.explain(defaults)
            assertTrue("${setting.title} has no explanation", explanation.length > 20)
            listOf(
                "halfLifeDays", "lowerBreakpoint", "upperBreakpoint", "negativeWeight",
                "likeWeight", "replayJumpThresholdMs", "dailyCapPlays", "recordFloor",
                "playlistJoinRank", "playlistLeaveRank",
            ).forEach { key ->
                assertTrue(
                    "${setting.title} leaks the field name '$key' into the explanation",
                    !explanation.contains(key),
                )
            }
        }
    }

    /** Every tunable number is reachable; none was forgotten. */
    @Test
    fun everyScoringParameterHasAControl() {
        val defaults = ScoringParameters()
        val reachable = SCORING_SETTINGS.mapNotNull { it.increase(defaults) }
        val fields = listOf<(ScoringParameters) -> Any>(
            { it.halfLifeDays }, { it.lowerBreakpoint }, { it.upperBreakpoint },
            { it.negativeWeight }, { it.likeWeight }, { it.replayJumpThresholdMs },
            { it.dailyCapPlays }, { it.recordFloor }, { it.playlistJoinRank },
            { it.playlistLeaveRank },
        )
        fields.forEach { read ->
            assertTrue(
                "no control moves one of the ten parameters",
                reachable.any { read(it) != read(defaults) },
            )
        }
        assertEquals("ten parameters, ten controls", 10, SCORING_SETTINGS.size)
    }

    // --- The change log's wording --------------------------------------------

    @Test
    fun aChangeIsDescribedInTheSameWordsAsTheControls() {
        val before = ScoringParameters()
        val after = before.copy(negativeWeight = -0.25, halfLifeDays = 60.0)
        val described = describeDifferences(before, after)

        assertTrue(described.contains("What a skip costs"))
        assertTrue(described.contains("How fast listening fades"))
        assertTrue(described.contains("−0.5") || described.contains("-0.5"))
        assertTrue(described.contains("45 days"))
        assertTrue(described.contains("60 days"))
        // Only what changed.
        assertTrue(!described.contains("What liking a song is worth"))
    }

    @Test
    fun anUnreadablePreviousValueDescribesNothingRatherThanGuessing() {
        assertEquals("", describeDifferences(null, ScoringParameters()))
        assertEquals("", describeDifferences(ScoringParameters(), null))
    }

    @Test
    fun roundingKeepsThreeDecimals() {
        assertEquals(0.25, round3(0.25000000000000006), 0.0)
        assertEquals(0.2, round3(0.19999999999999998), 0.0)
    }
}
