package com.example.evergreen.scoring

import com.example.evergreen.data.MembershipReason

/** A track that should join the playlist, or leave it. */
data class MembershipChange(
    val trackId: Long,
    val reason: MembershipReason,
    /** The rank that triggered the change, or null when a track left by being unliked. */
    val rank: Int?,
)

/** What one pass of [Membership.decide] concluded. */
data class MembershipDecision(
    /** Every track that should be in the playlist afterwards, best rank first. */
    val members: List<Long>,
    val joining: List<MembershipChange>,
    val leaving: List<MembershipChange>,
)

/**
 * Decides which tracks belong in the Evergreen playlist.
 *
 * **Two cutoffs, one score** (decisions 4). A track joins at rank 100 or better
 * and leaves only once it falls below rank 130. The gap is hysteresis, and it is
 * there because neither kind of error was judged worse than the other: with a
 * single cutoff, the tracks sitting either side of rank 100 would be added and
 * removed on alternate days as their scores drifted by thousandths. Each of
 * those flips is a write against a quota Spotify does not publish, and a
 * notification, and a playlist that never settles. Thirty places of slack costs
 * a slightly stale playlist and buys a stable one.
 *
 * Pure: it is handed the ranking and the current members and returns the
 * changes. Nothing here writes to a database or to Spotify.
 */
object Membership {

    /**
     * @param currentMembers tracks the app currently believes are in the
     *   playlist, from `evergreen_membership`.
     * @param ranked every liked track, best first, from [Ranker].
     * @param likedTrackIds tracks that are liked *now*. A member missing from
     *   this set has been unliked and leaves regardless of rank.
     */
    fun decide(
        currentMembers: Set<Long>,
        ranked: List<RankedTrack>,
        likedTrackIds: Set<Long>,
        parameters: ScoringParameters,
    ): MembershipDecision {
        val rankOf = ranked.associate { it.trackId to it.rank }

        val joining = mutableListOf<MembershipChange>()
        val leaving = mutableListOf<MembershipChange>()
        val members = mutableSetOf<Long>()

        // Unliking removes a track immediately, whatever it scores. The score
        // and the archive entry are kept, so re-liking restores it at full
        // strength -- the cost of an accidental unlike is one playlist write,
        // never lost history.
        currentMembers.forEach { trackId ->
            if (trackId !in likedTrackIds) {
                leaving += MembershipChange(trackId, MembershipReason.LEFT_UNLIKED, rankOf[trackId])
            }
        }
        val survivingMembers = currentMembers.filter { it in likedTrackIds }.toSet()

        ranked.forEach { track ->
            if (track.trackId !in likedTrackIds) return@forEach

            val wasMember = track.trackId in survivingMembers
            when {
                // Already in, and not yet fallen far enough to be dropped.
                wasMember && track.rank <= parameters.playlistLeaveRank -> {
                    members += track.trackId
                }
                wasMember -> {
                    leaving += MembershipChange(track.trackId, MembershipReason.LEFT_BY_RANK, track.rank)
                }
                // Not in, and good enough to join.
                track.rank <= parameters.playlistJoinRank -> {
                    members += track.trackId
                    joining += MembershipChange(
                        track.trackId,
                        // A track that was a member before and is liked again
                        // rejoins on its current rank; the distinction is only
                        // for the record, so "why is this back" is answerable.
                        if (track.trackId in currentMembers) {
                            MembershipReason.REJOINED_BY_LIKE
                        } else {
                            MembershipReason.JOINED_BY_RANK
                        },
                        track.rank,
                    )
                }
            }
        }

        return MembershipDecision(
            // Ordered by rank so the playlist reads best-first, and so two runs
            // over the same ranking produce the same order.
            members = ranked.filter { it.trackId in members }.map { it.trackId },
            joining = joining,
            leaving = leaving,
        )
    }

    /**
     * The tracks still considered alive: every liked track scoring above the
     * record floor.
     *
     * Kept in the app and searchable, never written to Spotify — the "fuller
     * record behind it" half of the brief. Not stored as a table of its own,
     * because it is a query over the score cache.
     */
    fun record(ranked: List<RankedTrack>, parameters: ScoringParameters): List<RankedTrack> =
        ranked.filter { it.score > parameters.recordFloor }
}
