package com.example.evergreen.scoring

import com.example.evergreen.data.MembershipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 6 of `docs/PHASE3_PLAN.md` section 9: joins at rank 100, not 101; a
 * member at rank 120 stays; at 131 leaves; unliked leaves regardless of rank;
 * re-liked at rank 50 rejoins; reasons recorded.
 */
class MembershipTest {

    private val parameters = ScoringParameters()

    /** 200 tracks, best score first, so rank N has trackId N. */
    private val ranked = (1..200).map { rank ->
        RankedTrack(trackId = rank.toLong(), score = 100.0 - rank * 0.1, rank = rank)
    }

    private val allLiked = ranked.map { it.trackId }.toSet()

    private fun decide(
        currentMembers: Set<Long> = emptySet(),
        liked: Set<Long> = allLiked,
        tracks: List<RankedTrack> = ranked,
    ) = Membership.decide(currentMembers, tracks, liked, parameters)

    // --- Joining --------------------------------------------------------------

    @Test
    fun `a track joins at rank 100 but not at 101`() {
        val decision = decide()

        assertTrue(100L in decision.members)
        assertTrue(101L !in decision.members)
        assertEquals(100, decision.members.size)
        assertEquals(100, decision.joining.size)
    }

    @Test
    fun `the members come back best rank first`() {
        val decision = decide()

        assertEquals((1L..100L).toList(), decision.members)
    }

    @Test
    fun `joining is recorded with a reason and a rank`() {
        val decision = decide()

        val top = decision.joining.single { it.trackId == 1L }
        assertEquals(MembershipReason.JOINED_BY_RANK, top.reason)
        assertEquals(1, top.rank)
    }

    // --- Hysteresis -----------------------------------------------------------

    /**
     * The whole point of two cutoffs. With one, the tracks either side of rank
     * 100 would be added and removed on alternate days as their scores drifted
     * by thousandths — a write against an unpublished quota each time.
     */
    @Test
    fun `a member that slips to rank 120 stays`() {
        val decision = decide(currentMembers = setOf(5L), tracks = demote(5L, to = 120))

        assertTrue(5L in decision.members)
        assertTrue(decision.leaving.isEmpty())
    }

    @Test
    fun `a member at exactly rank 130 stays`() {
        val decision = decide(currentMembers = setOf(5L), tracks = demote(5L, to = 130))

        assertTrue(5L in decision.members)
    }

    @Test
    fun `a member that falls to rank 131 leaves`() {
        val decision = decide(currentMembers = setOf(5L), tracks = demote(5L, to = 131))

        assertTrue(5L !in decision.members)
        val left = decision.leaving.single { it.trackId == 5L }
        assertEquals(MembershipReason.LEFT_BY_RANK, left.reason)
        assertEquals(131, left.rank)
    }

    /**
     * The asymmetry that makes hysteresis work: a track between the two cutoffs
     * stays if it is already in, and does not get in if it is not.
     */
    @Test
    fun `a non-member at rank 120 does not join`() {
        val decision = decide(currentMembers = emptySet())

        assertTrue(120L !in decision.members)
        assertTrue(decision.joining.none { it.trackId == 120L })
    }

    /** Running twice over an unchanged ranking must change nothing. */
    @Test
    fun `a settled playlist produces no changes`() {
        val first = decide()
        val second = decide(currentMembers = first.members.toSet())

        assertEquals(first.members, second.members)
        assertTrue(second.joining.isEmpty())
        assertTrue(second.leaving.isEmpty())
    }

    // --- Unliking -------------------------------------------------------------

    @Test
    fun `an unliked track leaves regardless of rank`() {
        val decision = decide(
            currentMembers = setOf(1L, 2L, 3L),
            liked = allLiked - 1L,
        )

        assertTrue("the top-ranked track still leaves if unliked", 1L !in decision.members)
        val left = decision.leaving.single { it.trackId == 1L }
        assertEquals(MembershipReason.LEFT_UNLIKED, left.reason)
    }

    @Test
    fun `an unliked track cannot join even at rank 1`() {
        val decision = decide(liked = allLiked - 1L)

        assertTrue(1L !in decision.members)
        assertTrue(decision.joining.none { it.trackId == 1L })
    }

    /**
     * Re-liking restores the track at its current rank. Its score and archive
     * entry were never touched, so the cost of an accidental unlike is one
     * playlist write rather than lost history.
     */
    @Test
    fun `a re-liked track rejoins at rank 50`() {
        val decision = decide(
            // Still recorded as a member from before it was unliked.
            currentMembers = setOf(50L),
            liked = allLiked,
            tracks = demote(50L, to = 50),
        )

        assertTrue(50L in decision.members)
    }

    @Test
    fun `a track that left and is liked again rejoins with its own reason`() {
        // Not currently a member, but the ranking has it inside the join cutoff.
        val decision = decide(currentMembers = emptySet())

        assertEquals(MembershipReason.JOINED_BY_RANK, decision.joining.first().reason)
    }

    // --- The record -----------------------------------------------------------

    @Test
    fun `the record is every liked track above the floor`() {
        val tracks = listOf(
            RankedTrack(1, 5.0, 1),
            RankedTrack(2, 0.5, 2),
            RankedTrack(3, 0.1, 3),
            RankedTrack(4, 0.05, 4),
            RankedTrack(5, -1.0, 5),
        )

        val record = Membership.record(tracks, parameters)

        assertEquals(listOf(1L, 2L), record.map { it.trackId })
    }

    @Test
    fun `the record floor is a setting`() {
        val tracks = listOf(RankedTrack(1, 0.5, 1), RankedTrack(2, 0.05, 2))

        assertEquals(1, Membership.record(tracks, parameters).size)
        assertEquals(2, Membership.record(tracks, parameters.copy(recordFloor = 0.01)).size)
    }

    // --- Cutoffs are settings -------------------------------------------------

    @Test
    fun `changing the cutoffs changes the playlist size`() {
        val smaller = Membership.decide(
            currentMembers = emptySet(),
            ranked = ranked,
            likedTrackIds = allLiked,
            parameters = parameters.copy(playlistJoinRank = 20, playlistLeaveRank = 30),
        )

        assertEquals(20, smaller.members.size)
    }

    @Test
    fun `an empty ranking empties the playlist`() {
        val decision = Membership.decide(
            currentMembers = setOf(1L, 2L),
            ranked = emptyList(),
            likedTrackIds = emptySet(),
            parameters = parameters,
        )

        assertTrue(decision.members.isEmpty())
        assertEquals(2, decision.leaving.size)
        assertTrue(decision.leaving.all { it.reason == MembershipReason.LEFT_UNLIKED })
    }

    /** Moves one track to a given rank, keeping the list a valid dense ranking. */
    private fun demote(trackId: Long, to: Int): List<RankedTrack> {
        val without = ranked.filter { it.trackId != trackId }.toMutableList()
        without.add(to - 1, ranked.single { it.trackId == trackId })
        return without.mapIndexed { index, track -> track.copy(rank = index + 1) }
    }
}
