package com.example.evergreen.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Test 8 of `docs/PHASE3_PLAN.md` section 9, the behavioural half: reads only
 * the listed fields; skips rows without a track URI; computes started/ended; a
 * row with an `ip_addr` key produces an output with no such value anywhere.
 *
 * Instrumented rather than a plain JVM test because the parser streams with
 * `android.util.JsonReader`, which is a stub on the unit-test classpath. The
 * structural privacy guarantee — that [HistoryRow] has nowhere to put an IP
 * address at all — is pinned in `HistoryRowTest`, which does run on the JVM.
 *
 * Every fixture here is synthetic. No real export row appears in the
 * repository.
 */
@RunWith(AndroidJUnit4::class)
class HistoryParserTest {

    private val parser = HistoryParser()

    private fun parse(json: String): Pair<HistoryParseSummary, List<HistoryRow>> = runBlocking {
        val rows = mutableListOf<HistoryRow>()
        val summary = parser.parse(json.byteInputStream()) { rows += it }
        summary to rows
    }

    // --- The fields it reads --------------------------------------------------

    @Test
    fun readsTheSevenAllowedFields() {
        val (summary, rows) = parse(
            """
            [
              {
                "ts": "2024-03-01T20:15:30Z",
                "ms_played": 214000,
                "spotify_track_uri": "spotify:track:4h3K5he5ItFqJB1MVHMKJV",
                "master_metadata_track_name": "Metadata",
                "master_metadata_album_artist_name": "Willix",
                "master_metadata_album_album_name": "Metadata",
                "reason_end": "trackdone"
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, summary.rowsRead)
        assertEquals(1, summary.rowsAccepted)
        val row = rows.single()
        assertEquals(1_709_324_130_000L, row.endedAtMs)
        assertEquals(214_000L, row.listenedMs)
        assertEquals("spotify:track:4h3K5he5ItFqJB1MVHMKJV", row.spotifyTrackUri)
        assertEquals("Metadata", row.trackName)
        assertEquals("Willix", row.artistName)
        assertEquals("Metadata", row.albumName)
        assertEquals("trackdone", row.reasonEnd)
    }

    /** `ts` is the end of the stream; the start is derived by subtraction. */
    @Test
    fun computesTheStartFromTheEndAndTheDuration() {
        val (_, rows) = parse(
            """
            [{"ts":"2024-03-01T20:15:30Z","ms_played":214000,
              "spotify_track_uri":"spotify:track:aaa"}]
            """.trimIndent(),
        )

        val row = rows.single()
        assertEquals(1_709_324_130_000L, row.endedAtMs)
        assertEquals(1_709_323_916_000L, row.startedAtMs)
    }

    // --- The fields it does not read ------------------------------------------

    /**
     * The privacy rule for this phase, exercised end to end.
     *
     * The real export carries `ip_addr` on every single row. This fixture is
     * shaped like one, with every field the app is not allowed to read present
     * and populated. Nothing from them may appear anywhere in the output.
     *
     * `offline_timestamp` is deliberately set to an instant unrelated to `ts`.
     * An earlier draft used the same moment for both, and its epoch-seconds
     * string was then a substring of the legitimate epoch-millis output, so the
     * check below failed on a coincidence rather than on a leak.
     */
    @Test
    fun ignoresEveryFieldOutsideTheAllowlist() {
        val (summary, rows) = parse(
            """
            [
              {
                "ts": "2024-03-01T20:15:30Z",
                "username": "someone",
                "platform": "android",
                "ms_played": 214000,
                "conn_country": "GB",
                "ip_addr": "203.0.113.42",
                "ip_addr_decrypted": "203.0.113.42",
                "user_agent_decrypted": "unknown",
                "master_metadata_track_name": "Metadata",
                "master_metadata_album_artist_name": "Willix",
                "master_metadata_album_album_name": "Metadata",
                "spotify_track_uri": "spotify:track:4h3K5he5ItFqJB1MVHMKJV",
                "episode_name": null,
                "episode_show_name": null,
                "spotify_episode_uri": null,
                "audiobook_title": null,
                "audiobook_chapter_title": null,
                "reason_start": "clickrow",
                "reason_end": "trackdone",
                "shuffle": false,
                "skipped": false,
                "offline": false,
                "offline_timestamp": 1600000000,
                "incognito_mode": false
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, summary.rowsAccepted)
        val row = rows.single()

        // Nothing the row can be turned into may contain any of it.
        val rendered = row.toString()
        listOf(
            "203.0.113.42",
            "someone",
            "android",
            "GB",
            "clickrow",
            "incognito",
            "1600000000",
        ).forEach { forbidden ->
            assertTrue(
                "the parser's output mentions '$forbidden': $rendered",
                !rendered.contains(forbidden, ignoreCase = true),
            )
        }

        // And the seven it is allowed to read came through untouched, so the
        // skipping did not knock the reader out of step with the keys.
        assertEquals("Metadata", row.trackName)
        assertEquals("Willix", row.artistName)
        assertEquals("trackdone", row.reasonEnd)
        assertEquals(214_000L, row.listenedMs)
    }

    /**
     * `offline_timestamp` is deliberately unused: it is meaningful only for
     * offline plays, and mixing it with `ts` would make some rows' times mean
     * something different from others'. A known simplification, pinned here so
     * it stays a decision rather than becoming an oversight.
     */
    @Test
    fun doesNotUseTheOfflineTimestamp() {
        val (_, rows) = parse(
            """
            [{"ts":"2024-03-01T20:15:30Z","ms_played":1000,"offline":true,
              "offline_timestamp":1600000000,
              "spotify_track_uri":"spotify:track:aaa"}]
            """.trimIndent(),
        )

        assertEquals(1_709_324_130_000L, rows.single().endedAtMs)
    }

    /** A nested or array-valued unknown key must not derail the reader. */
    @Test
    fun skipsStructuredUnknownFields() {
        val (summary, rows) = parse(
            """
            [
              {
                "something_nested": {"a": [1, 2, {"b": "c"}], "d": null},
                "ts": "2024-03-01T20:15:30Z",
                "a_list": [{"x": 1}, {"y": 2}],
                "ms_played": 5000,
                "spotify_track_uri": "spotify:track:aaa"
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, summary.rowsAccepted)
        assertEquals(5000L, rows.single().listenedMs)
    }

    // --- Rows it skips --------------------------------------------------------

    /** Podcasts and audiobooks carry an episode URI instead of a track URI. */
    @Test
    fun skipsAndCountsRowsWithNoTrackUri() {
        val (summary, rows) = parse(
            """
            [
              {"ts":"2024-03-01T20:15:30Z","ms_played":1000,
               "spotify_track_uri":"spotify:track:aaa"},
              {"ts":"2024-03-01T20:20:30Z","ms_played":900000,
               "spotify_track_uri":null,
               "spotify_episode_uri":"spotify:episode:bbb",
               "episode_name":"Some Podcast"},
              {"ts":"2024-03-01T20:40:30Z","ms_played":1000}
            ]
            """.trimIndent(),
        )

        assertEquals(3, summary.rowsRead)
        assertEquals(1, summary.rowsAccepted)
        assertEquals(2, summary.rowsWithoutTrackUri)
        assertEquals("spotify:track:aaa", rows.single().spotifyTrackUri)
    }

    /**
     * Only `spotify:track:` is accepted. The same rule the scorer applies to
     * `spotify:media:` DJ commentary: it is not a song, so it has no canonical
     * track to score.
     */
    @Test
    fun skipsNonTrackUriSchemes() {
        val (summary, _) = parse(
            """
            [
              {"ts":"2024-03-01T20:15:30Z","ms_played":1000,
               "spotify_track_uri":"spotify:media:2j7w4Q2yMxa9ekzCdGhKUc"},
              {"ts":"2024-03-01T20:16:30Z","ms_played":1000,
               "spotify_track_uri":"spotify:episode:xyz"}
            ]
            """.trimIndent(),
        )

        assertEquals(2, summary.rowsRead)
        assertEquals(0, summary.rowsAccepted)
        assertEquals(2, summary.rowsWithoutTrackUri)
    }

    @Test
    fun skipsAndCountsRowsWithAnUnreadableTimestamp() {
        val (summary, _) = parse(
            """
            [
              {"ts":"not a date","ms_played":1000,"spotify_track_uri":"spotify:track:aaa"},
              {"ts":null,"ms_played":1000,"spotify_track_uri":"spotify:track:bbb"}
            ]
            """.trimIndent(),
        )

        assertEquals(2, summary.rowsRead)
        assertEquals(0, summary.rowsAccepted)
        assertEquals(2, summary.rowsWithoutTimestamp)
    }

    // --- Tolerance ------------------------------------------------------------

    @Test
    fun readsAnEmptyFile() {
        val (summary, rows) = parse("[]")

        assertEquals(HistoryParseSummary(), summary)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun toleratesMissingOptionalMetadata() {
        val (_, rows) = parse(
            """
            [{"ts":"2024-03-01T20:15:30Z","ms_played":1000,
              "spotify_track_uri":"spotify:track:aaa",
              "master_metadata_track_name":null,
              "master_metadata_album_artist_name":null,
              "master_metadata_album_album_name":null,
              "reason_end":null}]
            """.trimIndent(),
        )

        val row = rows.single()
        assertNull(row.trackName)
        assertNull(row.artistName)
        assertNull(row.albumName)
        assertNull(row.reasonEnd)
    }

    @Test
    fun readsAQuotedDuration() {
        val (_, rows) = parse(
            """
            [{"ts":"2024-03-01T20:15:30Z","ms_played":"214000",
              "spotify_track_uri":"spotify:track:aaa"}]
            """.trimIndent(),
        )

        assertEquals(214_000L, rows.single().listenedMs)
    }

    /** A negative duration would produce a segment that ended before it began. */
    @Test
    fun clampsANegativeDurationToZero() {
        val (_, rows) = parse(
            """
            [{"ts":"2024-03-01T20:15:30Z","ms_played":-5000,
              "spotify_track_uri":"spotify:track:aaa"}]
            """.trimIndent(),
        )

        val row = rows.single()
        assertEquals(0L, row.listenedMs)
        assertEquals(row.endedAtMs, row.startedAtMs)
    }

    /**
     * An unreadable file must stop the import rather than look like a
     * successful import of nothing — otherwise a truncated download would be
     * recorded as "done" and the real history never imported.
     */
    @Test
    fun aMalformedFileThrowsRatherThanReportingZeroRows() {
        assertThrows(IOException::class.java) {
            runBlocking { parser.parse("not json at all".byteInputStream()) {} }
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                parser.parse("""[{"ts":"2024-03-01T20:15:30Z",""".byteInputStream()) {}
            }
        }
    }

    /** Rows arrive as they are read, so 77,000 of them never sit in memory. */
    @Test
    fun streamsRowsRatherThanCollectingThem() = runBlocking {
        val seenWhileParsing = mutableListOf<Int>()
        var delivered = 0

        val json = buildString {
            append('[')
            repeat(200) { index ->
                if (index > 0) append(',')
                append(
                    """{"ts":"2024-03-01T20:15:30Z","ms_played":$index,""" +
                        """"spotify_track_uri":"spotify:track:t$index"}""",
                )
            }
            append(']')
        }

        val summary = parser.parse(json.byteInputStream()) {
            delivered++
            seenWhileParsing += delivered
        }

        assertEquals(200, summary.rowsAccepted)
        assertEquals(200, delivered)
        // Delivered one at a time, in order, while the file was still being read.
        assertEquals((1..200).toList(), seenWhileParsing)
    }
}
