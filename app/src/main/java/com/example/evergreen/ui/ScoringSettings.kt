package com.example.evergreen.ui

import com.example.evergreen.scoring.ScoringParameters
import java.util.Locale

/**
 * One tunable number, in the words someone who did not write the scorer would
 * use for it.
 *
 * The JSON editor this replaces showed `negativeWeight: -0.5`, which is exactly
 * right and tells you nothing. What the user needs to decide is whether a skip
 * should count as minus half a play, and that is a sentence, not a key.
 *
 * Pure data, separate from the composable that draws it, so the arithmetic --
 * the step sizes, the bounds, the formatting -- is testable without a device.
 * Getting a bound wrong here is how a settings screen lets someone save a
 * half-life of zero and divide every score by it.
 */
internal data class ScoringSetting(
    val title: String,
    /** What the current value means, as a sentence. */
    val explain: (ScoringParameters) -> String,
    /** The value as the user sees it. */
    val display: (ScoringParameters) -> String,
    /** One step up, or null when already at the top. */
    val increase: (ScoringParameters) -> ScoringParameters?,
    /** One step down, or null when already at the bottom. */
    val decrease: (ScoringParameters) -> ScoringParameters?,
    /** Shown under the control when the value is not the calibrated default. */
    val defaultNote: String,
)

/**
 * Every setting the scoring depends on, in the order they matter.
 *
 * Decay first, because it is the one that shapes everything else; the playlist
 * cutoffs last, because they decide only what gets written rather than what
 * anything scores.
 *
 * Bounds are deliberately tighter than [ScoringParameters.problems] allows.
 * That check is the backstop against nonsense; these are the range within which
 * the answer is plausibly still music taste rather than a typo. Anything
 * outside them is still reachable through the JSON editor, which is what it is
 * kept for.
 */
