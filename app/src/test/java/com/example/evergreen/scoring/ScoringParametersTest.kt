package com.example.evergreen.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 9 of `docs/PHASE3_PLAN.md` section 9: JSON round trip; defaults equal
 * section 3 of the decisions; unknown keys ignored; a changed value changes the
 * hash.
 */
class ScoringParametersTest {

    // --- Defaults ------------------------------------------------------------

    /**
     * The calibrated values from `docs/PHASE3_DECISIONS.md` section 3.
     *
     * Spelled out as literals on purpose. If someone retunes a default, this
     * test fails and they have to come here and say so, rather than six years
     * of calibration quietly changing under a one-character edit.
     */
    @Test
    fun `defaults are the calibrated values`() {
        val defaults = ScoringParameters()

        assertEquals(45.0, defaults.halfLifeDays, 0.0)
        assertEquals(0.20, defaults.lowerBreakpoint, 0.0)
        assertEquals(0.85, defaults.upperBreakpoint, 0.0)
        assertEquals(-0.5, defaults.negativeWeight, 0.0)
        assertEquals(3.0, defaults.likeWeight, 0.0)
        assertEquals(10_000L, defaults.replayJumpThresholdMs)
        assertEquals(2.0, defaults.dailyCapPlays, 0.0)
        assertEquals(0.1, defaults.recordFloor, 0.0)
        assertEquals(100, defaults.playlistJoinRank)
        assertEquals(130, defaults.playlistLeaveRank)
        assertEquals(emptyList<String>(), defaults.problems())
    }

    @Test
    fun `half life converts to milliseconds and a ten half-life load window`() {
        val defaults = ScoringParameters()

        assertEquals(45.0 * 24 * 60 * 60 * 1000, defaults.halfLifeMs, 0.0)
        assertEquals(450L * 24 * 60 * 60 * 1000, defaults.loadWindowMs)
    }

    // --- Round trip ----------------------------------------------------------

    @Test
    fun `defaults survive a JSON round trip`() {
        val defaults = ScoringParameters()

        assertEquals(defaults, ScoringParameters.fromJson(defaults.toJson()))
    }

    @Test
    fun `non-default values survive a JSON round trip`() {
        val tuned = ScoringParameters(
            halfLifeDays = 90.0,
            lowerBreakpoint = 0.15,
            upperBreakpoint = 0.80,
            negativeWeight = -0.25,
            likeWeight = 5.0,
            replayJumpThresholdMs = 3_000L,
            dailyCapPlays = 1.5,
            recordFloor = 0.05,
            playlistJoinRank = 50,
            playlistLeaveRank = 75,
        )

        assertEquals(tuned, ScoringParameters.fromJson(tuned.toJson()))
    }

    /** [ScoringParameters.hash] is taken over this text, so it must be stable. */
    @Test
    fun `JSON is canonical`() {
        val json = ScoringParameters().toJson()

        assertEquals(json, ScoringParameters().toJson())
        assertTrue(json, json.startsWith("""{"halfLifeDays":45.0,"""))
        assertTrue(json, json.endsWith(""","playlistLeaveRank":130}"""))
        assertTrue("no whitespace: $json", json.none { it.isWhitespace() })
    }

    // --- Tolerance -----------------------------------------------------------

    @Test
    fun `unknown keys are ignored`() {
        val json = """{"halfLifeDays":30.0,"somethingFromTheFuture":7,"alsoUnknown":"text"}"""

        val parsed = ScoringParameters.fromJson(json)

        assertEquals(30.0, parsed.halfLifeDays, 0.0)
        assertEquals(ScoringParameters().upperBreakpoint, parsed.upperBreakpoint, 0.0)
    }

    /**
     * An unknown key holding an object or an array is skipped, not rejected.
     *
     * Matters for snapshots: one written by a later build may carry structure
     * this build has never heard of, and it should still be readable.
     */
    @Test
    fun `unknown structured keys are skipped`() {
        val json = """{"nested":{"a":[1,2,{"b":"}"}]},"halfLifeDays":12.0,"list":[],"empty":{}}"""

        assertEquals(12.0, ScoringParameters.fromJson(json).halfLifeDays, 0.0)
    }

    /** Missing keys take today's default — see the note on [ScoringParameters.fromJson]. */
    @Test
    fun `missing keys take their default`() {
        val parsed = ScoringParameters.fromJson("""{"halfLifeDays":20.0}""")

        assertEquals(ScoringParameters().copy(halfLifeDays = 20.0), parsed)
    }

    @Test
    fun `an empty object is all defaults`() {
        assertEquals(ScoringParameters(), ScoringParameters.fromJson("{}"))
        assertEquals(ScoringParameters(), ScoringParameters.fromJson("  {  }  "))
    }

    @Test
    fun `whitespace between tokens is allowed`() {
        val json = """ { "halfLifeDays" : 60.0 , "recordFloor" : 0.2 } """

        val parsed = ScoringParameters.fromJson(json)

        assertEquals(60.0, parsed.halfLifeDays, 0.0)
        assertEquals(0.2, parsed.recordFloor, 0.0)
    }

