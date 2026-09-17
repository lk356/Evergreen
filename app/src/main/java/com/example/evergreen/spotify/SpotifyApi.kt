package com.example.evergreen.spotify

import android.util.Log
import org.json.JSONObject
import java.io.IOException

/** One track as the Web API describes it, reduced to what Evergreen stores. */
data class SpotifyTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** From `external_ids.isrc`; null when the track object carries none. */
    val isrc: String?,
)

/** One entry in the user's saved tracks. */
data class SavedTrack(
    val track: SpotifyTrack,
    /** Spotify's `added_at`, in epoch milliseconds, or null if unparseable. */
    val addedAtMs: Long?,
)

/** A page of saved tracks. */
data class SavedTracksPage(
    val items: List<SavedTrack>,
    val total: Int,
    /** Absolute URL of the next page, or null at the end. */
    val next: String?,
)

/** What came back from a call, in the terms the caller has to act on. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 429. [retryAfterMs] already accounts for Retry-After and QUOTA_EXCEEDED. */
    data class RateLimited(val retryAfterMs: Long, val quotaExceeded: Boolean) : ApiResult<Nothing>

    /** The connection is unusable; the user must reconnect. */
    data object NeedsReconnect : ApiResult<Nothing>

    /** 404 — this particular item is gone, but the connection is fine. */
    data object NotFound : ApiResult<Nothing>

    /** Anything else. [retryable] separates a network blip from a 400. */
    data class Failed(val code: Int, val retryable: Boolean) : ApiResult<Nothing>
}

/**
 * The two calls Evergreen makes, as an interface.
 *
 * Exists so the sync logic can be tested against scripted answers -- rate
 * limits, 404s, an expired connection -- without a network or a device. Those
 * paths are the ones most likely to be wrong and least likely to be exercised by
 * hand.
 */
interface ApiSource {
    fun savedTracks(limit: Int = SpotifyApi.MAX_PAGE, offset: Int = 0): ApiResult<SavedTracksPage>
    fun savedTracksAt(url: String): ApiResult<SavedTracksPage>
    fun track(trackId: String): ApiResult<SpotifyTrack>
}

/**
 * The Spotify Web API calls Evergreen makes. There are exactly two.
 *
 * Every endpoint below records the reference page it was verified against and
 * the date. The API changed three times between February and July 2026, so
 * "verified once, on this date" is more useful than an assumption.
 */
