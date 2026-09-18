package com.example.evergreen.spotify

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.evergreen.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * The scopes Evergreen asks for, and nothing else.
 *
 * - `user-library-read` ("Read access to a user's library") is all that
 *   `GET /me/tracks` requires, and reading likes is all Phase 2 does.
 * - `playlist-modify-private` lets Phase 3 create the private Evergreen
 *   playlist and add and remove tracks in it.
 * - `playlist-read-private` lets it read the playlist back before each write,
 *   so the diff is computed against what is really there. Decision 10.2 chose
 *   this over trusting the app's own membership table: two requests a day buys
 *   a sync that heals itself when the playlist is edited elsewhere, instead of
 *   drifting silently.
 *
 * Deliberately not requested:
 * - `playlist-modify-public` — Evergreen is created private and stays private.
 * - `user-library-modify` — likes are made with Spotify's own button; this app
 *   only reads them.
 * - `user-read-private` / `user-read-email` — the app never needs to know who
 *   the user is, and February 2026 removed most profile fields anyway.
 *
 * **Adding the two playlist scopes forces a reconnect**, because a granted
 * token cannot be widened. The consent screen will also describe more than
 * these scopes cover; Phase 2 saw the same thing with `user-library-read`, and
 * the token response is what says what was actually granted.
 *
 * Verified against developer.spotify.com/documentation/web-api/concepts/scopes
 * on 2026-09-17, and each playlist endpoint's own reference page re-checked on
 * 2026-09-18.
 */
object SpotifyScopes {
    const val USER_LIBRARY_READ = "user-library-read"
    const val PLAYLIST_MODIFY_PRIVATE = "playlist-modify-private"
    const val PLAYLIST_READ_PRIVATE = "playlist-read-private"

    val requested = listOf(USER_LIBRARY_READ, PLAYLIST_MODIFY_PRIVATE, PLAYLIST_READ_PRIVATE)

    val asParameter: String get() = requested.joinToString(" ")

    /**
     * True when a stored grant covers everything Phase 3 needs.
     *
     * A connection made before the playlist scopes existed is still valid for
     * reading likes, so it is not an error — but it cannot write the playlist,
     * and the difference has to be visible rather than surfacing as a 403 in a
     * background worker.
     */
    fun coversPlaylistWrites(grantedScopes: String?): Boolean {
        val granted = grantedScopes?.split(' ')?.filter { it.isNotBlank() }?.toSet().orEmpty()
        return PLAYLIST_MODIFY_PRIVATE in granted && PLAYLIST_READ_PRIVATE in granted
    }
}

/**
 * Builds the authorize URL.
 *
 * Separated from [SpotifyAuth] and free of Android types so the parts that must
 * never regress -- S256 rather than `plain`, a `state` always present, the
 * minimum scope, no client secret -- can be asserted by a plain JVM test.
 *
 * GET https://accounts.spotify.com/authorize
 * Verified 2026-09-17 against
 * developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
 */
internal fun authorizeUrl(
    clientId: String,
    redirectUri: String,
    verifier: String,
    state: String,
): String {
    val params = linkedMapOf(
        "client_id" to clientId,
        "response_type" to "code",
        "redirect_uri" to redirectUri,
        "code_challenge_method" to Pkce.CHALLENGE_METHOD,
        "code_challenge" to Pkce.challengeOf(verifier),
        "state" to state,
        "scope" to SpotifyScopes.asParameter,
    )
    val query = params.entries.joinToString("&") { (k, v) ->
        "$k=${URLEncoder.encode(v, "UTF-8")}"
    }
    return "https://${SpotifyHosts.ACCOUNTS}/authorize?$query"
}

/** Where the login flow ended up. */
sealed interface AuthResult {
    data object Success : AuthResult
    data object Cancelled : AuthResult
    data class Failed(val reason: String) : AuthResult
}

/**
 * The hand-written Authorization Code with PKCE flow.
 *
 * About 200 lines instead of a third-party library, chosen because the protocol
 * is settled and unlikely to move, while an unmaintained dependency with network
 * reach is a liability that grows with neglect. What *does* change is Spotify's
 * endpoints — three times between February and July 2026 — and no library
 * protects against that.
 *
 * Every endpoint below records the reference page it was checked against and the
 * date, so re-verifying is a few minutes' work rather than a re-derivation.
 */
