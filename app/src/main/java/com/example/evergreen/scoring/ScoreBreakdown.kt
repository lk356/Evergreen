package com.example.evergreen.scoring

/** What kind of event a contribution came from. */
enum class ContributionKind {
    /** A listen — one or more segment rows folded into a single play. */
    LISTEN,

    /** A like, from the like archive. */
    LIKE,
}

/** Why a contribution is worth what it is worth, in one word. */
enum class ContributionNote {
    /** Scored normally. */
    SCORED,

    /** No duration is known, so "how much was heard" has no answer. */
    UNKNOWN_DURATION,

    /** Spotify DJ commentary, not a song. Contributes nothing. */
    COMMENTARY,

    /**
     * A rejection that was forgiven because it happened during a DJ session.
     * Skipping through songs you did not choose is browsing, not rejection.
     */
    DJ_SUPPRESSED,

    /** Reduced, possibly to nothing, by the per-track daily cap. */
    DAILY_CAP,

    /** A like. */
    LIKE,

    /**
     * An unlike. Contributes nothing: unliking is handled by membership, which
     * removes the track from the playlist, not by the score. The score and the
     * archive entry are kept so that re-liking restores the track at full
     * strength (decisions 4).
     */
    UNLIKE_IGNORED,
}

/**
 * One event's contribution to a track's score, with its working shown.
 *
 * The explain-the-score view renders this list directly, which is deliberate:
 * the scorer and the explanation read from the same values, so they cannot
 * drift apart and start telling different stories about the same track.
 */
data class ScoreContribution(
    val kind: ContributionKind,
    /** When it happened. Decay is measured from here. */
    val atMs: Long,
    /** `listenedMs / duration`, or null for a like or an unknown duration. */
    val fractionHeard: Double?,
    /** Milliseconds heard, or null for a like. */
    val listenedMs: Long?,
    /** Straight off the curve, before DJ suppression and the daily cap. */
    val rawPlayEquivalents: Double,
    /** After DJ suppression and the daily cap, before decay. */
    val playEquivalents: Double,
    /** `0.5 ^ (age / half-life)`. */
    val decayWeight: Double,
    /** What this event is actually worth today: [playEquivalents] × [decayWeight]. */
    val decayedValue: Double,
    val note: ContributionNote,
    /** The stored rows behind this contribution, so a number can be traced to a row. */
    val segmentIds: List<Long> = emptyList(),
)

/**
 * A track's score, and every event that produced it.
 *
 * Nothing here is stored as truth. A score is a pure function of (segments,
 * like events, parameters, now), recomputed rather than accumulated — see
 * [TrackScorer] for why that is an invariant rather than a preference.
 */
data class ScoreBreakdown(
    val trackId: Long,
    val score: Double,
    /** Oldest first. */
    val contributions: List<ScoreContribution>,
    val parameters: ScoringParameters,
    val nowMs: Long,
) {
    val listens: List<ScoreContribution>
        get() = contributions.filter { it.kind == ContributionKind.LISTEN }

    val likes: List<ScoreContribution>
        get() = contributions.filter { it.kind == ContributionKind.LIKE }

    /** Listens that actually moved the score, for the summary line. */
    val scoredListenCount: Int
        get() = listens.count { it.note == ContributionNote.SCORED || it.note == ContributionNote.DAILY_CAP }

    val totalListenedMs: Long
        get() = listens.sumOf { it.listenedMs ?: 0L }
}
