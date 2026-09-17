package com.example.evergreen.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The network guard rails.
 *
 * Android cannot express "this app may only contact these hosts" in the
 * manifest, so the restriction lives in [SpotifyHosts] and is asserted here.
 */
class SpotifyHostsTest {

    @Test
    fun theTwoSpotifyHostsAreAllowed() {
        SpotifyHosts.require("https://api.spotify.com/v1/me/tracks")
        SpotifyHosts.require("https://accounts.spotify.com/api/token")
    }

    @Test
    fun anyOtherHostIsRefused() {
        for (url in listOf(
            "https://example.test/collect",
            "https://analytics.google.com/collect",
            "https://spotify.com/v1/me",
        )) {
            assertThrows(SecurityException::class.java) { SpotifyHosts.require(url) }
        }
    }

    /**
     * A suffix check would pass this. The host comparison is exact for exactly
     * this reason.
     */
    @Test
    fun aLookalikeHostIsRefused() {
        assertThrows(SecurityException::class.java) {
            SpotifyHosts.require("https://api.spotify.com.evil.test/v1/me")
        }
        assertThrows(SecurityException::class.java) {
            SpotifyHosts.require("https://evil.test/api.spotify.com")
        }
    }

    @Test
    fun cleartextIsRefusedEvenToAnAllowedHost() {
        assertThrows(SecurityException::class.java) {
            SpotifyHosts.require("http://api.spotify.com/v1/me/tracks")
        }
    }

    @Test
    fun rubbishIsRefused() {
        assertThrows(SecurityException::class.java) { SpotifyHosts.require("not a url") }
        assertThrows(SecurityException::class.java) { SpotifyHosts.require("") }
    }
}

/**
 * Backing off when Spotify says to.
 *
 * Verified against developer.spotify.com/documentation/web-api/concepts/rate-limits
 * on 2026-09-17: a rolling 30 second window, and "the header of the 429 response
 * will normally include a Retry-After header with a value in seconds".
 */
class BackoffTest {

    @Test
    fun retryAfterIsReadAsSeconds() {
        assertEquals(30L, "30".toRetryAfterSeconds())
        assertEquals(1L, " 1 ".toRetryAfterSeconds())
    }

    @Test
    fun anUnparseableRetryAfterIsIgnoredRatherThanGuessed() {
        assertNull("Wed, 21 Oct 2026 07:28:00 GMT".toRetryAfterSeconds())
        assertNull("".toRetryAfterSeconds())
        assertNull("-5".toRetryAfterSeconds())
    }

    @Test
    fun aRetryAfterIsRespected() {
        assertEquals(
            TimeUnit.SECONDS.toMillis(42),
            Backoff.forRateLimit(retryAfterSeconds = 42, quotaExceeded = false),
        )
    }

    @Test
    fun withoutARetryAfterItWaitsOutTheRollingWindow() {
        val wait = Backoff.forRateLimit(retryAfterSeconds = null, quotaExceeded = false)
        assertTrue("must exceed Spotify's 30s window", wait >= TimeUnit.SECONDS.toMillis(30))
    }

    /**
     * A Retry-After of 0 is legal and means "now". Taken literally it turns a
     * rate limit into a tight loop.
     */
    @Test
    fun aZeroRetryAfterStillWaits() {
        assertTrue(Backoff.forRateLimit(retryAfterSeconds = 0, quotaExceeded = false) >= 1000L)
    }

    /**
     * QUOTA_EXCEEDED is not a burst problem; the allowance is spent and
     * retrying soon cannot fix it.
     */
    @Test
    fun quotaExceededBacksOffFarLongerThanARateLimit() {
        val quota = Backoff.forRateLimit(retryAfterSeconds = 1, quotaExceeded = true)
        val rateLimit = Backoff.forRateLimit(retryAfterSeconds = 1, quotaExceeded = false)

        assertTrue(quota > rateLimit)
        assertTrue("hours, not seconds", quota >= TimeUnit.HOURS.toMillis(1))
    }

    @Test
    fun anAbsurdRetryAfterIsCapped() {
        assertEquals(
            Backoff.MAX_MS,
            Backoff.forRateLimit(retryAfterSeconds = 999_999, quotaExceeded = false),
        )
    }
}