class SpotifyAuth(
    context: Context,
    private val tokenStore: TokenStore = TokenStore(context),
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val pendingStore: PendingAuthStore = PendingAuthStore(context),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** False when local.properties has no `spotify.clientId`. */
    val isConfigured: Boolean get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()

    fun connectionState(): SpotifyConnectionState = tokenStore.connectionState(now())

    /**
     * Builds the URL to open in the browser, and remembers what this attempt
     * needs in order to be completed.
     *
     * The verifier is stored encrypted rather than held only in memory: the
     * system can kill this process while the user is typing their password, and
     * a verifier lost that way turns a successful login into an error the user
     * cannot explain.
     */
    fun beginAuthorization(): Uri {
        check(isConfigured) { "no Spotify client ID configured" }

        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        pendingStore.save(PendingAuth(verifier = verifier, state = state, startedAtMs = now()))

        return Uri.parse(
            authorizeUrl(
                clientId = BuildConfig.SPOTIFY_CLIENT_ID,
                redirectUri = BuildConfig.SPOTIFY_REDIRECT_URI,
                verifier = verifier,
                state = state,
            ),
        )
    }

    /**
     * Completes a login from the redirect the browser delivered.
     *
     * Rejects anything that does not match the attempt this app started. The
     * `state` check is single-use: the pending attempt is cleared before the
     * network call, so a redirect replayed later finds nothing to match against.
     */
    fun completeAuthorization(redirect: Uri): AuthResult {
        // Same clock as the rest of this class, so the staleness check cannot
        // disagree with the timestamp the attempt was saved with.
        val pending = pendingStore.load(now())
        pendingStore.clear()

        if (pending == null) {
            return AuthResult.Failed("no login was in progress")
        }

        val returnedState = redirect.getQueryParameter("state")
        if (returnedState == null || !constantTimeEquals(returnedState, pending.state)) {
            // The most likely cause is a stale browser tab, but it is also what
            // a forged redirect looks like, so it is refused either way.
            return AuthResult.Failed("login response did not match the request")
        }

        redirect.getQueryParameter("error")?.let { error ->
            return if (error == "access_denied") {
                AuthResult.Cancelled
            } else {
                // Spotify's error codes are a fixed vocabulary, safe to surface.
                AuthResult.Failed("Spotify refused the login ($error)")
            }
        }

        val code = redirect.getQueryParameter("code")
            ?: return AuthResult.Failed("login response carried no code")

        return exchangeCode(code, pending.verifier)
    }

    private fun exchangeCode(code: String, verifier: String): AuthResult {
        // POST https://accounts.spotify.com/api/token
        // Verified 2026-09-17 against the code-pkce-flow tutorial. No client
        // secret: that is the point of PKCE and is not negotiable for a mobile
        // app, which cannot keep one.
        val response = try {
            transport.execute(
                method = "POST",
                url = TOKEN_URL,
                form = mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to BuildConfig.SPOTIFY_REDIRECT_URI,
                    "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                    "code_verifier" to verifier,
                ),
            )
        } catch (e: IOException) {
            return AuthResult.Failed("could not reach Spotify")
        }

        if (response.code !in 200..299) {
            Log.w(TAG, "token exchange failed with HTTP ${response.code}")
            return AuthResult.Failed("Spotify rejected the login (HTTP ${response.code})")
        }

        val issuedAt = now()
        val tokens = try {
            parseTokenResponse(response.body, issuedAt = issuedAt, authorizedAtMs = issuedAt)
        } catch (e: Exception) {
            return AuthResult.Failed("Spotify's reply could not be read")
        } ?: return AuthResult.Failed("Spotify's reply contained no refresh token")

        tokenStore.save(tokens)
        return AuthResult.Success
    }

    /**
     * Returns a usable access token, refreshing it first if necessary.
     *
     * Null means "cannot proceed"; the connection state says why. A refresh that
     * comes back `invalid_grant` is the six-month expiry, and is handled by
     * discarding the tokens and marking the connection expired — never by
     * retrying, which Spotify's own guidance rules out and which would turn a
     * predictable annual chore into a background loop hammering an endpoint that
     * will never say yes.
     */
    fun accessToken(): String? {
        val tokens = tokenStore.load() ?: return null

        if (tokens.accessTokenIsFresh(now())) return tokens.accessToken

        if (now() >= tokens.refreshTokenExpiresAtMs) {
            // Knowable without asking. Save the request.
            tokenStore.clear(expired = true, nowMs = now())
            return null
        }

        val response = try {
            transport.execute(
                method = "POST",
                url = TOKEN_URL,
                form = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to tokens.refreshToken,
                    "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                ),
            )
        } catch (e: IOException) {
            // A network failure is not an expiry. Leave the tokens alone.
            return null
        }

        if (response.code == 400 && response.body.contains(INVALID_GRANT)) {
            Log.i(TAG, "refresh token expired; reconnect required")
            tokenStore.clear(expired = true, nowMs = now())
            return null
        }

        if (response.code !in 200..299) return null

        val refreshed = try {
            parseTokenResponse(
                body = response.body,
                issuedAt = now(),
                // The six-month clock does NOT restart on a refresh; it runs
                // from the original authorization. Carrying the old value
                // forward is what keeps the deadline honest.
                authorizedAtMs = tokens.authorizedAtMs,
                fallbackRefreshToken = tokens.refreshToken,
            )
        } catch (e: Exception) {
            return null
        } ?: return null

        tokenStore.save(refreshed)
        return refreshed.accessToken
    }

    /** Forgets the connection at the user's request. */
    fun disconnect() {
        tokenStore.clear(expired = false)
        pendingStore.clear()
    }

    /**
     * Called when a request comes back 401 despite a token we believed fresh —
     * a token revoked from spotify.com/account/apps, most likely.
     */
    fun invalidateAccessToken() {
        val tokens = tokenStore.load() ?: return
        tokenStore.save(tokens.copy(accessTokenExpiresAtMs = 0L))
    }

    private fun parseTokenResponse(
        body: String,
        issuedAt: Long,
        authorizedAtMs: Long,
        fallbackRefreshToken: String? = null,
    ): SpotifyTokens? {
        val json = JSONObject(body)
        val access = json.getString("access_token")
        // A refresh response may omit refresh_token, meaning "keep the one you
        // have". Treating that as a failure would break every refresh.
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: fallbackRefreshToken
            ?: return null
        val expiresIn = json.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS)

        return SpotifyTokens(
            accessToken = access,
            refreshToken = refresh,
            accessTokenExpiresAtMs = issuedAt + expiresIn * 1000,
            authorizedAtMs = authorizedAtMs,
            // What Spotify granted, which may be less than what was asked for.
            grantedScopes = json.optString("scope", SpotifyScopes.asParameter),
        )
    }

    /**
     * Compares without leaking, through timing, how much of the value matched.
     *
     * Almost certainly unnecessary for a `state` check, but it costs one line
     * and removes the need to reason about whether it is.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private companion object {
        const val TAG = "SpotifyAuth"
        const val TOKEN_URL = "https://${SpotifyHosts.ACCOUNTS}/api/token"
        const val INVALID_GRANT = "invalid_grant"
        const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
    }
}
