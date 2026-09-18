package com.example.evergreen.spotify

import java.util.concurrent.TimeUnit

/**
 * What Spotify hands back after a successful login, plus the one thing it does
 * not hand back: when the authorization happened.
 *
 * That timestamp matters because the refresh token's six-month life is measured
 * from the user's *original authorization*, not from the last refresh — so it
 * cannot be derived from any later response and has to be carried forward
 * ourselves. See [refreshTokenExpiresAtMs].
 *
 * Verified against developer.spotify.com/blog/2026-06-18-refresh-token-expiration
 * on 2026-09-17.
 */
data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String,

    /** When [accessToken] stops working. Typically an hour after it was issued. */
    val accessTokenExpiresAtMs: Long,

    /**
     * When the user last granted access — the start of the six-month clock.
     *
     * Carried unchanged through every refresh. A refresh that returns a new
     * refresh token does **not** restart it; only a fresh login does.
     */
    val authorizedAtMs: Long,

    /** The scopes Spotify actually granted, which may differ from those asked for. */
    val grantedScopes: String,
) {
    /**
     * The deadline after which refreshing fails and the user must log in again.
     *
     * Computed rather than stored so that a change in Spotify's policy is a
     * one-line edit instead of a data migration.
     */
    val refreshTokenExpiresAtMs: Long
        get() = authorizedAtMs + REFRESH_TOKEN_LIFETIME_MS

    fun accessTokenIsFresh(nowMs: Long, marginMs: Long = ACCESS_TOKEN_REFRESH_MARGIN_MS): Boolean =
        nowMs < accessTokenExpiresAtMs - marginMs

    companion object {
        /** Six months, as announced for tokens issued from June 2026 onwards. */
        val REFRESH_TOKEN_LIFETIME_MS: Long = TimeUnit.DAYS.toMillis(180)

        /**
         * Refresh this long before the access token actually expires, so a sync
         * that starts just under the wire does not fail mid-flight.
         */
        val ACCESS_TOKEN_REFRESH_MARGIN_MS: Long = TimeUnit.MINUTES.toMillis(5)

        /** How long before the refresh deadline the UI starts warning. */
        val RECONNECT_WARNING_MS: Long = TimeUnit.DAYS.toMillis(14)
    }
}

/**
 * Whether Spotify is usable right now, and if not, why.
 *
 * "Never connected" and "connection expired" are deliberately separate states.
 * They need different words in the UI — one is an invitation, the other is a
 * six-monthly chore — and conflating them was the failure mode this phase was
 * asked to avoid: an app that silently stops syncing and never says so.
 */
sealed interface SpotifyConnectionState {

    /** No tokens stored. First run, or the user disconnected. */
    data object NotConnected : SpotifyConnectionState

    /** Working normally. */
    data class Connected(
        val authorizedAtMs: Long,
        val refreshTokenExpiresAtMs: Long,
        val grantedScopes: String,
    ) : SpotifyConnectionState

    /**
     * Still working, but the six-month deadline is close enough to mention.
     *
     * This state exists only because the deadline is knowable in advance: the
     * clock starts at authorization, so the app can ask for a reconnect at a
     * convenient moment rather than after sync has already stopped.
     */
    data class ExpiringSoon(
        val authorizedAtMs: Long,
        val refreshTokenExpiresAtMs: Long,
        val grantedScopes: String,
    ) : SpotifyConnectionState

    /**
     * The refresh token is dead and the user must log in again.
     *
     * Reached when Spotify answers a refresh with HTTP 400 `invalid_grant`, or
     * when the stored deadline has simply passed. Spotify's guidance is
     * explicit: discard the tokens and send the user back through login. Do not
     * retry — which is why scheduled work is cancelled on entering this state
     * rather than left to hammer a token endpoint that will never say yes.
     */
    data class Expired(val expiredAtMs: Long) : SpotifyConnectionState
}
