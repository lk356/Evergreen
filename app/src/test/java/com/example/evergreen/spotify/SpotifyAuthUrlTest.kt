package com.example.evergreen.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * Guards the properties of the login request that must never regress.
 *
 * These are the ones where a mistake is invisible in testing but security
 * relevant: a downgrade to `plain`, a missing `state`, a scope quietly widening,
 * or a client secret appearing where a public client must not have one.
 */
class SpotifyAuthUrlTest {

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { pair ->
            val (k, v) = pair.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    private fun url() = authorizeUrl(
        clientId = "test-client-id",
        redirectUri = "com.example.evergreen://auth",
        verifier = "test-verifier",
        state = "test-state",
    )

    @Test
    fun theAuthorizeRequestGoesToSpotifyOverHttps() {
        assertTrue(url().startsWith("https://accounts.spotify.com/authorize?"))
    }

    /**
     * `plain` sends the verifier itself and defeats the entire scheme. There
     * must be no code path that produces it.
     */
    @Test
    fun theChallengeMethodIsAlwaysS256() {
        assertEquals("S256", params(url())["code_challenge_method"])
    }

    @Test
    fun theChallengeIsTheSha256OfTheVerifier() {
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("test-verifier".toByteArray()),
        )
        assertEquals(expected, params(url())["code_challenge"])
    }

    /** The verifier must never travel to the browser. */
    @Test
    fun theVerifierIsNotSentInTheAuthorizeRequest() {
        assertFalse(url().contains("test-verifier"))
    }

    @Test
    fun aStateIsAlwaysSent() {
        assertEquals("test-state", params(url())["state"])
    }

    /**
     * A public client has no secret to send. If one ever appears here it means
     * someone has mistaken this for a server-side flow.
     */
    @Test
    fun noClientSecretIsEverSent() {
        val url = url()
        assertFalse(url.contains("client_secret"))
        assertEquals("code", params(url)["response_type"])
    }

    /**
     * Phase 2 reads likes and does nothing else. A widened scope should fail
     * here and be a deliberate decision, not a drift.
     */
    @Test
    fun onlyTheLibraryReadScopeIsRequested() {
        // Phase 3 added the two playlist scopes, so the authorize URL now
        // carries three. The exact set, and the long list of scopes this app
        // deliberately never asks for, are pinned in SpotifyScopesTest; what
        // matters here is that the URL sends exactly what was requested and
        // nothing is added on the way.
        assertEquals(
            "user-library-read playlist-modify-private playlist-read-private",
            params(url())["scope"],
        )
        assertEquals(SpotifyScopes.asParameter, params(url())["scope"])
    }

    @Test
    fun theRedirectUriIsSentExactly() {
        assertEquals("com.example.evergreen://auth", params(url())["redirect_uri"])
    }
}

/**
 * The PKCE values themselves.
 */
class PkceTest {

    @Test
    fun verifiersAreInTheLengthRangeTheSpecAllows() {
        val verifier = Pkce.newVerifier()
        assertTrue("RFC 7636 allows 43-128 characters", verifier.length in 43..128)
    }

    @Test
    fun verifiersUseOnlyUnreservedCharacters() {
        val allowed = Regex("^[A-Za-z0-9\\-._~]+$")
        repeat(20) {
            assertTrue(allowed.matches(Pkce.newVerifier()))
        }
    }

    /** A reused verifier would let one intercepted code unlock another login. */
    @Test
    fun everyVerifierIsDifferent() {
        val seen = List(50) { Pkce.newVerifier() }.toSet()
        assertEquals(50, seen.size)
    }

    @Test
    fun everyStateIsDifferent() {
        val seen = List(50) { Pkce.newState() }.toSet()
        assertEquals(50, seen.size)
    }

    @Test
    fun theChallengeIsNotTheVerifier() {
        val verifier = Pkce.newVerifier()
        assertNotEquals(verifier, Pkce.challengeOf(verifier))
    }

    @Test
    fun theChallengeIsStableForAGivenVerifier() {
        val verifier = Pkce.newVerifier()
        assertEquals(Pkce.challengeOf(verifier), Pkce.challengeOf(verifier))
    }

    /** Base64url, unpadded — a '+' or '=' would be rejected by Spotify. */
    @Test
    fun theChallengeIsUnpaddedBase64Url() {
        val challenge = Pkce.challengeOf(Pkce.newVerifier())
        assertTrue(Regex("^[A-Za-z0-9\\-_]+$").matches(challenge))
        assertEquals(43, challenge.length)
    }
}
