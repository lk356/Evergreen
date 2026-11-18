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
     * Recorded from a media session that was controlling **another device's**
     * volume, so this device was almost certainly not the one playing.
     * Contributes nothing.
     *
     * With Spotify Connect, a device showing a session for music playing
     * somewhere else records a faithful duplicate of a listen it is not
     * producing — a **phantom**. Measured in both directions on 2026-09-21:
     * five tracks played on one device produced five segments on the other,
     * claiming 403.3 s against the real 403.5 s, with every fraction agreeing
     * to 0.01. Merging two devices' captures without this exclusion would
     * double-count every such listen, and would also inherit spurious
     * zero-length rows that score as *rejections* of songs played in full.
     *
     * **Excluded here rather than dropped at capture**, so that a listen cast
     * from a phone to a speaker, a TV, a car or a desktop is still recorded
     * and the decision stays reversible. See
     * [com.example.evergreen.data.PlaybackType].
     */
    REMOTE_SESSION,

    /**
     * A rejection that was forgiven because it happened during a DJ session.
     * Skipping through songs you did not choose is browsing, not rejection.
     */
    DJ_SUPPRESSED,

    /** Reduced, possibly to nothing, by the per-track daily cap. */
    DAILY_CAP,

    /**
     * A skip inside a browsing run: several very short listens in a row
     * ([BrowsingRuns]). Scaled by `rapidRunSkipWeight` rather than counted
     * as a full rejection. Scoring freshness, change B.
     */
    BROWSING,

    /**
     * A skip inside a browsing run, of a song already heard on the same
     * device within the previous two hours: passing back over it while
     * looking for something else. Contributes nothing.
     */
    BROWSING_REVISIT,

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
    /**
     * The UTC offset in force where and when this happened, or null if the row
     * did not record one.
     *
     * Only the daily cap reads it, and only to decide which local day a listen
     * falls in. Null means the reading device's own zone is used instead, which
     * is what every row written before schema v8 gets and is exactly what the
     * scorer did before the column existed.
     */
    val utcOffsetMinutes: Int? = null,
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
