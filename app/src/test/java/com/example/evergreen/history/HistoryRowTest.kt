package com.example.evergreen.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The privacy half of test 8 in `docs/PHASE3_PLAN.md` section 9: "a unit test
 * asserts the parser's output type has no room for anything else".
 *
 * This is the guarantee that does not depend on the parser being written
 * carefully. The export contains an IP address on every row, the country it was
 * seen from, the platform, and offline timestamps. A careful parser can be made
 * careless by a later edit; a type with nowhere to put an IP address cannot.
 *
 * Plain JVM, no device needed — which is why the structural check lives here
 * and the parsing behaviour lives in `HistoryParserTest` alongside
 * `android.util.JsonReader`.
 */
class HistoryRowTest {

    /**
     * The full list of what the app may know about one play.
     *
     * Adding an eighth field means changing this test, which means saying out
     * loud that the app now reads something more about the user.
     */
    private val allowedFields = setOf(
        "endedAtMs",
        "listenedMs",
        "spotifyTrackUri",
        "trackName",
        "artistName",
        "albumName",
        "reasonEnd",
    )

    /**
     * Every value one row can actually store: its instance fields.
     *
     * Statics are excluded — the companion object and the compiler's own
     * `$stable` are not per-row data and hold nothing from the file.
     */
    private fun storedFields(): Set<String> =
        HistoryRow::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    /** Everything readable off a row, including computed properties. */
    private fun readableNames(): List<String> =
        storedFields().toList() + HistoryRow::class.java.declaredMethods
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filter { it.startsWith("get") }
            .map { it.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }

    @Test
    fun `the row type can hold nothing but the seven allowed fields`() {
        assertEquals(allowedFields, storedFields())
    }

    /**
     * `startedAtMs` is the only thing readable beyond the seven, and it is
     * derived from two of them rather than being anything new from the file.
     */
    @Test
    fun `the only derived value is the start time`() {
        assertEquals(allowedFields + "startedAtMs", readableNames().toSet())
    }

    /**
     * Named individually so a failure says *which* forbidden field appeared,
     * and so the list of things deliberately not read is written down somewhere
     * a reader will find it.
     */
    @Test
    fun `the row type has no field for anything the export also carries`() {
        val actual = readableNames().map { it.lowercase() }

        val forbidden = listOf(
            "ipaddr", "ipaddress", "ip",
            "conncountry", "country",
            "platform", "useragent",
            "offlinetimestamp", "offline",
            "episode", "audiobook", "chapter", "show",
            "username", "user",
            "shuffle", "incognitomode",
        )
        forbidden.forEach { field ->
            assertTrue(
                "HistoryRow must not be able to hold '$field', but has ${actual.filter { it.contains(field) }}",
                actual.none { it.contains(field) },
            )
        }
    }

    @Test
    fun `the parser's allowlist is exactly the seven documented fields`() {
        assertEquals(
            setOf(
                "ts",
                "ms_played",
                "spotify_track_uri",
                "master_metadata_track_name",
                "master_metadata_album_artist_name",
                "master_metadata_album_album_name",
                "reason_end",
            ),
            HistoryParser.ALLOWED_FIELDS,
        )
    }

    // --- Derived values ------------------------------------------------------

    /**
     * `ts` is the end of the stream, so the start is derived by subtraction.
     * Getting this backwards would date six years of listening wrongly by up to
     * a track length each, which decay would then quietly misweight.
     */
    @Test
    fun `the start is the end minus the time played`() {
        val row = sampleRow(endedAtMs = 1_600_000_000_000, listenedMs = 180_000)

        assertEquals(1_599_999_820_000, row.startedAtMs)
        assertEquals(180_000, row.endedAtMs - row.startedAtMs)
    }

    @Test
    fun `a zero-length play starts when it ends`() {
        val row = sampleRow(endedAtMs = 1_600_000_000_000, listenedMs = 0)

        assertEquals(row.endedAtMs, row.startedAtMs)
    }

    // --- Timestamps ----------------------------------------------------------

    @Test
    fun `Spotify's own timestamp format is read as UTC`() {
        assertEquals(
            1_579_879_930_000L,
            HistoryRow.parseTimestamp("2020-01-24T15:32:10Z"),
        )
    }

    @Test
    fun `fractional seconds are accepted`() {
        assertEquals(
            1_579_879_930_500L,
            HistoryRow.parseTimestamp("2020-01-24T15:32:10.5Z"),
        )
    }

    /**
     * Older exports have been seen writing a space instead of the `T` and
     * omitting the zone. Accepting that shape rather than dropping the rows is
     * the difference between importing six years and importing one.
     */
    @Test
    fun `the older space-separated format is accepted as UTC`() {
        assertEquals(
            HistoryRow.parseTimestamp("2020-01-24T15:32:10Z"),
            HistoryRow.parseTimestamp("2020-01-24 15:32:10"),
        )
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(
            1_579_879_930_000L,
            HistoryRow.parseTimestamp("  2020-01-24T15:32:10Z  "),
        )
    }

    /** Never guessed at. An unreadable timestamp is counted and skipped. */
    @Test
    fun `an unreadable timestamp is null rather than a guess`() {
        listOf(null, "", "   ", "not a date", "24/01/2020", "0", "2020-13-45T99:99:99Z").forEach {
            assertNull("should not have parsed: $it", HistoryRow.parseTimestamp(it))
        }
    }

    private fun sampleRow(endedAtMs: Long, listenedMs: Long) = HistoryRow(
        endedAtMs = endedAtMs,
        listenedMs = listenedMs,
        spotifyTrackUri = "spotify:track:4h3K5he5ItFqJB1MVHMKJV",
        trackName = "Metadata",
        artistName = "Willix",
        albumName = "Metadata",
        reasonEnd = "trackdone",
    )
}
