package com.example.evergreen.spotify

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The relationship between **how often a write is attempted** and **how often
 * one is allowed**.
 *
 * This exists because the two were once the same number, and that was a bug
 * that hid for two phases. `EvergreenSyncWorker`'s period is anchored to when
 * the work was enqueued; `EvergreenSync.WRITE_INTERVAL_MS` is measured from
 * when the last write actually happened. Nothing keeps them in step, and a
 * manual write moves one without moving the other.
 *
 * Once they were out of phase, an attempt landing inside the guard returned
 * `TOO_SOON` and — correctly, because nothing was wrong — reported success.
 * WorkManager then waited a full further period. Measured on the NW-A307 on
 * 2026-09-19: a write at 03:25 on the 18th, an attempt at 01:08 on the 19th
 * that did nothing, and the next chance not until 01:25 on the 20th. Two
 * hours and seventeen minutes of bad luck cost a day.
 *
 * Both values are compile-time constants, so this test is a statement about
 * the source rather than about a running system — which is exactly what is
 * wanted. Nothing at runtime would ever have complained about the old pair;
 * the only place this could have been caught is here, at the point where
 * someone changes one of the two numbers.
 */
class EvergreenScheduleTest {

    private val attemptMs = TimeUnit.HOURS.toMillis(EvergreenSyncWorker.ATTEMPT_INTERVAL_HOURS)
    private val guardMs = EvergreenSync.WRITE_INTERVAL_MS

    /**
     * The invariant that was violated. Equal is not good enough: equal is
     * precisely the case that oscillates, because consecutive attempts then
     * sit within drift of the boundary and fall on whichever side the device's
     * sleep put them.
     */
    @Test
    fun `a write is attempted more often than one is allowed`() {
        assertTrue(
            "attempting every ${attemptMs / 3_600_000} h against a guard of " +
                "${guardMs / 3_600_000} h must leave room for the guard to expire " +
                "between attempts",
            attemptMs < guardMs,
        )
    }

    /**
     * And with enough margin that ordinary scheduling drift cannot close it.
     *
     * A quarter of the guard is the loosest this should get. At that point an
     * attempt still lands within six hours of the guard expiring however the
     * two are phased, and no plausible amount of Doze deferral turns a
     * six-hour margin into a missed day.
     */
    @Test
    fun `the margin is wide enough to survive scheduling drift`() {
        assertTrue(
            "the attempt interval should be at most a quarter of the write guard",
            attemptMs * 4 <= guardMs,
        )
    }

    /**
     * What the pair actually promises, stated as the number that matters:
     * once a write becomes due, how long can it be before one is tried?
     *
     * This is the user-visible consequence — "it writes every day" is true
     * only to within this figure — and it is worth having written down as a
     * number rather than inferred from two constants in different files.
     */
    @Test
    fun `a due write waits at most one attempt interval`() {
        val worstCaseDelayHours = attemptMs / 3_600_000

        assertTrue(
            "a write that has become due should never wait more than a few hours; " +
                "this pair makes it $worstCaseDelayHours",
            worstCaseDelayHours <= 8,
        )
    }
}