internal val SCORING_SETTINGS: List<ScoringSetting> = listOf(
    ScoringSetting(
        title = "How fast listening fades",
        explain = { p ->
            "A play counts for half as much after ${trim(p.halfLifeDays)} days, " +
                "a quarter after ${trim(p.halfLifeDays * 2)}. Shorter means the " +
                "playlist follows what you are listening to this month; longer means " +
                "it remembers further back."
        },
        display = { p -> "${trim(p.halfLifeDays)} days" },
        increase = { p -> p.step(p.halfLifeDays, 5.0, max = 365.0) { v -> p.copy(halfLifeDays = v) } },
        decrease = { p -> p.step(p.halfLifeDays, -5.0, min = 5.0) { v -> p.copy(halfLifeDays = v) } },
        defaultNote = "Calibrated to 45 days over 77,369 real plays: for liked tracks the " +
            "odds of coming back within 90 days cross half at 45.",
    ),
    ScoringSetting(
        title = "What counts as rejecting a song",
        explain = { p ->
            "Hearing less than ${percent(p.lowerBreakpoint)} of a song counts against it, " +
                "rather than for it."
        },
        display = { p -> percent(p.lowerBreakpoint) },
        increase = { p ->
            p.step(p.lowerBreakpoint, 0.05, max = p.upperBreakpoint - 0.05) { v ->
                p.copy(lowerBreakpoint = v)
            }
        },
        decrease = { p -> p.step(p.lowerBreakpoint, -0.05, min = 0.0) { v -> p.copy(lowerBreakpoint = v) } },
        defaultNote = "Calibrated to 20%: 79% of plays you cut short were shorter than that.",
    ),
    ScoringSetting(
        title = "What counts as a full play",
        explain = { p ->
            "Hearing ${percent(p.upperBreakpoint)} or more of a song counts as a whole " +
                "play. Between here and ${percent(p.lowerBreakpoint)}, it counts for part " +
                "of one."
        },
        display = { p -> percent(p.upperBreakpoint) },
        increase = { p -> p.step(p.upperBreakpoint, 0.05, max = 1.0) { v -> p.copy(upperBreakpoint = v) } },
        decrease = { p ->
            p.step(p.upperBreakpoint, -0.05, min = p.lowerBreakpoint + 0.05) { v ->
                p.copy(upperBreakpoint = v)
            }
        },
        defaultNote = "Calibrated to 85%, but read more softly than the 20% below — 80% " +
            "is defensible, which is why this is a setting.",
    ),
    ScoringSetting(
        title = "What a skip costs",
        explain = { p ->
            "A skip counts as ${trim(p.negativeWeight)} of a play. At " +
                "${trim(p.negativeWeight)}, " +
                "${playsToUndo(p.negativeWeight)} to undo one."
        },
        display = { p -> trim(p.negativeWeight) },
        increase = { p -> p.step(p.negativeWeight, 0.05, max = 0.0) { v -> p.copy(negativeWeight = v) } },
        decrease = { p -> p.step(p.negativeWeight, -0.05, min = -2.0) { v -> p.copy(negativeWeight = v) } },
        defaultNote = "Chosen rather than calibrated, and the one under active question: " +
            "skips are not capped per day while plays are, so four skips in one evening " +
            "cost more than a day of listening can win back.",
    ),
    ScoringSetting(
        title = "What liking a song is worth",
        explain = { p ->
            "Liking a song starts it off as though you had played it " +
                "${trim(p.likeWeight)} times. That boost fades at the same rate as a " +
                "real play, so a liked song you never listen to sinks on its own."
        },
        display = { p -> "${trim(p.likeWeight)} plays" },
        increase = { p -> p.step(p.likeWeight, 0.5, max = 20.0) { v -> p.copy(likeWeight = v) } },
        decrease = { p -> p.step(p.likeWeight, -0.5, min = 0.0) { v -> p.copy(likeWeight = v) } },
        defaultNote = "This is what replaces a grace period for new likes: a fresh like " +
            "enters strong and fades if listening does not back it up.",
    ),
    ScoringSetting(
        title = "What counts as replaying",
        explain = { p ->
            "Jumping back at least ${trim(p.replayJumpThresholdMs / 1000.0)} seconds counts " +
                "as deliberately replaying a song. Smaller jumps are the player fidgeting."
        },
        display = { p -> "${trim(p.replayJumpThresholdMs / 1000.0)} s" },
        increase = { p ->
            p.stepMs(p.replayJumpThresholdMs, 1_000, max = 60_000) { v ->
                p.copy(replayJumpThresholdMs = v)
            }
        },
        decrease = { p ->
            p.stepMs(p.replayJumpThresholdMs, -1_000, min = 0) { v ->
                p.copy(replayJumpThresholdMs = v)
            }
        },
        defaultNote = "Every jump's size is stored, so changing this re-reads listening " +
            "already recorded rather than needing it captured again.",
    ),
    ScoringSetting(
        title = "Most one song can earn in a day",
        explain = { p ->
            "However many times a song is played in one day, it can add at most " +
                "${trim(p.dailyCapPlays)} plays to its score. This is what stops leaving " +
                "one track on repeat overnight scoring forty plays."
        },
        display = { p -> "${trim(p.dailyCapPlays)} plays" },
        increase = { p -> p.step(p.dailyCapPlays, 0.5, max = 10.0) { v -> p.copy(dailyCapPlays = v) } },
        decrease = { p -> p.step(p.dailyCapPlays, -0.5, min = 0.5) { v -> p.copy(dailyCapPlays = v) } },
        defaultNote = "Skips are deliberately not capped the same way. Whether that " +
            "asymmetry is right is an open question.",
    ),
    ScoringSetting(
        title = "When a song stops counting as alive",
        explain = { p ->
            "Below a score of ${trim(p.recordFloor)}, a liked song is no longer counted " +
                "as still alive in the app's own record. It changes nothing about the " +
                "playlist and deletes nothing."
        },
        display = { p -> trim(p.recordFloor) },
        increase = { p -> p.step(p.recordFloor, 0.05, max = 5.0) { v -> p.copy(recordFloor = v) } },
        decrease = { p -> p.step(p.recordFloor, -0.05, min = 0.0) { v -> p.copy(recordFloor = v) } },
        defaultNote = "Affects the record only. Nothing is ever deleted at this line.",
    ),
    ScoringSetting(
        title = "How big the playlist is",
        explain = { p ->
            "A song joins Evergreen when it reaches rank ${p.playlistJoinRank} or better."
        },
        display = { p -> "top ${p.playlistJoinRank}" },
        increase = { p ->
            p.stepInt(p.playlistJoinRank, 5, max = 500) { v ->
                // Push the leave rank along rather than letting it be overtaken,
                // which would be rejected on save with a message about a
                // relationship the user never sees on screen.
                p.copy(playlistJoinRank = v, playlistLeaveRank = maxOf(p.playlistLeaveRank, v))
            }
        },
        decrease = { p -> p.stepInt(p.playlistJoinRank, -5, min = 5) { v -> p.copy(playlistJoinRank = v) } },
        defaultNote = "One hundred songs.",
    ),
    ScoringSetting(
        title = "How far a song falls before it leaves",
        explain = { p ->
            "Once in, a song stays until it falls below rank ${p.playlistLeaveRank} — " +
                "${p.playlistLeaveRank - p.playlistJoinRank} places of slack. Without that " +
                "gap, songs sitting either side of the cutoff would be added and removed " +
                "on alternate days as their scores drifted by thousandths."
        },
        display = { p -> "rank ${p.playlistLeaveRank}" },
        increase = { p -> p.stepInt(p.playlistLeaveRank, 5, max = 600) { v -> p.copy(playlistLeaveRank = v) } },
        decrease = { p ->
            p.stepInt(p.playlistLeaveRank, -5, min = p.playlistJoinRank) { v ->
                p.copy(playlistLeaveRank = v)
            }
        },
        defaultNote = "Thirty places of slack. Each avoided flip is a write against a " +
            "quota Spotify does not publish, and a notification.",
    ),
)