class SpotifyApi(
    private val auth: SpotifyAuth,
    private val transport: HttpTransport = UrlConnectionTransport(),
) : ApiSource {

    /**
     * One page of the user's Liked Songs.
     *
     * GET https://api.spotify.com/v1/me/tracks
     * Scope: user-library-read. Max limit 50.
     * Verified 2026-09-17 against
     * developer.spotify.com/documentation/web-api/reference/get-users-saved-tracks
     *
     * Still available to development-mode apps: the February 2026 consolidation
     * moved the *write* endpoints (PUT/DELETE /me/tracks) to the generic
     * /me/library, and left this read where it was.
     *
     * The response carries full track objects, so every liked song's ISRC
     * arrives here at fifty per request. That matters because the batch track
     * endpoint is gone (see [track]).
     */
    override fun savedTracks(limit: Int, offset: Int): ApiResult<SavedTracksPage> =
        savedTracksAt("$API/me/tracks?limit=$limit&offset=$offset")

    /** Follows a `next` URL from a previous page. */
    override fun savedTracksAt(url: String): ApiResult<SavedTracksPage> =
        get(url) { json ->
            val items = json.optJSONArray("items")
            SavedTracksPage(
                items = buildList {
                    for (i in 0 until (items?.length() ?: 0)) {
                        val item = items!!.getJSONObject(i)
                        val track = item.optJSONObject("track") ?: continue
                        parseTrack(track)?.let { parsed ->
                            add(
                                SavedTrack(
                                    track = parsed,
                                    addedAtMs = item.optString("added_at")
                                        .takeIf { it.isNotBlank() }
                                        ?.let(::parseIso8601),
                                ),
                            )
                        }
                    }
                },
                total = json.optInt("total", 0),
                next = json.optString("next").takeIf { it.isNotBlank() && it != "null" },
            )
        }

    /**
     * One track by its Spotify ID.
     *
     * GET https://api.spotify.com/v1/tracks/{id}
     * Verified 2026-09-17 against
     * developer.spotify.com/documentation/web-api/reference/get-track
     *
     * **One at a time, deliberately.** The batch endpoint `GET /tracks?ids=`
     * was removed for development-mode apps in February 2026 and Spotify's own
     * migration guide says to "fetch items individually instead". So this is
     * used only for tracks in the listening history that are *not* liked; liked
     * songs get their ISRC free from [savedTracks], fifty at a time.
     *
     * `external_ids.isrc` was itself removed in February 2026 and reverted in
     * March. It is present as of the date above, but it has gone once before.
     */
    override fun track(trackId: String): ApiResult<SpotifyTrack> =
        get("$API/tracks/$trackId") { json ->
            parseTrack(json) ?: throw IllegalArgumentException("unreadable track object")
        }

    private fun <T> get(url: String, parse: (JSONObject) -> T): ApiResult<T> {
        val token = auth.accessToken() ?: return ApiResult.NeedsReconnect

        val response = try {
            transport.execute("GET", url, headers = mapOf("Authorization" to "Bearer $token"))
        } catch (e: IOException) {
            return ApiResult.Failed(code = 0, retryable = true)
        }

        when (response.code) {
            in 200..299 -> return try {
                ApiResult.Ok(parse(JSONObject(response.body)))
            } catch (e: Exception) {
                Log.w(TAG, "could not read a Spotify response", e)
                ApiResult.Failed(code = response.code, retryable = false)
            }

            401 -> {
                // The token was rejected despite looking fresh — revoked from
                // spotify.com/account/apps, most likely. Force a refresh and let
                // the caller come back; if the refresh token is dead too, the
                // next call returns NeedsReconnect.
                auth.invalidateAccessToken()
                return ApiResult.Failed(code = 401, retryable = true)
            }

            403 -> {
                // In development mode this is what a user who is not on the
                // app's allowlist gets: login succeeds, every request is
                // refused. Not retryable, and not something more requests fix.
                Log.w(TAG, "403 from Spotify: is this account on the app's allowlist?")
                return ApiResult.Failed(code = 403, retryable = false)
            }

            404 -> return ApiResult.NotFound

            429 -> {
                val quotaExceeded = response.body.contains(QUOTA_EXCEEDED)
                return ApiResult.RateLimited(
                    retryAfterMs = Backoff.forRateLimit(response.retryAfterSeconds, quotaExceeded),
                    quotaExceeded = quotaExceeded,
                )
            }

            else -> return ApiResult.Failed(
                code = response.code,
                retryable = response.code >= 500,
            )
        }
    }

    private fun parseTrack(json: JSONObject): SpotifyTrack? {
        val uri = json.optString("uri").takeIf { it.startsWith(TRACK_URI_PREFIX) } ?: return null
        val artists = json.optJSONArray("artists")
        val artist = buildList {
            for (i in 0 until (artists?.length() ?: 0)) {
                artists!!.getJSONObject(i).optString("name").takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString(", ")

        return SpotifyTrack(
            uri = uri,
            title = json.optString("name"),
            artist = artist,
            album = json.optJSONObject("album")?.optString("name").orEmpty(),
            durationMs = json.optLong("duration_ms", 0L),
            isrc = json.optJSONObject("external_ids")
                ?.optString("isrc")
                ?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        private const val TAG = "SpotifyApi"
        private const val API = "https://${SpotifyHosts.API}/v1"
        private const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
        const val TRACK_URI_PREFIX = "spotify:track:"
        const val MAX_PAGE = 50

        /**
         * Turns a Spotify `added_at` into epoch milliseconds.
         *
         * Hand-parsed rather than pulled through `java.time` formatting so a
         * shape we did not expect returns null instead of throwing inside a
         * sync. The archive can live without one timestamp; it cannot live with
         * a sync that dies partway.
         */
        fun parseIso8601(value: String): Long? = try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            null
        }

        /** `spotify:track:xyz` -> `xyz`, or null if it is not a track URI. */
        fun trackIdFromUri(uri: String): String? =
            uri.removePrefix(TRACK_URI_PREFIX).takeIf { it != uri && it.isNotBlank() }
    }
}
