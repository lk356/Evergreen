package com.example.evergreen.spotify

import android.util.Log
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.IsrcLookupError
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.SpotifyDao
import com.example.evergreen.data.SyncKeys
import com.example.evergreen.data.SyncStateEntity
import com.example.evergreen.data.trackKeyOf
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** How a sync run ended. */
sealed interface SyncOutcome {
    data class Success(val liked: Int, val unliked: Int, val isrcsFilled: Int) : SyncOutcome

    /** Stopped early and changed nothing it should not have. */
    data class BackedOff(val untilMs: Long, val quotaExceeded: Boolean) : SyncOutcome
    data object NeedsReconnect : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Reads Liked Songs from Spotify into the local archive, and fills in ISRCs.
 *
 * The database is the source of truth and Spotify is a sync target, so this only
 * ever *reads* from Spotify. Likes are made and unmade with Spotify's own button.
 */
class LikeSync(
    private val db: EvergreenDatabase,
    private val api: ApiSource,
    private val dao: SpotifyDao = db.spotifyDao(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * Reads every page of Liked Songs, then reconciles what has gone.
     *
     * The reconciliation is the dangerous part and is guarded accordingly: a run
     * that does not reach the last page must not mark anything unliked, because
     * a sync interrupted by a 429, a dropped connection or a flat battery looks
     * from the database's side exactly like the user having unliked every song
     * it did not reach.
     */
    suspend fun syncLikes(): SyncOutcome {
        val backoff = backoffUntil()
        if (backoff != null && now() < backoff) {
            return SyncOutcome.BackedOff(backoff, quotaExceeded = false)
        }

        val startedAt = now()
        var seen = 0
        var url: String? = null

        while (true) {
            val result = if (url == null) api.savedTracks() else api.savedTracksAt(url)

            when (result) {
                is ApiResult.Ok -> {
                    val page = result.value
                    for (saved in page.items) {
                        recordLike(saved)
                        seen++
                    }
                    url = page.next ?: break
                    // Spotify's window is a rolling 30 seconds. Pages of 50 are
                    // few enough that a short, fixed pause keeps a large library
                    // comfortably under any limit without a tuning knob.
                    pause(PAGE_PAUSE_MS)
                }

                is ApiResult.RateLimited -> {
                    // Stop here and leave the archive exactly as it was. The
                    // likes recorded so far are correct; what is missing is the
                    // reconciliation, which is skipped precisely because this
                    // run is incomplete.
                    return backOff(result.retryAfterMs, result.quotaExceeded)
                }

                is ApiResult.NeedsReconnect -> return SyncOutcome.NeedsReconnect

                is ApiResult.NotFound -> return SyncOutcome.Failed("Spotify returned 404 for the library")

                is ApiResult.Failed -> {
                    Log.w(TAG, "like sync stopped after HTTP ${result.code}")
                    return SyncOutcome.Failed("Spotify returned HTTP ${result.code}")
                }
            }
        }

        // Every page read. Only now is it safe to conclude that a like which did
        // not appear has genuinely gone.
        val unliked = dao.markUnlikedAfterFullSync(syncStartedAt = startedAt, now = now())
        dao.putTimestamp(SyncKeys.LIKES_LAST_SYNCED_AT, now())
        clearBackoff()

        Log.i(TAG, "like sync complete: $seen liked, $unliked newly unliked")
        return SyncOutcome.Success(liked = seen, unliked = unliked, isrcsFilled = 0)
    }

    private suspend fun recordLike(saved: SavedTrack) {
        val track = saved.track
        val serviceTrackRowId = dao.resolveServiceTrack(
            trackKey = trackKeyOf(track.title, track.artist, track.album),
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            service = SERVICE_SPOTIFY,
            serviceTrackId = track.uri,
            // The ISRC rides along with the saved-track response, so the whole
            // liked library is covered at fifty tracks per request.
            isrc = track.isrc,
            now = now(),
        )
        dao.recordLiked(
            serviceTrackRowId = serviceTrackRowId,
            serviceAddedAtMs = saved.addedAtMs,
            now = now(),
        )
    }

    /**
     * Fills in ISRCs for tracks the like sync did not cover.
     *
     * These are songs in the listening history that are not liked, so they get
     * no free ride from `GET /me/tracks` and cost one request each — the batch
     * endpoint was removed in February 2026. Bounded per run and resumable:
     * every attempt is recorded, so the next run picks up where this one
     * stopped rather than starting over.
     */
    suspend fun backfillIsrcs(limit: Int = DEFAULT_ISRC_BATCH): SyncOutcome {
        val backoff = backoffUntil()
        if (backoff != null && now() < backoff) {
            return SyncOutcome.BackedOff(backoff, quotaExceeded = false)
        }

        val candidates = dao.serviceTracksNeedingIsrc(
            service = SERVICE_SPOTIFY,
            retryBefore = now() - RETRY_AFTER_FAILURE_MS,
            maxAttempts = MAX_ISRC_ATTEMPTS,
            limit = limit,
        )
        if (candidates.isEmpty()) {
            dao.putTimestamp(SyncKeys.ISRC_LAST_BACKFILL_AT, now())
            return SyncOutcome.Success(liked = 0, unliked = 0, isrcsFilled = 0)
        }

        var filled = 0
        for (candidate in candidates) {
            val trackId = SpotifyApi.trackIdFromUri(candidate.serviceTrackId)
            if (trackId == null) {
                // Not a Spotify track URI — a local file or a podcast episode.
                // Recorded so it is never retried rather than silently skipped
                // and re-picked on every run.
                dao.recordIsrcLookupFailure(candidate.id, IsrcLookupError.UNSUPPORTED_ID, now())
                continue
            }

            when (val result = api.track(trackId)) {
                is ApiResult.Ok -> {
                    val isrc = result.value.isrc
                    if (isrc != null) {
                        dao.setIsrc(candidate.id, isrc, now())
                        filled++
                    } else {
                        // Fetched fine, carried no ISRC. A real answer, not a
                        // failure, but worth recording so it is not retried
                        // every hour.
                        dao.recordIsrcLookupFailure(candidate.id, IsrcLookupError.ABSENT, now())
                    }
                }

                is ApiResult.NotFound ->
                    dao.recordIsrcLookupFailure(candidate.id, IsrcLookupError.NOT_FOUND, now())

                is ApiResult.RateLimited -> {
                    dao.putTimestamp(SyncKeys.ISRC_LAST_BACKFILL_AT, now())
                    return backOff(result.retryAfterMs, result.quotaExceeded)
                }

                is ApiResult.NeedsReconnect -> return SyncOutcome.NeedsReconnect

                is ApiResult.Failed -> {
                    dao.recordIsrcLookupFailure(candidate.id, IsrcLookupError.TRANSIENT, now())
                    if (!result.retryable) {
                        return SyncOutcome.Failed("Spotify returned HTTP ${result.code}")
                    }
                }
            }
            pause(TRACK_PAUSE_MS)
        }

        dao.putTimestamp(SyncKeys.ISRC_LAST_BACKFILL_AT, now())
        return SyncOutcome.Success(liked = 0, unliked = 0, isrcsFilled = filled)
    }

    // --- Backoff bookkeeping -------------------------------------------------

    private suspend fun backoffUntil(): Long? = dao.syncState(SyncKeys.API_BACKOFF_UNTIL)?.valueMs

    private suspend fun backOff(forMs: Long, quotaExceeded: Boolean): SyncOutcome.BackedOff {
        val until = now() + forMs
        dao.putTimestamp(SyncKeys.API_BACKOFF_UNTIL, until)
        dao.putSyncState(
            SyncStateEntity(
                key = SyncKeys.API_BACKOFF_REASON,
                valueText = if (quotaExceeded) "QUOTA_EXCEEDED" else "RATE_LIMITED",
            ),
        )
        Log.i(TAG, "backing off for ${forMs}ms (quotaExceeded=$quotaExceeded)")
        return SyncOutcome.BackedOff(until, quotaExceeded)
    }

    private suspend fun clearBackoff() {
        dao.putSyncState(SyncStateEntity(SyncKeys.API_BACKOFF_UNTIL, valueMs = null))
        dao.putSyncState(SyncStateEntity(SyncKeys.API_BACKOFF_REASON, valueText = null))
    }

    companion object {
        private const val TAG = "LikeSync"

        /** Between pages of 50. Keeps a large library well inside the window. */
        private const val PAGE_PAUSE_MS = 250L

        /** Between individual track lookups, which are the expensive ones. */
        private const val TRACK_PAUSE_MS = 300L

        /** Per run, so one sync cannot spend the whole quota on backfill. */
        const val DEFAULT_ISRC_BATCH = 100

        /** A row that failed is not retried before this much time has passed. */
        val RETRY_AFTER_FAILURE_MS: Long = TimeUnit.DAYS.toMillis(7)

        /** After this many failures a row is left alone until something changes. */
        const val MAX_ISRC_ATTEMPTS = 5
    }
}
