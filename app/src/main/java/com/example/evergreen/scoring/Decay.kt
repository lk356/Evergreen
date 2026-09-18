package com.example.evergreen.scoring

import kotlin.math.pow

/**
 * How much of a past event still counts today.
 *
 * Exponential decay on a half-life: an event keeps half its weight after one
 * half-life, a quarter after two, and so on. This is what makes the playlist
 * "living" — a song that stops being played fades out on its own rather than
 * needing anything to remove it.
 *
 * **The half-life is measured, not guessed.** Over 77,369 rows of real
 * listening history, the probability that a liked track comes back within 90
 * days crosses 50% at 45 days (`docs/PHASE3_DECISIONS.md` 3.1). So a score
 * halves at roughly the point where the odds of the song returning have halved.
 *
 * **A known imperfection.** The real return curve is not exponential: it stays
 * above 0.85 for a fortnight and then falls almost linearly. Exponential decay
 * at a 45-day half-life is therefore slightly harsh in the first two weeks and
 * slightly generous after three months. That is a parameter to revisit, not a
 * migration, which is the point of keeping the half-life a setting.
 */
object Decay {

    /**
     * The weight of an event that happened at [eventAtMs], as of [nowMs].
     *
     * Returns 1.0 for an event happening now and approaches 0 for old ones. An
     * event dated in the future — a device with a wrong clock, or an import
     * carrying a bad timestamp — is clamped to 1.0 rather than allowed to score
     * *more* than a play happening right now, which is the only direction in
     * which a bad clock could inflate a score.
     */
    fun weight(eventAtMs: Long, nowMs: Long, parameters: ScoringParameters): Double =
        weightForAge(ageMs = nowMs - eventAtMs, halfLifeMs = parameters.halfLifeMs)

    /** [weight], for an age already worked out. Negative ages clamp to 1.0. */
    fun weightForAge(ageMs: Long, halfLifeMs: Double): Double {
        if (ageMs <= 0L) return 1.0
        return 0.5.pow(ageMs.toDouble() / halfLifeMs)
    }
}
