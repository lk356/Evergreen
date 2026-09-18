package com.example.evergreen.spotify

import android.util.Log
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.MembershipReason
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.SyncKeys
import com.example.evergreen.scoring.Membership
import com.example.evergreen.scoring.MembershipDecision
import com.example.evergreen.scoring.ScoreService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a sync did, or would do. Counts only; never a row. */
data class EvergreenSyncResult(
    val outcome: Outcome,
    val change: PlaylistChange = PlaylistChange(emptyList(), emptyList()),
    val added: Int = 0,
    val removed: Int = 0,
    /** URIs Spotify refused — unavailable tracks. Counted, never fatal. */
    val refused: Int = 0,
    val playlistId: String? = null,
    val message: String? = null,
) {
    enum class Outcome {
        /** Applied (or, for a dry run, computed) successfully. */
        OK,

        /** Nothing to do. */
        NO_CHANGES,

        /** Not connected, or the grant lacks the playlist scopes. */
        NEEDS_RECONNECT,

        /** Inside a backoff window, or rate limited part way through. */
        BACKED_OFF,

        /** Already written today. */
        TOO_SOON,

        /** This device is not the one that writes Evergreen. */
        NOT_THE_WRITER,

        /** A diff is waiting for the user to confirm the first write. */
        AWAITING_CONFIRMATION,

        FAILED,
    }
}

/**
 * Writes the Evergreen playlist on Spotify.
 *
 * **The first write is behind a confirmation** (decisions 6), the same pattern
 * as the same-song merge. Until the user has confirmed once, every run
 * computes the diff, shows it, and writes nothing. That is why step 11's
 * explain-the-score view was built before this: the scoring can be looked at
 * over six years of real listening, and retuned, before anything can change a
 * playlist.
 *
 * **The app never touches a playlist it did not create** (decision 13). It
 * writes only to the playlist whose id it stored; it looks one up by name only
 * when no id is stored, and only among playlists the user owns; and there is no
 * code path here that deletes a playlist.
 */
