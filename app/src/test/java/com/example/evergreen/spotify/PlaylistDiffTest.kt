package com.example.evergreen.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 7 of `docs/PHASE3_PLAN.md` section 9: adds, removes, no-op when equal,
 * order-insensitive, batches of 100, a manually added URI is scheduled for
 * removal.
 */
class PlaylistDiffTest {

    private fun uris(vararg ids: String) = ids.map { "spotify:track:$it" }

    @Test
    fun `an empty playlist takes everything`() {
        val change = PlaylistDiff.compute(current = emptyList(), desired = uris("a", "b", "c"))

        assertEquals(uris("a", "b", "c"), change.toAdd)
        assertTrue(change.toRemove.isEmpty())
    }

    @Test
    fun `an unchanged playlist is a no-op`() {
        val change = PlaylistDiff.compute(current = uris("a", "b"), desired = uris("a", "b"))

        assertTrue(change.isEmpty)
        assertEquals(0, change.size)
    }

    /**
     * Spotify returns the playlist in its own order and Evergreen is meant to
     * be shuffled, so a reorder must not be mistaken for a change. Treating it
     * as one would be a hundred writes a day to achieve nothing.
     */
    @Test
    fun `a reordered playlist is still a no-op`() {
        val change = PlaylistDiff.compute(
            current = uris("c", "a", "b"),
            desired = uris("a", "b", "c"),
        )

        assertTrue(change.toString(), change.isEmpty)
    }

    @Test
    fun `tracks that fell out are removed`() {
        val change = PlaylistDiff.compute(current = uris("a", "b", "c"), desired = uris("a"))

        assertTrue(change.toAdd.isEmpty())
        assertEquals(uris("b", "c"), change.toRemove)
    }

    @Test
    fun `adds and removes in one pass`() {
        val change = PlaylistDiff.compute(current = uris("a", "b"), desired = uris("b", "c"))

        assertEquals(uris("c"), change.toAdd)
        assertEquals(uris("a"), change.toRemove)
        assertEquals(2, change.size)
        assertFalse(change.isEmpty)
    }

    /** Adds keep the desired order, so a first write reads best-first. */
    @Test
    fun `additions keep the ranked order`() {
        val change = PlaylistDiff.compute(current = emptyList(), desired = uris("c", "a", "b"))

        assertEquals(uris("c", "a", "b"), change.toAdd)
    }

    /**
     * The playlist is app-owned (decision 10.3): anything added by hand is
     * removed at the next sync. The alternative — a "not ours" list — means the
     * app has to remember which tracks it may touch, and gets it wrong after a
     * reinstall.
     */
    @Test
    fun `a track added by hand is scheduled for removal`() {
        val change = PlaylistDiff.compute(
            current = uris("a", "b", "addedByHand"),
            desired = uris("a", "b"),
        )

        assertEquals(uris("addedByHand"), change.toRemove)
        assertTrue(change.toAdd.isEmpty())
    }

    /**
     * A removal takes a URI, not a position, so it clears every copy. A wanted
     * track that somehow appears twice is therefore fixed by removing it and
     * adding it back — which works because a sync applies removes before adds.
     */
    @Test
    fun `a duplicate of a wanted track is removed and re-added`() {
        val change = PlaylistDiff.compute(
            current = uris("a", "b", "a"),
            desired = uris("a", "b"),
        )

        assertEquals(uris("a"), change.toRemove)
        assertEquals(uris("a"), change.toAdd)
    }

    @Test
    fun `a duplicate of an unwanted track is removed once, not twice`() {
        val change = PlaylistDiff.compute(
            current = uris("gone", "a", "gone"),
            desired = uris("a"),
        )

        assertEquals(uris("gone"), change.toRemove)
        assertTrue(change.toAdd.isEmpty())
    }

    // --- Batching -------------------------------------------------------------

    @Test
    fun `additions are split into batches of a hundred`() {
        val desired = (1..250).map { "spotify:track:t$it" }

        val batches = PlaylistDiff.compute(emptyList(), desired).addBatches()

        assertEquals(3, batches.size)
        assertEquals(100, batches[0].size)
        assertEquals(100, batches[1].size)
        assertEquals(50, batches[2].size)
        assertEquals(desired, batches.flatten())
    }

    @Test
    fun `removals are split into batches of a hundred`() {
        val current = (1..150).map { "spotify:track:t$it" }

        val batches = PlaylistDiff.compute(current, emptyList()).removeBatches()

        assertEquals(2, batches.size)
        assertEquals(100, batches[0].size)
        assertEquals(50, batches[1].size)
    }

    @Test
    fun `exactly a hundred is one batch`() {
        val desired = (1..100).map { "spotify:track:t$it" }

        assertEquals(1, PlaylistDiff.compute(emptyList(), desired).addBatches().size)
    }

    @Test
    fun `no batches when there is nothing to do`() {
        val change = PlaylistDiff.compute(uris("a"), uris("a"))

        assertTrue(change.addBatches().isEmpty())
        assertTrue(change.removeBatches().isEmpty())
    }

    @Test
    fun `no batch ever exceeds what Spotify accepts`() {
        val change = PlaylistDiff.compute(
            current = (1..400).map { "spotify:track:old$it" },
            desired = (1..400).map { "spotify:track:new$it" },
        )

        (change.addBatches() + change.removeBatches()).forEach { batch ->
            assertTrue("${batch.size} is over the limit", batch.size <= SpotifyApi.MAX_PLAYLIST_WRITE)
        }
        assertEquals(400, change.toAdd.size)
        assertEquals(400, change.toRemove.size)
    }

    // --- Realistic shape ------------------------------------------------------

    /**
     * A settled playlist with a few tracks moving in and out, which is what an
     * ordinary day looks like. The write should be a handful of tracks, not a
     * hundred — that is the whole reason for diffing rather than replacing.
     */
    @Test
    fun `an ordinary day is a handful of changes, not a rewrite`() {
        val current = (1..100).map { "spotify:track:t$it" }
        val desired = (4..103).map { "spotify:track:t$it" }

        val change = PlaylistDiff.compute(current, desired)

        assertEquals(3, change.toAdd.size)
        assertEquals(3, change.toRemove.size)
    }
}
