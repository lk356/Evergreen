package com.example.evergreen.spotify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * Test 10 of `docs/PHASE3_PLAN.md` section 9: the playlist URLs are on the
 * allowed host; the remove body uses `items`, not `tracks`; more than 100 URIs
 * are refused.
 *
 * Every one of these endpoints changed shape in February 2026, and the shapes
 * every older tutorial still shows would 404 or 400. These assertions are what
 * stops the code drifting back to them.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistApiTest {

    /** Records the request and replies with whatever the test scripted. */
    private class RecordingTransport : HttpTransport {
        var response: HttpResponse = HttpResponse(200, "{}", null)
        val methods = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String?>()

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            form: Map<String, String>?,
            jsonBody: String?,
        ): HttpResponse {
            // Even the fake enforces the host allowlist, so a test cannot bless
            // a URL the real transport would refuse to send.
            SpotifyHosts.require(url)
            methods += method
            urls += url
            bodies += jsonBody
            return response
        }
    }

    private lateinit var api: SpotifyApi
    private val transport = RecordingTransport()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tokenStore = TokenStore(context)
        // A live connection, so the calls reach the transport instead of
        // short-circuiting to NeedsReconnect. The token is a fixture and never
        // leaves the device; the transport here is a fake.
        tokenStore.save(
            SpotifyTokens(
                accessToken = "test-access-token",
                refreshToken = "test-refresh-token",
                accessTokenExpiresAtMs = System.currentTimeMillis() + 60 * 60 * 1000,
                authorizedAtMs = System.currentTimeMillis(),
                grantedScopes = SpotifyScopes.asParameter,
            ),
        )
        api = SpotifyApi(
            SpotifyAuth(context, tokenStore = tokenStore, transport = transport),
            transport = transport,
        )
    }

    @After
    fun tearDown() {
        TokenStore(ApplicationProvider.getApplicationContext<Context>()).clear(expired = false)
    }

    // --- Paths ----------------------------------------------------------------

    /**
     * `/items`, not `/tracks`. The old path was removed in February 2026.
     */
    @Test
    fun readingAPlaylistUsesTheItemsPath() {
        transport.response = HttpResponse(200, """{"total":0,"items":[]}""", null)

        api.playlistItems("abc123")

        val url = transport.urls.single()
        assertTrue(url, url.startsWith("https://api.spotify.com/v1/playlists/abc123/items"))
        assertTrue("must not use the removed /tracks path", !url.contains("/tracks"))
        assertEquals("GET", transport.methods.single())
    }

    /** `POST /me/playlists`; `POST /users/{id}/playlists` is gone. */
    @Test
    fun creatingAPlaylistPostsToMePlaylists() {
        transport.response = HttpResponse(201, """{"id":"new1","name":"Evergreen"}""", null)

        api.createPlaylist("Evergreen", "managed by Evergreen")

        assertEquals("https://api.spotify.com/v1/me/playlists", transport.urls.single())
        assertEquals("POST", transport.methods.single())
        assertTrue("must not use the removed /users path", !transport.urls.single().contains("/users/"))
    }

    @Test
    fun addingPostsToTheItemsPath() {
        transport.response = HttpResponse(201, """{"snapshot_id":"snap1"}""", null)

        api.addToPlaylist("abc123", listOf("spotify:track:a"))

        assertEquals("https://api.spotify.com/v1/playlists/abc123/items", transport.urls.single())
        assertEquals("POST", transport.methods.single())
    }

    @Test
    fun removingDeletesTheItemsPath() {
        transport.response = HttpResponse(200, """{"snapshot_id":"snap2"}""", null)

        api.removeFromPlaylist("abc123", listOf("spotify:track:a"))

        assertEquals("https://api.spotify.com/v1/playlists/abc123/items", transport.urls.single())
        assertEquals("DELETE", transport.methods.single())
    }

    @Test
    fun everyPlaylistUrlIsOnTheAllowedHost() {
        transport.response = HttpResponse(200, """{"total":0,"items":[]}""", null)

        api.myPlaylists()
        api.playlistItems("abc")
        api.createPlaylist("x", "y")
        api.addToPlaylist("abc", listOf("spotify:track:a"))
        api.removeFromPlaylist("abc", listOf("spotify:track:a"))

        assertEquals(5, transport.urls.size)
        transport.urls.forEach { url ->
            assertTrue(url, url.startsWith("https://${SpotifyHosts.API}/v1/"))
            // Would throw if it were not; asserted explicitly so the intent is
            // visible rather than implied by the fake.
            SpotifyHosts.require(url)
        }
    }

    // --- Bodies ---------------------------------------------------------------

    /**
     * The field is `items`, and each entry is an object with a `uri`. `tracks`
     * belonged to the endpoint removed in February 2026 and is what every older
     * example shows.
     */
    @Test
    fun theRemoveBodyUsesItemsNotTracks() {
        transport.response = HttpResponse(200, """{"snapshot_id":"s"}""", null)

        api.removeFromPlaylist("abc123", listOf("spotify:track:a", "spotify:track:b"))

        val body = JSONObject(transport.bodies.single()!!)
        assertTrue("the body must have an 'items' field", body.has("items"))
        assertTrue("the body must not have a 'tracks' field", !body.has("tracks"))

        val items = body.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals("spotify:track:a", items.getJSONObject(0).getString("uri"))
        assertEquals("spotify:track:b", items.getJSONObject(1).getString("uri"))
    }

    @Test
    fun theAddBodyIsAPlainUriArray() {
        transport.response = HttpResponse(201, """{"snapshot_id":"s"}""", null)

        api.addToPlaylist("abc123", listOf("spotify:track:a", "spotify:track:b"))

        val body = JSONObject(transport.bodies.single()!!)
        val uris = body.getJSONArray("uris")
        assertEquals(2, uris.length())
        assertEquals("spotify:track:a", uris.getString(0))
    }

    /** Evergreen is the user's own listening. It is never created public. */
    @Test
    fun aCreatedPlaylistIsAlwaysPrivate() {
        transport.response = HttpResponse(201, """{"id":"new1","name":"Evergreen"}""", null)

        api.createPlaylist("Evergreen", "managed by Evergreen")

        val body = JSONObject(transport.bodies.single()!!)
        assertEquals("Evergreen", body.getString("name"))
        assertEquals(false, body.getBoolean("public"))
        assertEquals("managed by Evergreen", body.getString("description"))
    }

    // --- Limits ---------------------------------------------------------------

    /**
     * Spotify rejects a batch of more than 100 whole, so the caller has to
     * split. Refusing here means that is discovered in a test rather than at
     * track 101 of a real write.
     */
    @Test
    fun moreThanOneHundredUrisIsRefusedRatherThanSent() {
        val tooMany = (1..101).map { "spotify:track:t$it" }

        assertThrows(IllegalArgumentException::class.java) { api.addToPlaylist("abc", tooMany) }
        assertThrows(IllegalArgumentException::class.java) { api.removeFromPlaylist("abc", tooMany) }
        assertEquals("nothing should have been sent", 0, transport.urls.size)
    }

    @Test
    fun exactlyOneHundredIsAllowed() {
        transport.response = HttpResponse(201, """{"snapshot_id":"s"}""", null)

        api.addToPlaylist("abc", (1..100).map { "spotify:track:t$it" })

        assertEquals(1, transport.urls.size)
    }

    @Test
    fun readingAPlaylistAsksForFiftyAtATimeAndOnlyTheUris() {
        transport.response = HttpResponse(200, """{"total":0,"items":[]}""", null)

        api.playlistItems("abc", offset = 50)

        val url = transport.urls.single()
        assertTrue(url, url.contains("limit=50"))
        assertTrue(url, url.contains("offset=50"))
        // Asking for the whole item objects would be a few hundred kilobytes a
        // page to compute a diff that needs only the URIs.
        assertTrue(url, url.contains("fields="))
    }

    // --- Parsing --------------------------------------------------------------

    /**
     * Entries carry both `item` (current) and `track` (deprecated). Reading
     * `item` is right; the fallback matters because a playlist that silently
     * read as empty would look to the diff like every track needing adding
     * again.
     */
    @Test
    fun playlistItemsAreReadFromTheItemField() {
        transport.response = HttpResponse(
            200,
            """
            {"total":2,"next":null,"items":[
              {"item":{"uri":"spotify:track:a"},"track":{"uri":"spotify:track:OLD"}},
              {"item":{"uri":"spotify:track:b"}}
            ]}
            """.trimIndent(),
            null,
        )

        val result = api.playlistItems("abc") as ApiResult.Ok

        assertEquals(listOf("spotify:track:a", "spotify:track:b"), result.value.uris)
        assertEquals(2, result.value.total)
        assertNull(result.value.next)
    }

    @Test
    fun theDeprecatedTrackFieldIsUsedOnlyWhenItemIsAbsent() {
        transport.response = HttpResponse(
            200,
            """{"total":1,"items":[{"track":{"uri":"spotify:track:only"}}]}""",
            null,
        )

        val result = api.playlistItems("abc") as ApiResult.Ok

        assertEquals(listOf("spotify:track:only"), result.value.uris)
    }

    @Test
    fun aPlaylistPageReportsItsOwnerSoTheAppCanTellItsOwnApart() {
        transport.response = HttpResponse(
            200,
            """
            {"total":2,"next":"https://api.spotify.com/v1/me/playlists?offset=50","items":[
              {"id":"p1","name":"Evergreen","owner":{"id":"me"},"public":false},
              {"id":"p2","name":"Someone else's","owner":{"id":"them"},"public":true}
            ]}
            """.trimIndent(),
            null,
        )

        val result = api.myPlaylists() as ApiResult.Ok

        assertEquals(2, result.value.items.size)
        assertEquals("Evergreen", result.value.items[0].name)
        assertEquals("me", result.value.items[0].ownerId)
        assertEquals(false, result.value.items[0].isPublic)
        assertEquals("them", result.value.items[1].ownerId)
        assertEquals("https://api.spotify.com/v1/me/playlists?offset=50", result.value.next)
    }

    // --- Failures behave like the like sync's ---------------------------------

    /**
     * The playlist writes are the calls most likely to meet the unpublished
     * per-account quota, so they must back off exactly as the like sync does.
     */
    @Test
    fun aRateLimitOnAWriteBacksOff() {
        transport.response = HttpResponse(429, """{"error":{"reason":"QUOTA_EXCEEDED"}}""", 30)

        val result = api.addToPlaylist("abc", listOf("spotify:track:a"))

        val limited = result as ApiResult.RateLimited
        assertTrue(limited.quotaExceeded)
        assertTrue("a quota block should back off for hours", limited.retryAfterMs > 60 * 60 * 1000)
    }

    @Test
    fun aPlainRateLimitRespectsRetryAfter() {
        transport.response = HttpResponse(429, """{"error":{"status":429}}""", 30)

        val limited = api.addToPlaylist("abc", listOf("spotify:track:a")) as ApiResult.RateLimited

        assertTrue(!limited.quotaExceeded)
        assertTrue(limited.retryAfterMs >= 30_000)
    }

    @Test
    fun aMissingPlaylistIsNotFoundRatherThanAFailure() {
        transport.response = HttpResponse(404, """{"error":{"status":404}}""", null)

        assertEquals(ApiResult.NotFound, api.playlistItems("gone"))
    }

    /** A write that succeeds with an empty body is a success, not a parse failure. */
    @Test
    fun anEmptyBodyOnASuccessfulWriteIsStillASuccess() {
        transport.response = HttpResponse(200, "", null)

        val result = api.removeFromPlaylist("abc", listOf("spotify:track:a"))

        assertTrue(result.toString(), result is ApiResult.Ok)
    }
}