class EvergreenSync(
    private val db: EvergreenDatabase,
    private val api: ApiSource,
    private val scores: ScoreService = ScoreService(db),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val scoringDao = db.scoringDao()
    private val importDao = db.importDao()
    private val spotifyDao = db.spotifyDao()

    /**
     * Computes what a write would do, without writing anything.
     *
     * Reads the playlist from Spotify, so it costs two requests and tells the
     * truth about drift, rather than reporting what the app believes.
     */
    suspend fun dryRun(): EvergreenSyncResult = onIoThread { run(apply = false) }

    /** Computes and applies, subject to every guard below. */
    suspend fun sync(): EvergreenSyncResult = onIoThread { run(apply = true) }

    /**
     * Moves the work off whatever thread the caller is on.
     *
     * **This function owns its dispatcher rather than trusting callers**, and
     * that is the fix for a real crash: the debug screen's "Preview Evergreen
     * sync" button launched from `rememberCoroutineScope()`, which is Compose's
     * *main-thread* dispatcher, and the first HTTP call threw
     * `NetworkOnMainThreadException`. The scheduled worker never hit it because
     * `CoroutineWorker` already runs off the main thread, so the same code was
     * fine by one route and fatal by the other.
     *
     * A `suspend` function is not automatically off the main thread — it runs
     * wherever its caller is unless it says otherwise — and [ApiSource] is a
     * plain blocking interface. Putting the rule here means no future caller
     * can get it wrong, which is worth more than a comment telling them not to.
     */
    private suspend fun <T> onIoThread(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }

    private suspend fun run(apply: Boolean): EvergreenSyncResult {
        val at = now()

        // 1. Guards. Each one stops before a single request is made.
        if (apply) {
            if (!importDao.isEvergreenWriter()) {
                return EvergreenSyncResult(
                    EvergreenSyncResult.Outcome.NOT_THE_WRITER,
                    message = "This device is not set as the one that writes Evergreen.",
                )
            }
            backoffRemainingMs(at)?.let { remaining ->
                return EvergreenSyncResult(
                    EvergreenSyncResult.Outcome.BACKED_OFF,
                    message = "Backing off for another ${remaining / 60_000} minutes.",
                )
            }
            val lastWrite = scoringDao.syncStateMs(SyncKeys.EVERGREEN_LAST_WRITTEN_AT)
            if (lastWrite != null && at - lastWrite < WRITE_INTERVAL_MS) {
                return EvergreenSyncResult(
                    EvergreenSyncResult.Outcome.TOO_SOON,
                    message = "Evergreen is written at most once a day.",
                )
            }
        }

        // 2. Recompute. Never touches the network.
        val ranking = scores.ranking(at, force = true)
        val parameters = ranking.parameters

        // 3. The desired set.
        val likedTrackIds = scoringDao.likedTrackIds().toSet()
        val decision = Membership.decide(
            currentMembers = scoringDao.currentMemberTrackIds().toSet(),
            ranked = ranking.tracks,
            likedTrackIds = likedTrackIds,
            parameters = parameters,
        )
        val desiredUris = decision.members.mapNotNull { trackId ->
            // The URI written is the *liked* service row's, so the playlist
            // gets the version the user liked rather than whichever pressing
            // happened to be played most.
            scoringDao.preferredServiceUri(trackId, SERVICE_SPOTIFY, SpotifyApi.TRACK_URI_PREFIX)
        }

        // 4. The current set, read from Spotify rather than assumed.
        val playlistId = when (val found = findOrCreatePlaylist(create = apply)) {
            is Resolved.Ok -> found.id
            is Resolved.Problem -> return found.result
        }
        val current = when (val read = readPlaylist(playlistId)) {
            is Resolved.Ok -> read.id
            is Resolved.Problem -> return read.result
        }

        // 5. Diff.
        val change = PlaylistDiff.compute(current = current, desired = desiredUris)
        if (change.isEmpty) {
            if (apply) recordWrite(at, decision, emptyList(), emptyList())
            return EvergreenSyncResult(
                EvergreenSyncResult.Outcome.NO_CHANGES,
                change = change,
                playlistId = playlistId,
                message = "Evergreen is already up to date (${desiredUris.size} tracks).",
            )
        }

        if (!apply) {
            return EvergreenSyncResult(
                EvergreenSyncResult.Outcome.OK,
                change = change,
                playlistId = playlistId,
                message = "Would add ${change.toAdd.size} and remove ${change.toRemove.size}.",
            )
        }

        if (!hasConfirmedFirstWrite()) {
            return EvergreenSyncResult(
                EvergreenSyncResult.Outcome.AWAITING_CONFIRMATION,
                change = change,
                playlistId = playlistId,
                message = "The first write needs confirming on the Spotify tab.",
            )
        }

        // 6. Apply. Removes first, so a playlist at its limit has room for the
        //    adds, and so a wanted duplicate is cleared before being re-added.
        var removed = 0
        var added = 0
        var refused = 0

        for (batch in change.removeBatches()) {
            when (val result = api.removeFromPlaylist(playlistId, batch)) {
                is ApiResult.Ok -> removed += batch.size
                else -> return stopFor(result, at, change, playlistId, added, removed, refused)
            }
        }
        for (batch in change.addBatches()) {
            when (val result = api.addToPlaylist(playlistId, batch)) {
                is ApiResult.Ok -> added += batch.size
                // A batch refused whole is usually one unavailable track in it.
                // Retrying the batch one URI at a time finds which, so a single
                // unavailable song cannot cost the other 99 their place.
                is ApiResult.Failed -> {
                    if (result.retryable) {
                        return stopFor(result, at, change, playlistId, added, removed, refused)
                    }
                    val (ok, bad) = addOneByOne(playlistId, batch)
                    added += ok
                    refused += bad
                }
                else -> return stopFor(result, at, change, playlistId, added, removed, refused)
            }
        }

        recordWrite(at, decision, change.toAdd, change.toRemove)
        Log.i(TAG, "evergreen sync: +$added -$removed, $refused refused")

        return EvergreenSyncResult(
            EvergreenSyncResult.Outcome.OK,
            change = change,
            added = added,
            removed = removed,
            refused = refused,
            playlistId = playlistId,
            message = "Added $added, removed $removed" +
                if (refused > 0) ", $refused unavailable and skipped." else ".",
        )
    }

    /**
     * Adds one URI at a time after a batch was refused.
     *
     * Spotify's desktop client shows 380 liked songs and the phone shows 374;
     * the difference is tracks unavailable in this region, and adding one of
     * those to a playlist can be refused. A refused URI must not abort the run
     * (decision 14): it is skipped, counted, and the rest go in. The snapshot
     * keeps the track either way.
     */
    private fun addOneByOne(playlistId: String, batch: List<String>): Pair<Int, Int> {
        var ok = 0
        var refused = 0
        batch.forEach { uri ->
            when (api.addToPlaylist(playlistId, listOf(uri))) {
                is ApiResult.Ok -> ok++
                else -> refused++
            }
        }
        return ok to refused
    }

    private sealed interface Resolved<out T> {
        data class Ok<T>(val id: T) : Resolved<T>
        data class Problem(val result: EvergreenSyncResult) : Resolved<Nothing>
    }

    /**
     * The playlist to write to.
     *
     * The stored id wins. Discovery by name happens only when there is no
     * stored id, and only among playlists the user owns — so the app can never
     * adopt someone else's playlist that happens to be called Evergreen, for
     * instance one shared with the user. Creating is last, and only on a real
     * sync: a dry run must never leave a playlist behind.
     */
    private suspend fun findOrCreatePlaylist(create: Boolean): Resolved<String> {
        scoringDao.syncStateText(SyncKeys.EVERGREEN_PLAYLIST_ID)?.let { return Resolved.Ok(it) }

        var offset = 0
        while (true) {
            when (val page = api.myPlaylists(offset = offset)) {
                is ApiResult.Ok -> {
                    val match = page.value.items.firstOrNull { it.name == PLAYLIST_NAME }
                    if (match != null) {
                        scoringDao.putSyncText(SyncKeys.EVERGREEN_PLAYLIST_ID, match.id)
                        Log.i(TAG, "adopted an existing $PLAYLIST_NAME playlist")
                        return Resolved.Ok(match.id)
                    }
                    if (page.value.next == null) break
                    offset += SpotifyApi.MAX_PAGE
                }
                else -> return Resolved.Problem(problemFor(page))
            }
        }

        if (!create) {
            return Resolved.Problem(
                EvergreenSyncResult(
                    EvergreenSyncResult.Outcome.OK,
                    message = "No $PLAYLIST_NAME playlist yet; it will be created on the first write.",
                ),
            )
        }

        return when (val created = api.createPlaylist(PLAYLIST_NAME, PLAYLIST_DESCRIPTION)) {
            is ApiResult.Ok -> {
                scoringDao.putSyncText(SyncKeys.EVERGREEN_PLAYLIST_ID, created.value.id)
                Log.i(TAG, "created the $PLAYLIST_NAME playlist")
                Resolved.Ok(created.value.id)
            }
            else -> Resolved.Problem(problemFor(created))
        }
    }

    private fun readPlaylist(playlistId: String): Resolved<List<String>> {
        val uris = mutableListOf<String>()
        var offset = 0
        while (true) {
            when (val page = api.playlistItems(playlistId, offset = offset)) {
                is ApiResult.Ok -> {
                    uris += page.value.uris
                    if (page.value.next == null || page.value.uris.isEmpty()) break
                    offset += SpotifyApi.MAX_PAGE
                }
                // The stored id points at something that is gone -- the
                // playlist was deleted in the Spotify client. Forgetting the id
                // is right: the next run discovers or creates one. Deleting is
                // the user's prerogative and this app has no delete path at all.
                ApiResult.NotFound -> return Resolved.Problem(
                    EvergreenSyncResult(
                        EvergreenSyncResult.Outcome.FAILED,
                        message = "The Evergreen playlist no longer exists; it will be recreated.",
                    ),
                )
                else -> return Resolved.Problem(problemFor(page))
            }
        }
        return Resolved.Ok(uris)
    }

    private fun problemFor(result: ApiResult<*>): EvergreenSyncResult = when (result) {
        is ApiResult.NeedsReconnect -> EvergreenSyncResult(
            EvergreenSyncResult.Outcome.NEEDS_RECONNECT,
            message = "Reconnect Spotify to keep Evergreen up to date.",
        )
        is ApiResult.RateLimited -> EvergreenSyncResult(
            EvergreenSyncResult.Outcome.BACKED_OFF,
            message = if (result.quotaExceeded) {
                "Spotify's quota is exhausted; backing off for hours."
            } else {
                "Rate limited; backing off."
            },
        )
        else -> EvergreenSyncResult(
            EvergreenSyncResult.Outcome.FAILED,
            message = "Spotify refused the request ($result).",
        )
    }

    /**
     * Stops part way through, recording the backoff but **not** the write time.
     *
     * Not recording it is what lets the next run finish the job: the diff is
     * recomputed from the live playlist each time, so whatever went in already
     * simply is not in the diff any more. Nothing is stored as done until the
     * API says so.
     */
    private suspend fun stopFor(
        result: ApiResult<*>,
        at: Long,
        change: PlaylistChange,
        playlistId: String,
        added: Int,
        removed: Int,
        refused: Int,
    ): EvergreenSyncResult {
        if (result is ApiResult.RateLimited) {
            spotifyDao.putSyncState(
                com.example.evergreen.data.SyncStateEntity(
                    key = SyncKeys.API_BACKOFF_UNTIL,
                    valueMs = at + result.retryAfterMs,
                ),
            )
            spotifyDao.putSyncState(
                com.example.evergreen.data.SyncStateEntity(
                    key = SyncKeys.API_BACKOFF_REASON,
                    valueText = if (result.quotaExceeded) "QUOTA_EXCEEDED" else "rate limited",
                ),
            )
        }
        val base = problemFor(result)
        return base.copy(
            change = change,
            added = added,
            removed = removed,
            refused = refused,
            playlistId = playlistId,
            message = base.message + " Stopped after +$added -$removed; the next run resumes.",
        )
    }

    private suspend fun backoffRemainingMs(at: Long): Long? =
        scoringDao.syncStateMs(SyncKeys.API_BACKOFF_UNTIL)
            ?.takeIf { it > at }
            ?.let { it - at }

    private suspend fun recordWrite(
        at: Long,
        decision: MembershipDecision,
        added: List<String>,
        removed: List<String>,
    ) {
        scoringDao.recordMembershipChanges(
            joined = decision.joining.map { Triple(it.trackId, it.reason, it.rank ?: 0) },
            left = decision.leaving.map { Triple(it.trackId, it.reason, it.rank) },
            at = at,
        )
        scoringDao.putSyncMs(SyncKeys.EVERGREEN_LAST_WRITTEN_AT, at)
    }

    suspend fun hasConfirmedFirstWrite(): Boolean =
        scoringDao.syncStateMs(SyncKeys.EVERGREEN_WRITE_CONFIRMED_AT) != null

    /** Records the one-time confirmation. Never undone automatically. */
    suspend fun confirmFirstWrite() {
        scoringDao.putSyncMs(SyncKeys.EVERGREEN_WRITE_CONFIRMED_AT, now())
    }

    companion object {
        private const val TAG = "EvergreenSync"

        const val PLAYLIST_NAME = "Evergreen"
        const val PLAYLIST_DESCRIPTION =
            "Liked songs you are still listening to. Managed automatically by the Evergreen app."

        /** At most one write a day (decisions 6), against an unpublished quota. */
        const val WRITE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