// --- Stepping ----------------------------------------------------------------

/**
 * One step, or null at the bound.
 *
 * Returning null rather than clamping is what lets the button disable itself:
 * a "+" that is enabled but does nothing is worse than one that is greyed out,
 * because the user cannot tell it from a frozen screen.
 *
 * Rounded to three decimals because repeatedly adding 0.05 to a Double drifts —
 * 0.2 + 0.05 is 0.25000000000000006 — and the parameters' JSON is canonical, so
 * that drift would change the hash and rebuild the score cache for a value the
 * user believes they set back to what it was.
 */
private inline fun ScoringParameters.step(
    current: Double,
    by: Double,
    min: Double = Double.NEGATIVE_INFINITY,
    max: Double = Double.POSITIVE_INFINITY,
    build: (Double) -> ScoringParameters,
): ScoringParameters? {
    val next = round3(current + by)
    if (next < round3(min) || next > round3(max)) return null
    return build(next)
}

private inline fun ScoringParameters.stepInt(
    current: Int,
    by: Int,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    build: (Int) -> ScoringParameters,
): ScoringParameters? {
    val next = current + by
    if (next < min || next > max) return null
    return build(next)
}

private inline fun ScoringParameters.stepMs(
    current: Long,
    by: Long,
    min: Long = Long.MIN_VALUE,
    max: Long = Long.MAX_VALUE,
    build: (Long) -> ScoringParameters,
): ScoringParameters? {
    val next = current + by
    if (next < min || next > max) return null
    return build(next)
}

internal fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0

private fun trim(value: Double): String =
    String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

private fun percent(fraction: Double): String = "${Math.round(fraction * 100)}%"

/** "one play is enough" / "two plays are needed" — the sentence, not the ratio. */
private fun playsToUndo(negativeWeight: Double): String {
    if (negativeWeight == 0.0) return "nothing is taken off at all"
    val plays = -negativeWeight
    return when {
        plays <= 1.0 -> "one full play is more than enough"
        else -> "it takes ${trim(plays)} full plays"
    }
}
