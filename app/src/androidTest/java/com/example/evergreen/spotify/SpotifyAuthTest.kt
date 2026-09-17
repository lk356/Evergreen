package com.example.evergreen.spotify

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The token lifecycle, driven against a scripted transport.
 *
 * Instrumented because [TokenStore] and [PendingAuthStore] use the real Android
 * Keystore. The network is faked — these tests are about how the app reacts to
 * Spotify's answers, not about reaching Spotify.
 */
@RunWith(AndroidJUnit4::class)
class SpotifyAuthTest {

    private lateinit var context: Context
    private lateinit var tokenStore: TokenStore
    private lateinit var pendingStore: PendingAuthStore

    private val t0 = 1_700_000_000_000L
    private var clock = t0
    private val day = TimeUnit.DAYS.toMillis(1)

    /** Records what was asked for and replies with whatever the test scripted. */
    private class FakeTransport : HttpTransport {
        var response: HttpResponse = HttpResponse(200, "{}", null)
        var throwIo = false
        val calls = mutableListOf<Map<String, String>>()

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            form: Map<String, String>?,
        ): HttpResponse {
            // Even the fake enforces the allowlist, so a test cannot
            // accidentally bless a URL the real transport would refuse.
            SpotifyHosts.require(url)
            calls += form.orEmpty()
            if (throwIo) throw IOException("network down")
            return response
        }
    }

    private val transport = FakeTransport()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tokenStore = TokenStore(context)
        pendingStore = PendingAuthStore(context)
        tokenStore.clear(expired = false)
        pendingStore.clear()
        clock = t0
        transport.throwIo = false
        transport.calls.clear()
    }

    private fun auth() = SpotifyAuth(
        context = context,
        tokenStore = tokenStore,
        transport = transport,
        pendingStore = pendingStore,
        now = { clock },
    )

    private fun storeTokens(
        accessExpiresAtMs: Long = t0 + TimeUnit.HOURS.toMillis(1),
        authorizedAtMs: Long = t0,
    ) = tokenStore.save(
        SpotifyTokens(
            accessToken = "access-1",
            refreshToken = "refresh-1",
            accessTokenExpiresAtMs = accessExpiresAtMs,
            authorizedAtMs = authorizedAtMs,
            grantedScopes = "user-library-read",
        ),
    )

    // --- Refresh token expiry ----------------------------------------------

    /**
     * The behaviour Phase 2 exists to get right: when the six-month refresh
     * token dies, Spotify answers 400 `invalid_grant`. The app must discard the
     * tokens and surface a reconnect prompt — not retry, and not fail silently.
     */
    @Test
    fun invalidGrantSurfacesTheReconnectStateInsteadOfRetrying() {
        storeTokens(accessExpiresAtMs = t0 - 1)
        transport.response = HttpResponse(400, """{"error":"invalid_grant"}""", null)
        clock = t0 + 30 * day

        val token = auth().accessToken()

        assertNull("no access token can be produced", token)
        assertEquals("exactly one attempt, never a retry loop", 1, transport.calls.size)

        val state = tokenStore.connectionState(clock)
        assertTrue("must be Expired, not NotConnected", state is SpotifyConnectionState.Expired)
        assertNull("the dead tokens are discarded", tokenStore.load())
    }

    /**
     * Past the deadline the app knows without asking, because the clock runs
     * from authorization. It should not spend a request to be told.
     */
    @Test
    fun anExpiredDeadlineIsDetectedWithoutCallingSpotify() {
        storeTokens(accessExpiresAtMs = t0 - 1)
        clock = t0 + 181 * day

        val token = auth().accessToken()

        assertNull(token)
        assertEquals("no request should have been made", 0, transport.calls.size)
        assertTrue(tokenStore.connectionState(clock) is SpotifyConnectionState.Expired)
    }

    /**
     * A network failure is not an expiry. Losing signal must not log the user
     * out.
     */
    @Test
    fun aNetworkFailureDoesNotDiscardTheTokens() {
        storeTokens(accessExpiresAtMs = t0 - 1)
        transport.throwIo = true
        clock = t0 + 30 * day

        assertNull(auth().accessToken())

        assertTrue("the connection is still live", tokenStore.load() != null)
        assertTrue(tokenStore.connectionState(clock) is SpotifyConnectionState.Connected)
    }

    // --- Refresh ------------------------------------------------------------

    /**
     * The subtle one. Spotify measures the six months from the *original*
     * authorization, so a refresh must not restart the clock. Getting this
     * wrong would make the app believe a dead connection is healthy and never
     * prompt for a reconnect.
     */
    @Test
    fun refreshingDoesNotRestartTheSixMonthClock() {
        storeTokens(accessExpiresAtMs = t0 - 1, authorizedAtMs = t0)
        transport.response = HttpResponse(
            200,
            """{"access_token":"access-2","expires_in":3600,"scope":"user-library-read"}""",
            null,
        )
        clock = t0 + 100 * day

        assertEquals("access-2", auth().accessToken())

        val stored = tokenStore.load()!!
        assertEquals("the authorization time is carried forward", t0, stored.authorizedAtMs)
        assertEquals(
            "so the deadline stays where it was",
            t0 + 180 * day,
            stored.refreshTokenExpiresAtMs,
        )
    }

    /** A refresh response may omit refresh_token, meaning "keep the one you have". */
    @Test
    fun aRefreshThatOmitsTheRefreshTokenKeepsTheExistingOne() {
        storeTokens(accessExpiresAtMs = t0 - 1)
        transport.response = HttpResponse(200, """{"access_token":"access-2","expires_in":3600}""", null)
        clock = t0 + day

        auth().accessToken()

        assertEquals("refresh-1", tokenStore.load()!!.refreshToken)
    }

    @Test
    fun aRotatedRefreshTokenIsStored() {
        storeTokens(accessExpiresAtMs = t0 - 1)
        transport.response = HttpResponse(
            200,
            """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}""",
            null,
        )
        clock = t0 + day

        auth().accessToken()

        assertEquals("refresh-2", tokenStore.load()!!.refreshToken)
    }

    /** A still-fresh access token must not cost a request. */
    @Test
    fun aFreshAccessTokenIsReusedWithoutCallingSpotify() {
        storeTokens(accessExpiresAtMs = t0 + TimeUnit.HOURS.toMillis(1))
        clock = t0

        assertEquals("access-1", auth().accessToken())
        assertEquals(0, transport.calls.size)
    }

    /** No client secret may appear in a token request. */
    @Test
    fun tokenRequestsCarryNoClientSecret() {
        storeTokens(accessExpiresAtMs = t0 - 1)
        transport.response = HttpResponse(200, """{"access_token":"a","expires_in":3600}""", null)
        clock = t0 + day

        auth().accessToken()

        assertTrue(transport.calls.single().keys.none { it.contains("secret") })
        assertEquals("refresh_token", transport.calls.single()["grant_type"])
    }

    // --- The redirect -------------------------------------------------------

    /**
     * A redirect whose `state` does not match the attempt this app started is
     * refused. That covers a stale browser tab and a forged redirect alike.
     */
    @Test
    fun aRedirectWithTheWrongStateIsRefused() {
        pendingStore.save(PendingAuth(verifier = "v", state = "expected-state", startedAtMs = t0))

        val result = auth().completeAuthorization(
            Uri.parse("com.example.evergreen://auth?code=abc&state=attacker-state"),
        )

        assertTrue(result is AuthResult.Failed)
        assertEquals("no code was redeemed", 0, transport.calls.size)
    }

    /** A redirect arriving when no login was started has nothing to match. */
    @Test
    fun aRedirectWithNoLoginInProgressIsRefused() {
        val result = auth().completeAuthorization(
            Uri.parse("com.example.evergreen://auth?code=abc&state=whatever"),
        )

        assertTrue(result is AuthResult.Failed)
        assertEquals(0, transport.calls.size)
    }

    /**
     * The pending attempt is single-use, so a redirect replayed after a
     * successful login cannot be redeemed a second time.
     */
    @Test
    fun aReplayedRedirectIsRefused() {
        pendingStore.save(PendingAuth(verifier = "v", state = "s", startedAtMs = t0))
        transport.response = HttpResponse(
            200,
            """{"access_token":"a","refresh_token":"r","expires_in":3600}""",
            null,
        )
        val redirect = Uri.parse("com.example.evergreen://auth?code=abc&state=s")

        assertTrue(auth().completeAuthorization(redirect) is AuthResult.Success)
        assertTrue(auth().completeAuthorization(redirect) is AuthResult.Failed)
        assertEquals("only the first redirect reached Spotify", 1, transport.calls.size)
    }

    @Test
    fun aSuccessfulExchangeSendsTheVerifierAndStoresTokens() {
        pendingStore.save(PendingAuth(verifier = "the-verifier", state = "s", startedAtMs = t0))
        transport.response = HttpResponse(
            200,
            """{"access_token":"a","refresh_token":"r","expires_in":3600,"scope":"user-library-read"}""",
            null,
        )

        val result = auth().completeAuthorization(
            Uri.parse("com.example.evergreen://auth?code=abc&state=s"),
        )

        assertTrue(result is AuthResult.Success)
        val sent = transport.calls.single()
        assertEquals("authorization_code", sent["grant_type"])
        assertEquals("the-verifier", sent["code_verifier"])
        assertEquals("abc", sent["code"])
        assertTrue(sent.keys.none { it.contains("secret") })

        val stored = tokenStore.load()!!
        assertEquals("r", stored.refreshToken)
        assertEquals(t0, stored.authorizedAtMs)
    }

    /** The user pressing "cancel" on Spotify's consent screen is not an error. */
    @Test
    fun aDeniedConsentReadsAsCancelled() {
        pendingStore.save(PendingAuth(verifier = "v", state = "s", startedAtMs = t0))

        val result = auth().completeAuthorization(
            Uri.parse("com.example.evergreen://auth?error=access_denied&state=s"),
        )

        assertEquals(AuthResult.Cancelled, result)
    }

    /** A login left open for a day should not still be completable. */
    @Test
    fun aStalePendingLoginExpires() {
        pendingStore.save(PendingAuth(verifier = "v", state = "s", startedAtMs = t0))

        assertNull(pendingStore.load(nowMs = t0 + TimeUnit.HOURS.toMillis(2)))
    }

    @Test
    fun disconnectingClearsEverything() {
        storeTokens()
        pendingStore.save(PendingAuth(verifier = "v", state = "s", startedAtMs = t0))

        auth().disconnect()

        assertNull(tokenStore.load())
        assertNull(pendingStore.load())
        assertEquals(SpotifyConnectionState.NotConnected, tokenStore.connectionState(clock))
    }
}
