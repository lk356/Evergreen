package com.example.evergreen.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 11 of `docs/PHASE3_PLAN.md` section 9: exactly `user-library-read`,
 * `playlist-modify-private`, `playlist-read-private`.
 *
 * Spelled out as an exact set rather than a "contains" check. A scope is a
 * standing permission over the user's whole Spotify account, so the list
 * growing must be a deliberate edit with this test to update, never a line
 * added in passing.
 */
class SpotifyScopesTest {

    @Test
    fun `the requested scopes are exactly the three Phase 3 needs`() {
        assertEquals(
            listOf(
                "user-library-read",
                "playlist-modify-private",
                "playlist-read-private",
            ),
            SpotifyScopes.requested,
        )
    }

    @Test
    fun `the scope parameter is space separated, as the spec requires`() {
        assertEquals(
            "user-library-read playlist-modify-private playlist-read-private",
            SpotifyScopes.asParameter,
        )
    }

    /**
     * The things this app has deliberately never asked for. Named individually
     * so a failure says which one crept in.
     */
    @Test
    fun `no scope beyond reading the library and the private playlist is requested`() {
        val forbidden = listOf(
            // Likes are made with Spotify's own button; this app only reads them.
            "user-library-modify",
            // Evergreen is created private and stays private.
            "playlist-modify-public",
            "playlist-read-collaborative",
            // The app never needs to know who the user is.
            "user-read-private",
            "user-read-email",
            "user-top-read",
            "user-read-recently-played",
            // Capture reads media sessions on the device, not Spotify's API.
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "streaming",
            "ugc-image-upload",
            "user-follow-read",
            "user-follow-modify",
        )

        forbidden.forEach { scope ->
            assertFalse("must not request $scope", scope in SpotifyScopes.requested)
        }
    }

    @Test
    fun `no scope is requested twice or left blank`() {
        assertEquals(SpotifyScopes.requested.size, SpotifyScopes.requested.toSet().size)
        assertTrue(SpotifyScopes.requested.none { it.isBlank() })
    }

    // --- Recognising an old grant --------------------------------------------

    /**
     * A connection made before Phase 3 is still valid for reading likes but
     * cannot write the playlist. The difference has to be visible up front,
     * rather than arriving as a 403 inside a background worker.
     */
    @Test
    fun `a Phase 2 grant does not cover playlist writes`() {
        assertFalse(SpotifyScopes.coversPlaylistWrites("user-library-read"))
    }

    @Test
    fun `a full grant covers playlist writes`() {
        assertTrue(
            SpotifyScopes.coversPlaylistWrites(
                "user-library-read playlist-modify-private playlist-read-private",
            ),
        )
    }

    @Test
    fun `either playlist scope alone is not enough`() {
        assertFalse(
            SpotifyScopes.coversPlaylistWrites("user-library-read playlist-modify-private"),
        )
        assertFalse(
            SpotifyScopes.coversPlaylistWrites("user-library-read playlist-read-private"),
        )
    }

    @Test
    fun `a missing or empty grant does not cover playlist writes`() {
        assertFalse(SpotifyScopes.coversPlaylistWrites(null))
        assertFalse(SpotifyScopes.coversPlaylistWrites(""))
        assertFalse(SpotifyScopes.coversPlaylistWrites("   "))
    }

    /** Spotify returns the granted scopes space separated, in any order. */
    @Test
    fun `the order Spotify reports the granted scopes in does not matter`() {
        assertTrue(
            SpotifyScopes.coversPlaylistWrites(
                "playlist-read-private  playlist-modify-private   user-library-read",
            ),
        )
    }

    /** What the app asks for must itself be enough to do the job. */
    @Test
    fun `the requested set covers playlist writes`() {
        assertTrue(SpotifyScopes.coversPlaylistWrites(SpotifyScopes.asParameter))
    }
}
