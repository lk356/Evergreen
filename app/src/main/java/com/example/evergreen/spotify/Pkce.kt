package com.example.evergreen.spotify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The cryptographic half of Authorization Code with PKCE.
 *
 * PKCE ("pixie") is why this app ships no client secret. A secret compiled into
 * an APK is not a secret — anyone can unzip it — so instead the app invents a
 * fresh random value per login attempt, sends only a one-way hash of it when
 * starting the flow, and reveals the original when redeeming the code. Spotify
 * checks they match.
 *
 * That is also what makes a custom-scheme redirect acceptable. Another app could
 * in principle register `com.example.evergreen://` and intercept the redirect,
 * but the code it steals is worthless: redeeming it requires the verifier, which
 * never leaves this process and is never sent to the browser.
 *
 * Uses `java.util.Base64` rather than `android.util.Base64` so the whole file is
 * testable on the JVM without a device. Available from API 26; minSdk is 31.
 *
 * Verified against developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
 * on 2026-09-17.
 */
object Pkce {

    /**
     * RFC 7636 allows a verifier of 43-128 characters. 64 random bytes encode to
     * 86, comfortably inside the range and well past any brute-force concern.
     */
    private const val VERIFIER_BYTES = 64
    private const val STATE_BYTES = 32

    /** Base64url without padding, which is what the spec requires. */
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * A fresh code verifier.
     *
     * [SecureRandom] specifically, never [kotlin.random.Random] — the whole
     * scheme rests on this value being unguessable.
     */
    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        encoder.encodeToString(ByteArray(VERIFIER_BYTES).also(random::nextBytes))

    /**
     * The S256 challenge for a verifier: base64url(SHA-256(verifier)).
     *
     * Only S256. The spec also defines a `plain` method that sends the verifier
     * itself, which would defeat the entire point; there is deliberately no code
     * path here that can produce it.
     */
    fun challengeOf(verifier: String): String =
        encoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

    /**
     * A random `state` value.
     *
     * Carried through the browser and compared on return. It stops a redirect
     * this app never asked for from being accepted — someone handing the app a
     * code obtained under a different login.
     */
    fun newState(random: SecureRandom = SecureRandom()): String =
        encoder.encodeToString(ByteArray(STATE_BYTES).also(random::nextBytes))

    const val CHALLENGE_METHOD = "S256"
}
