package com.example.evergreen.spotify

/** What has to change to turn the playlist as it is into the playlist as it should be. */
data class PlaylistChange(
    /** URIs to add, in the order they should appear. */
    val toAdd: List<String>,
    /** URIs to remove. */
    val toRemove: List<String>,
) {
    val isEmpty: Boolean get() = toAdd.isEmpty() && toRemove.isEmpty()
    val size: Int get() = toAdd.size + toRemove.size

    /**
     * The add batches, at most [SpotifyApi.MAX_PLAYLIST_WRITE] each.
     *
     * Spotify rejects an oversized batch whole rather than truncating it.
     */
    fun addBatches(): List<List<String>> = toAdd.chunked(SpotifyApi.MAX_PLAYLIST_WRITE)

    fun removeBatches(): List<List<String>> = toRemove.chunked(SpotifyApi.MAX_PLAYLIST_WRITE)
}

/**
 * Works out the smallest set of changes that turns one playlist into another.
 *
 * **Diffs, not replacements** (decisions 6). Replacing the contents wholesale
 * would be simpler to write and worse in three ways: it rewrites a hundred
 * tracks a day against a quota that is per developer account and unpublished,
 * it throws away every track's `added_at` so the playlist loses any sense of
 * when things arrived, and a failure half way leaves the playlist empty rather
 * than merely stale.
 *
 * Pure, and order-insensitive on the current side: Spotify returns the playlist
 * in its own order, and Evergreen is meant to be shuffled, so reordering an
 * unchanged playlist would be a hundred writes to achieve nothing.
 */
object PlaylistDiff {

    /**
     * @param current the URIs actually in the playlist, as Spotify reports them.
     * @param desired the URIs that should be there, best rank first.
     */
    fun compute(current: List<String>, desired: List<String>): PlaylistChange {
        val currentSet = current.toSet()
        val desiredSet = desired.toSet()

        // Spotify allows the same track in a playlist twice, and a removal
        // takes a URI rather than a position, so it clears every copy. A
        // wanted track that appears twice is therefore fixed by removing it
        // and adding it back -- which works because a sync applies its removes
        // before its adds. Without this the diff would report "nothing to do"
        // forever while the playlist stayed wrong, and noticing that kind of
        // drift is the whole reason the playlist is read back before a write.
        val duplicatedButWanted = current
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .filter { it in desiredSet }
            .toSet()

        return PlaylistChange(
            // Kept in desired order so a first write produces a playlist that
            // reads best-first, even though nothing later depends on the order.
            toAdd = desired.filter { it !in currentSet || it in duplicatedButWanted }.distinct(),
            // Anything present that should not be. This is where a track added
            // to the playlist by hand is scheduled for removal: the playlist is
            // app-owned (decision 10.3), and the alternative -- keeping a
            // "not ours" list -- means the app has to remember which tracks it
            // is allowed to touch, and gets it wrong after a reinstall.
            toRemove = (current.filter { it !in desiredSet } + duplicatedButWanted).distinct(),
        )
    }
}
