package com.example.evergreen.data

import java.time.Instant
import java.time.zone.ZoneRules

/**
 * Builds the one statement that backfills `utcOffsetMinutes` on existing rows.
 *
 * **Why a backfill at all.** Schema v8 adds the column as NULL, and NULL is
 * handled correctly at read time — scoring falls back to the reading device's
 * current zone, which is exactly what it did before the column existed. So the
 * backfill is not needed for correctness *today*. It is needed for correctness
 * *later*: the fallback is a guess that gets worse the moment the user travels,
 * and the information to replace it with is only available while the device is
 * still in the zone the rows were recorded in. Filling it in now costs one
 * statement; leaving it means the answer is gone.
 *
 * **Why the device's current zone rules, evaluated at each row's own
 * timestamp.** The device cannot know where it was in 2021, so the honest
 * choice is between "assume the zone has not changed" and "leave it null".
 * Assuming is better, and it is better in a way that is easy to miss: a
 * `ZoneRules` object knows the *history* of its zone, so asking it for the
 * offset at a 2021 instant gives 2021's daylight-saving state rather than
 * today's. Daylight saving therefore comes out right for free, over six years
 * of history, without a single stored transition. What it cannot recover is a
 * genuine move between zones, and the user has accepted that.
 *
 * ### Why one statement with a CASE, and not one UPDATE per period
 *
 * The obvious shape is a loop: for each daylight-saving period, one
 * `UPDATE ... WHERE endedAtMs BETWEEN ? AND ?`. Six years of a DST zone is
 * about a dozen of those, which sounds cheap. It is not: there is no index on
 * `endedAtMs`, so each one is a **full scan of 77,518 rows** on the slowest
 * device in the project, and a dozen scans is a dozen times the work for a
 * migration the user is waiting through.
 *
 * A single CASE is one scan, whatever the number of periods. SQLite
 * short-circuits the branches, so the average row tests about half of them —
 * integer comparisons against literals, with no allocation and no function
 * call. That is the difference between one pass and twelve.
 *
 * ### Idempotence
 *
 * `WHERE utcOffsetMinutes IS NULL` means running it twice changes nothing the
 * second time, and — more usefully — that it can never overwrite a real
 * captured offset with a guessed one. A row that arrived from another device
 * carrying its own offset is left exactly as it came.
 */
internal object OffsetBackfill {

    /** Never build a statement larger than this many branches. */
    private const val MAX_TRANSITIONS = 400

    /**
     * @param rules the zone rules to read history from, normally
     *   `ZoneId.systemDefault().rules`.
     * @param fromMs the earliest row's `endedAtMs`, so no time is spent
     *   enumerating transitions before any data exists.
     * @param toMs the latest row's `endedAtMs`.
     * @return one SQL statement, ready to execute.
     */
    fun statement(rules: ZoneRules, fromMs: Long, toMs: Long): String {
        val branches = StringBuilder()
        var offsetMinutes = minutesOf(rules, Instant.ofEpochMilli(fromMs))
        var cursor = Instant.ofEpochMilli(fromMs)
        val end = Instant.ofEpochMilli(toMs)

        var seen = 0
        while (seen < MAX_TRANSITIONS) {
            // Strictly after `cursor`, so a row landing exactly on a transition
            // cannot loop.
            val transition = rules.nextTransition(cursor) ?: break
            if (transition.instant.isAfter(end)) break

            branches
                .append("WHEN `endedAtMs` < ")
                .append(transition.instant.toEpochMilli())
                .append(" THEN ")
                .append(offsetMinutes)
                .append(' ')

            offsetMinutes = transition.offsetAfter.totalSeconds / SECONDS_PER_MINUTE
            cursor = transition.instant
            seen++
        }

        // A zone with no transitions in range -- UTC, or a span inside one
        // period -- needs no CASE at all, and `CASE ELSE x END` is not valid
        // SQLite anyway.
        val value = if (branches.isEmpty()) {
            offsetMinutes.toString()
        } else {
            "CASE $branches ELSE $offsetMinutes END"
        }

        return "UPDATE `listening_segments` SET `utcOffsetMinutes` = $value " +
            "WHERE `utcOffsetMinutes` IS NULL"
    }

    private fun minutesOf(rules: ZoneRules, at: Instant): Int =
        rules.getOffset(at).totalSeconds / SECONDS_PER_MINUTE

    private const val SECONDS_PER_MINUTE = 60
}
