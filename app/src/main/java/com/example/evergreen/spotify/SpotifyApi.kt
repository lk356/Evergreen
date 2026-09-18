package com.example.evergreen.spotify

import android.util.Log
import org.json.JSONArray
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
 * The calls Evergreen makes, as an interface.
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

    // --- Playlists (Phase 3) --------------------------------------------------

    fun myPlaylists(limit: Int = SpotifyApi.MAX_PAGE, offset: Int = 0): ApiResult<PlaylistPage>
    fun createPlaylist(name: String, description: String): ApiResult<PlaylistSummary>
    fun playlistItems(playlistId: String, offset: Int = 0): ApiResult<PlaylistItemsPage>
    fun addToPlaylist(playlistId: String, uris: List<String>): ApiResult<String>
    fun removeFromPlaylist(playlistId: String, uris: List<String>): ApiResult<String>
}

/** A playlist, reduced to what Evergreen needs to recognise and write it. */
data class PlaylistSummary(
    val id: String,
    val name: String,
    /** The owner's Spotify user id, so the app only ever touches its own. */
    val ownerId: String?,
    val isPublic: Boolean?,
)

/** A page of the user's playlists. */
data class PlaylistPage(
    val items: List<PlaylistSummary>,
    val total: Int,
    val next: String?,
)

/** A page of a playlist's contents, reduced to the track URIs in order. */
data class PlaylistItemsPage(
    val uris: List<String>,
    val total: Int,
    val next: String?,
)

