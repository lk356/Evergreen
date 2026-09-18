package com.example.evergreen.spotify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Exercises [TokenStore] against the real Android Keystore.
 *
 * Instrumented rather than a JVM test because the thing under test is the
 * platform Keystore. Mocking it would prove only that the mock works.
 */
@RunWith(AndroidJUnit4::class)
class TokenStoreTest {

    private lateinit var store: TokenStore
    private lateinit var context: Context

    private val authorizedAt = 1_700_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = TokenStore(context)
        store.clear(expired = false)
    }

    private fun tokens(
        access: String = "BQD-fake-access-token",
        refresh: String = "AQD-fake-refresh-token",
        accessExpiresAtMs: Long = authorizedAt + TimeUnit.HOURS.toMillis(1),
    ) = SpotifyTokens(
        accessToken = access,
        refreshToken = refresh,
        accessTokenExpiresAtMs = accessExpiresAtMs,
        authorizedAtMs = authorizedAt,
        grantedScopes = "user-library-read",
    )

    @Test
    fun tokensRoundTripThroughTheKeystore() {
        val original = tokens()
        store.save(original)

        // A fresh instance, so the read goes through the Keystore rather than
        // any in-memory copy left behind by the write.
        val reloaded = TokenStore(context).load()

        assertEquals(original, reloaded)
    }

    /**
     * The point of encrypting at all: the token must not be sitting in the
     * preferences file as readable text.
     */
    @Test
    fun storedBytesDoNotContainTheTokenInClear() {
        store.save(tokens(access = "BQD-distinctive-access", refresh = "AQD-distinctive-refresh"))

        val raw = context
            .getSharedPreferences("spotify_tokens", Context.MODE_PRIVATE)
            .all
            .toString()

        assertFalse("access token leaked in clear", raw.contains("BQD-distinctive-access"))
        assertFalse("refresh token leaked in clear", raw.contains("AQD-distinctive-refresh"))
        assertTrue("something was actually stored", raw.contains("tokens"))
    }

    @Test
    fun clearingRemovesTheTokens() {
        store.save(tokens())
        store.clear(expired = false)

        assertNull(store.load())
        assertEquals(SpotifyConnectionState.NotConnected, store.connectionState())
    }

    @Test
    fun freshInstallIsNotConnected() {
        assertEquals(SpotifyConnectionState.NotConnected, store.connectionState())
    }

    @Test
    fun aLiveConnectionReportsConnected() {
        store.save(tokens())

        val state = store.connectionState(nowMs = authorizedAt + 30 * day)

        assertTrue(state is SpotifyConnectionState.Connected)
        assertEquals(
            authorizedAt + TimeUnit.DAYS.toMillis(180),
            (state as SpotifyConnectionState.Connected).refreshTokenExpiresAtMs,
        )
    }

    /**
     * The six-month clock runs from authorization, so the app can warn before
     * anything breaks rather than after sync has silently stopped.
     */
    @Test
    fun theDeadlineIsAnnouncedBeforeItArrives() {
        store.save(tokens())

        val state = store.connectionState(nowMs = authorizedAt + 175 * day)

        assertTrue("175 days in should be a warning, not a failure", state is SpotifyConnectionState.ExpiringSoon)
    }

    /**
     * Expiry is a state, not an error: past the deadline the store says so
     * without any network call having been attempted.
     */
    @Test
    fun pastTheDeadlineTheConnectionReportsExpired() {
        store.save(tokens())

        val state = store.connectionState(nowMs = authorizedAt + 181 * day)

        assertTrue(state is SpotifyConnectionState.Expired)
    }

    /**
     * "Never connected" and "connection expired" must not collapse into one
     * state once the tokens are gone — they need different words in the UI.
     */
    @Test
    fun expiryIsDistinguishableFromNeverHavingConnected() {
        store.save(tokens())
        store.clear(expired = true, nowMs = authorizedAt + 181 * day)

        val state = store.connectionState()

        assertTrue(state is SpotifyConnectionState.Expired)
        assertEquals(authorizedAt + 181 * day, (state as SpotifyConnectionState.Expired).expiredAtMs)
    }

    /** Logging back in after an expiry must clear the expired marker. */
    @Test
    fun reconnectingClearsTheExpiredMarker() {
        store.clear(expired = true, nowMs = authorizedAt)
        store.save(tokens())

        assertTrue(
            store.connectionState(nowMs = authorizedAt + day) is SpotifyConnectionState.Connected,
        )
    }

    /**
     * Ciphertext that cannot be opened — a Keystore key lost to a device
     * transfer, say — must read as "not connected" rather than crash the app on
     * launch.
     */
    @Test
    fun undecryptableStorageDegradesToNotConnected() {
        store.save(tokens())
        context.getSharedPreferences("spotify_tokens", Context.MODE_PRIVATE)
            .edit()
            .putString("tokens", "AQIDBAUGBwgJCgsMDQ4PEA==")
            .commit()

        assertNull(TokenStore(context).load())
        assertEquals(SpotifyConnectionState.NotConnected, TokenStore(context).connectionState())
    }

    @Test
    fun accessTokenFreshnessLeavesAMarginBeforeExpiry() {
        val t = tokens(accessExpiresAtMs = authorizedAt + TimeUnit.HOURS.toMillis(1))

        assertTrue(t.accessTokenIsFresh(authorizedAt))
        assertFalse(
            "a token expiring in two minutes is not fresh enough to start a sync",
            t.accessTokenIsFresh(authorizedAt + TimeUnit.MINUTES.toMillis(58)),
        )
    }
}