    // --- Rejection -----------------------------------------------------------

    @Test
    fun `a known key holding a non-number is rejected`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ScoringParameters.fromJson("""{"halfLifeDays":"forty-five"}""")
        }

        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("halfLifeDays"))
    }

    @Test
    fun `malformed JSON is rejected`() {
        listOf(
            "",
            "not json",
            "{",
            """{"halfLifeDays":}""",
            """{"halfLifeDays":45.0""",
            """{"halfLifeDays":45.0}trailing""",
            """["halfLifeDays"]""",
        ).forEach { json ->
            assertThrows("should have rejected: $json", IllegalArgumentException::class.java) {
                ScoringParameters.fromJson(json)
            }
        }
    }

    /**
     * Nonsense settings are refused at the point they are entered.
     *
     * A zero half-life divides by zero and a NaN turns every score into NaN,
     * which would look like a bug in the scorer rather than a typo in a text
     * box. The debug screen lets this JSON be edited by hand, so it is worth
     * catching there.
     */
    @Test
    fun `unusable values are rejected with a reason naming the key`() {
        val cases = mapOf(
            """{"halfLifeDays":0}""" to "halfLifeDays",
            """{"halfLifeDays":-1}""" to "halfLifeDays",
            """{"lowerBreakpoint":1.5}""" to "lowerBreakpoint",
            """{"upperBreakpoint":-0.1}""" to "upperBreakpoint",
            """{"lowerBreakpoint":0.9,"upperBreakpoint":0.5}""" to "lowerBreakpoint",
            """{"negativeWeight":0.5}""" to "negativeWeight",
            """{"likeWeight":-1}""" to "likeWeight",
            """{"replayJumpThresholdMs":-1}""" to "replayJumpThresholdMs",
            """{"dailyCapPlays":0}""" to "dailyCapPlays",
            """{"recordFloor":-0.1}""" to "recordFloor",
            """{"playlistJoinRank":0}""" to "playlistJoinRank",
            """{"playlistJoinRank":100,"playlistLeaveRank":80}""" to "playlistLeaveRank",
        )

        cases.forEach { (json, expectedKey) ->
            val failure = assertThrows(json, IllegalArgumentException::class.java) {
                ScoringParameters.fromJson(json)
            }
            assertTrue(
                "$json -> ${failure.message}",
                failure.message.orEmpty().contains(expectedKey),
            )
        }
    }

    /** The startup path must never be stopped by a bad stored string. */
    @Test
    fun `fromJsonOrDefault falls back instead of throwing`() {
        assertEquals(ScoringParameters(), ScoringParameters.fromJsonOrDefault(null))
        assertEquals(ScoringParameters(), ScoringParameters.fromJsonOrDefault("nonsense"))
        assertEquals(ScoringParameters(), ScoringParameters.fromJsonOrDefault("""{"halfLifeDays":0}"""))
        assertEquals(
            ScoringParameters(halfLifeDays = 10.0),
            ScoringParameters.fromJsonOrDefault("""{"halfLifeDays":10.0}"""),
        )
    }

    // --- Hash ----------------------------------------------------------------

    /**
     * The score cache stores this hash on every row and rebuilds when it stops
     * matching, so "the parameters changed" and "the hash changed" have to be
     * the same statement.
     */
    @Test
    fun `a changed value changes the hash`() {
        val defaults = ScoringParameters()

        val changed = listOf(
            defaults.copy(halfLifeDays = 45.1),
            defaults.copy(lowerBreakpoint = 0.21),
            defaults.copy(upperBreakpoint = 0.80),
            defaults.copy(negativeWeight = -0.51),
            defaults.copy(likeWeight = 3.1),
            defaults.copy(replayJumpThresholdMs = 10_001L),
            defaults.copy(dailyCapPlays = 2.1),
            defaults.copy(recordFloor = 0.11),
            defaults.copy(playlistJoinRank = 101),
            defaults.copy(playlistLeaveRank = 131),
        )

        changed.forEach { parameters ->
            assertNotEquals(parameters.toString(), defaults.hash(), parameters.hash())
        }
        assertEquals(changed.size, changed.map { it.hash() }.toSet().size)
    }

    @Test
    fun `equal parameters hash equally, across instances and round trips`() {
        val tuned = ScoringParameters(halfLifeDays = 60.0, upperBreakpoint = 0.8)

        assertEquals(tuned.hash(), ScoringParameters(halfLifeDays = 60.0, upperBreakpoint = 0.8).hash())
        assertEquals(tuned.hash(), ScoringParameters.fromJson(tuned.toJson()).hash())
    }

    /** Stable across processes, which `hashCode()` would not be. */
    @Test
    fun `the default hash is a fixed 64-character hex string`() {
        val hash = ScoringParameters().hash()

        assertEquals(64, hash.length)
        assertTrue(hash, hash.all { it in "0123456789abcdef" })
        assertEquals(hash, ScoringParameters().hash())
    }
}