/**
 * The Spotify Web API calls Evergreen makes. There are exactly seven.
 *
 * Two reads for the like archive, and five for the Evergreen playlist. Nothing
 * else: the app has no profile endpoint, no search, and no way to reach a host
 * other than the two [SpotifyHosts] allows.
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

    // --- Playlists ------------------------------------------------------------
    //
    // All five re-verified against the live reference on 2026-09-18, the day
    // they were built, and unchanged from the 2026-09-17 check in the plan. The
    // pre-2026 shapes every tutorial still shows -- /tracks rather than /items,
    // POST /users/{id}/playlists, a "tracks" body on the remove -- were removed
    // in February 2026 and would all 404 or 400.

    /**
     * GET /me/playlists
     * Verified 2026-09-18 against
     * developer.spotify.com/documentation/web-api/reference/get-a-list-of-current-users-playlists
     * Scope: playlist-read-private. 50 per page.
     */
    override fun myPlaylists(limit: Int, offset: Int): ApiResult<PlaylistPage> =
        get("$API/me/playlists?limit=$limit&offset=$offset", ::parsePlaylistPage)

    /**
     * POST /me/playlists
     * Verified 2026-09-18 against
     * developer.spotify.com/documentation/web-api/reference/create-playlist
     * Scope: playlist-modify-private.
     *
     * `POST /users/{user_id}/playlists` was removed in February 2026, which is
     * also why the app needs no profile scope: the playlist is created against
     * "me" and the user id never has to be looked up.
     *
     * Always private. Evergreen is the user's own listening turned into a
     * playlist; making it public would be a disclosure, not a feature.
     */
    override fun createPlaylist(name: String, description: String): ApiResult<PlaylistSummary> =
        post(
            url = "$API/me/playlists",
            body = JSONObject()
                .put("name", name)
                .put("public", false)
                .put("description", description)
                .toString(),
            parse = ::parsePlaylist,
        )

    /**
     * GET /playlists/{playlist_id}/items
     * Verified 2026-09-18 against
     * developer.spotify.com/documentation/web-api/reference/get-playlists-items
     * Scope: playlist-read-private. 50 per page, `fields` supported.
     *
     * **`/items`, not `/tracks`.** The old path was removed in February 2026.
     *
     * `fields` is used to ask for nothing but the URIs, which is a few hundred
     * bytes a page instead of a few hundred kilobytes -- the full item objects
     * carry every album image and artist for all hundred tracks, and all this
     * needs is the diff.
     */
    override fun playlistItems(playlistId: String, offset: Int): ApiResult<PlaylistItemsPage> =
        get(
            "$API/playlists/$playlistId/items" +
                "?limit=$MAX_PAGE&offset=$offset&fields=total,next,items(item(uri),track(uri))",
            ::parsePlaylistItemsPage,
        )

    /**
     * POST /playlists/{playlist_id}/items
     * Verified 2026-09-18 against
     * developer.spotify.com/documentation/web-api/reference/add-items-to-playlist
     * Scope: playlist-modify-private. Body `{"uris": [...]}`, max 100.
     *
     * @return the new `snapshot_id`.
     */
    override fun addToPlaylist(playlistId: String, uris: List<String>): ApiResult<String> {
        require(uris.size <= MAX_PLAYLIST_WRITE) { "at most $MAX_PLAYLIST_WRITE uris per request" }
        return post(
            url = "$API/playlists/$playlistId/items",
            body = JSONObject().put("uris", JSONArray(uris)).toString(),
            parse = { json -> json.optString("snapshot_id") },
        )
    }

    /**
     * DELETE /playlists/{playlist_id}/items
     * Verified 2026-09-18 against
     * developer.spotify.com/documentation/web-api/reference/remove-items-playlist
     * Scope: playlist-modify-private. Max 100.
     *
     * **The body field is `items`, not `tracks`.** `tracks` belonged to the
     * endpoint removed in February 2026, and is what every older example shows.
     * Each entry is an object with a `uri`, not a bare string.
     */
    override fun removeFromPlaylist(playlistId: String, uris: List<String>): ApiResult<String> {
        require(uris.size <= MAX_PLAYLIST_WRITE) { "at most $MAX_PLAYLIST_WRITE uris per request" }
        val items = JSONArray()
        uris.forEach { uri -> items.put(JSONObject().put("uri", uri)) }
        return send(
            method = "DELETE",
            url = "$API/playlists/$playlistId/items",
            body = JSONObject().put("items", items).toString(),
            parse = { json -> json.optString("snapshot_id") },
        )
    }

    private fun parsePlaylist(json: JSONObject): PlaylistSummary = PlaylistSummary(
        id = json.getString("id"),
        name = json.optString("name"),
        ownerId = json.optJSONObject("owner")?.optString("id")?.takeIf { it.isNotBlank() },
        isPublic = if (json.isNull("public")) null else json.optBoolean("public"),
    )

    private fun parsePlaylistPage(json: JSONObject): PlaylistPage {
        val items = json.optJSONArray("items")
        return PlaylistPage(
            items = buildList {
                for (i in 0 until (items?.length() ?: 0)) {
                    val entry = items!!.optJSONObject(i) ?: continue
                    runCatching { parsePlaylist(entry) }.getOrNull()?.let(::add)
                }
            },
            total = json.optInt("total"),
            next = json.optString("next").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun parsePlaylistItemsPage(json: JSONObject): PlaylistItemsPage {
        val items = json.optJSONArray("items")
        return PlaylistItemsPage(
            uris = buildList {
                for (i in 0 until (items?.length() ?: 0)) {
                    val entry = items!!.optJSONObject(i) ?: continue
                    // `item` is the current field. `track` is still returned
                    // alongside it but is deprecated, so it is read only as a
                    // fallback -- and the fallback is worth having, because a
                    // playlist that silently read as empty would look to the
                    // diff like every track needing to be added again.
                    val uri = entry.optJSONObject("item")?.optString("uri")
                        ?: entry.optJSONObject("track")?.optString("uri")
                    if (!uri.isNullOrBlank()) add(uri)
                }
            },
            total = json.optInt("total"),
            next = json.optString("next").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun <T> post(url: String, body: String, parse: (JSONObject) -> T): ApiResult<T> =
        send("POST", url, body, parse)

    private fun <T> send(
        method: String,
        url: String,
        body: String,
        parse: (JSONObject) -> T,
    ): ApiResult<T> {
        val token = auth.accessToken() ?: return ApiResult.NeedsReconnect

        val response = try {
            transport.execute(
                method = method,
                url = url,
                headers = mapOf("Authorization" to "Bearer $token"),
                jsonBody = body,
            )
        } catch (e: IOException) {
            return ApiResult.Failed(code = 0, retryable = true)
        }

        return handle(response) { text ->
            // A successful add or remove returns a snapshot id; some 200s come
            // back with an empty body, which is success rather than a parse
            // failure.
            parse(if (text.isBlank()) JSONObject() else JSONObject(text))
        }
    }

    private fun <T> get(url: String, parse: (JSONObject) -> T): ApiResult<T> {
        val token = auth.accessToken() ?: return ApiResult.NeedsReconnect

        val response = try {
            transport.execute("GET", url, headers = mapOf("Authorization" to "Bearer $token"))
        } catch (e: IOException) {
            return ApiResult.Failed(code = 0, retryable = true)
        }

        return handle(response) { text -> parse(JSONObject(text)) }
    }

    /**
     * Turns one HTTP response into an [ApiResult].
     *
     * Shared by the reads and the writes so that a 429 or an expired connection
     * behaves identically whichever call hit it -- the playlist writes are the
     * ones most likely to meet a quota limit, and they must back off the same
     * way the like sync already does.
     */
    private fun <T> handle(response: HttpResponse, parse: (String) -> T): ApiResult<T> {
        when (response.code) {
            in 200..299 -> return try {
                ApiResult.Ok(parse(response.body))
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
         * The most URIs one add or remove request may carry.
         *
         * Spotify's own limit, on both endpoints. A larger batch is rejected
         * whole, so the caller splits rather than discovering this at 101.
         */
        const val MAX_PLAYLIST_WRITE = 100

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
