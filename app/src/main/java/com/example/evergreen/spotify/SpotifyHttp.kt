package com.example.evergreen.spotify

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A response reduced to the three things this app reacts to.
 *
 * Nothing here is ever logged: a body can contain a token and a header can
 * contain anything Spotify decides to add later.
 */
data class HttpResponse(
    val code: Int,
    val body: String,
    val retryAfterSeconds: Long?,
)

/**
 * The seam that lets the 429 and refresh-expiry behaviour be tested on the JVM
 * without a device or a network.
 */
interface HttpTransport {
    /**
     * @param form a form-encoded body, as the token endpoint requires.
     * @param jsonBody a JSON body, as the playlist endpoints require. At most
     *   one of [form] and [jsonBody] may be given.
     */
    @Throws(IOException::class)
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        jsonBody: String? = null,
    ): HttpResponse
}

/**
 * The only two hosts this app may contact.
 *
 * Android has no manifest-level way to say "this app talks to these hosts and
 * nothing else", so the restriction is enforced here and asserted by a unit
 * test. Every request in the app goes through this check; a URL pointing
 * anywhere else throws rather than being sent.
 */
object SpotifyHosts {
    const val ACCOUNTS = "accounts.spotify.com"
    const val API = "api.spotify.com"

    val allowed = setOf(ACCOUNTS, API)

    /**
     * Throws unless [url] is HTTPS and addressed to an allowed host.
     *
     * Exact host match, not a suffix match: `api.spotify.com.evil.test` ends
     * with a permitted string and must still be refused.
     */
    fun require(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw SecurityException("unparseable URL", e)
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            throw SecurityException("refusing a non-HTTPS request")
        }
        val host = uri.host?.lowercase()
        if (host == null || host !in allowed) {
            // Deliberately does not echo the host, so a crafted URL cannot write
            // attacker-chosen text into a log.
            throw SecurityException("refusing a request to a host outside the Spotify allowlist")
        }
    }
}

/**
 * The real transport: `HttpURLConnection` from the JDK.
 *
 * No OkHttp, no Retrofit. Evergreen makes a few dozen requests a day, so the
 * ergonomics those libraries buy would cost more in third-party code with
 * network reach than they return — and "no third-party SDKs" is a standing rule
 * for this project.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : HttpTransport {

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        form: Map<String, String>?,
        jsonBody: String?,
    ): HttpResponse {
        SpotifyHosts.require(url)
        require(form == null || jsonBody == null) { "pass a form body or a JSON body, not both" }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            if (form != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded",
                )
                val encoded = form.entries.joinToString("&") { (k, v) ->
                    "${k.urlEncoded()}=${v.urlEncoded()}"
                }
                connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
            }

            if (jsonBody != null) {
                // Note that the playlist remove endpoint is a DELETE *with* a
                // body, which older Java HttpURLConnection implementations
                // refuse. Android's is backed by OkHttp and allows it; if this
                // ever starts throwing on a new platform version, that is the
                // cause and not the request being malformed.
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            // An error response's body arrives on errorStream, not inputStream,
            // and reading the wrong one throws instead of returning the reason.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            return HttpResponse(
                code = code,
                body = body,
                retryAfterSeconds = connection.getHeaderField("Retry-After")?.toRetryAfterSeconds(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")
}

/**
 * Parses a `Retry-After` header.
 *
 * Spotify documents it as a number of seconds, which is the only form observed.
 * A malformed or absent value returns null so the caller falls back to its own
 * backoff rather than treating "unparseable" as "retry immediately".
 */
fun String.toRetryAfterSeconds(): Long? = trim().toLongOrNull()?.takeIf { it >= 0 }

/** How long to wait after a rate-limit or quota response. */
object Backoff {
    /**
     * Used when a 429 arrives without a usable `Retry-After`.
     *
     * Spotify's window is a rolling 30 seconds, so a minute clears any ordinary
     * burst.
     */
    val DEFAULT_RATE_LIMIT_MS: Long = TimeUnit.MINUTES.toMillis(1)

    /**
     * Used for `QUOTA_EXCEEDED`.
     *
     * This is not a burst problem and retrying soon cannot fix it: the app has
     * used its allowance for the period. Spotify publishes no numbers for
     * development-mode quota and says only that it is enforced differently from
     * rate limits, so the response is to stop asking for a good long while
     * rather than to guess at a smaller number and keep probing.
     */
    val QUOTA_EXCEEDED_MS: Long = TimeUnit.HOURS.toMillis(6)

    /** Nothing waits longer than this, however large a Retry-After arrives. */
    val MAX_MS: Long = TimeUnit.HOURS.toMillis(24)

    fun forRateLimit(retryAfterSeconds: Long?, quotaExceeded: Boolean): Long {
        val base = when {
            quotaExceeded -> QUOTA_EXCEEDED_MS
            retryAfterSeconds != null -> TimeUnit.SECONDS.toMillis(retryAfterSeconds)
            else -> DEFAULT_RATE_LIMIT_MS
        }
        // A Retry-After of 0 is legal and means "now", but treating it literally
        // turns a rate limit into a tight loop. Floor it at a second.
        return base.coerceIn(TimeUnit.SECONDS.toMillis(1), MAX_MS)
    }
}
