package com.example.evergreen.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The listening history and the rankings, as files a person can read.
 *
 * Distinct from [DatabaseBackup], which exists so the data can be *restored*
 * and is therefore a SQLite file nothing else will open. This is the other
 * half: getting the data out into something a spreadsheet, a script or another
 * program can use.
 *
 * **CSV rather than JSON.** Both were on the table; the deciding argument is
 * that these are two flat tables with a fixed set of columns, and the thing
 * most likely to be done with them is to open them and sort by a column. A CSV
 * does that with no tooling at all. JSON would be better for nested data, and
 * there is none here.
 *
 * **Streamed, never assembled.** The segment table holds 77,000 rows after the
 * import and grows without bound; building the file as a String first would be
 * tens of megabytes of char array on a device with limited RAM, for no gain
 * over writing it out a page at a time.
 *
 * Timestamps are written twice: as ISO 8601 in the device's timezone, which is
 * what a human reads, and as epoch milliseconds, which is what survives being
 * loaded into another program. Neither is derivable from the other without
 * knowing the offset, and the offset changes twice a year.
 */
object DataExport {

    /** What a file will hold. */
    enum class Kind(val label: String) {
        /** One row per stored play segment. */
        LISTENING("listening"),

        /** One row per liked track, in rank order. */
        RANKINGS("rankings"),
    }

    /**
     * Writes [kind] to [destination].
     *
     * @return how many data rows were written, not counting the header.
     */
    suspend fun writeTo(
        context: Context,
        destination: Uri,
        kind: Kind,
    ): Int = withContext(Dispatchers.IO) {
        val db = EvergreenDatabase.get(context)
        val out = context.contentResolver.openOutputStream(destination)
            ?: throw IOException("Could not write to the file you chose.")

        out.bufferedWriter().use { writer ->
            when (kind) {
                Kind.LISTENING -> writeListening(db, writer)
                Kind.RANKINGS -> writeRankings(db, writer)
            }
        }
    }

    /**
     * Every stored play, oldest row first.
     *
     * Includes imported rows, marked as such in the `source` column and with
     * the columns the export could not say left empty rather than filled with a
     * zero. "Not observed" and "observed at the start" are different statements
     * and the file keeps them apart, exactly as the database does.
     */
    private suspend fun writeListening(db: EvergreenDatabase, writer: Writer): Int {
        val dao = db.dao()
        writer.writeRow(
            "started_at", "started_at_ms", "ended_at", "ended_at_ms",
            "title", "artist",
            "listened_ms", "track_duration_ms", "fraction_heard",
            "end_position_ms", "forward_seeks", "seeked_forward_ms",
            "end_reason", "source", "source_end_reason", "app_package",
        )

        var written = 0
        var offset = 0
        while (true) {
            val page = dao.segmentPage(limit = PAGE, offset = offset)
            if (page.isEmpty()) break
            page.forEach { row ->
                val segment = row.segment
                writer.writeRow(
                    isoOf(segment.startedAtMs), segment.startedAtMs.toString(),
                    isoOf(segment.endedAtMs), segment.endedAtMs.toString(),
                    row.title.orEmpty(), row.artist.orEmpty(),
                    segment.listenedMs.toString(),
                    segment.trackDurationMs.toString(),
                    // Blank rather than zero when the track's length is
                    // unknown: DJ commentary and imported rows both carry a
                    // zero duration, and "how much was heard" has no answer
                    // without a length. A 0 here would read as "heard none of
                    // it", which is a different and wrong claim.
                    if (segment.trackDurationMs > 0) {
                        fraction(segment.listenedMs, segment.trackDurationMs)
                    } else {
                        ""
                    },
                    segment.endPositionMs?.toString().orEmpty(),
                    segment.forwardSeekCount?.toString().orEmpty(),
                    segment.seekedForwardMs?.toString().orEmpty(),
                    segment.endReason.name,
                    segment.source.name,
                    segment.sourceEndReason.orEmpty(),
                    segment.appPackage,
                )
                written++
            }
            if (page.size < PAGE) break
            offset += PAGE
        }
        return written
    }

    /**
     * Every scored track, best first.
     *
     * Read from the score cache, so it is whatever the app last computed rather
     * than a recompute triggered by pressing export -- which on this device is
     * seconds of work and would make the button feel broken. The Scores screen
     * is where a recompute is asked for.
     */
    private suspend fun writeRankings(db: EvergreenDatabase, writer: Writer): Int {
        val scoringDao = db.scoringDao()
        val parameters = scoringDao.scoringParameters()
        val cached = scoringDao.cachedScores()
        if (cached.isEmpty()) {
            writer.writeRow("rank", "score", "title", "artist", "in_playlist")
            return 0
        }

        val titles = cached.map { it.trackId }
            .chunked(SQLITE_VARIABLE_LIMIT)
            .flatMap { chunk -> scoringDao.trackTitles(chunk) }
            .associateBy { it.id }
        val members = scoringDao.currentMemberTrackIds().toSet()
        val uris = cached.map { it.trackId }
            .chunked(SQLITE_VARIABLE_LIMIT)
            .flatMap { chunk ->
                scoringDao.preferredServiceUris(chunk, SERVICE_SPOTIFY, SPOTIFY_TRACK_PREFIX)
            }
            .groupBy { it.trackId }
            .mapValues { (_, rows) -> rows.first().serviceTrackId }

        writer.writeRow(
            "rank", "score", "title", "artist",
            "in_playlist", "above_record_floor", "spotify_uri",
            "computed_at", "computed_at_ms",
        )
        cached.forEach { score ->
            val info = titles[score.trackId]
            writer.writeRow(
                score.rank.toString(),
                score.score.toString(),
                info?.title.orEmpty(),
                info?.artist.orEmpty(),
                (score.trackId in members).toString(),
                (score.score > parameters.recordFloor).toString(),
                uris[score.trackId].orEmpty(),
                isoOf(score.computedAt),
                score.computedAt.toString(),
            )
        }
        return cached.size
    }

    /**
     * A filename that says what it holds and sorts chronologically.
     *
     * The timestamp is in it because exporting twice should give two files
     * rather than one overwritten one -- this is a snapshot of a moving record,
     * and which moment it describes is part of what it is.
     */
    fun suggestedFileName(kind: Kind, nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(nowMs))
        return "evergreen-${kind.label}-$stamp.csv"
    }

    // --- CSV -----------------------------------------------------------------

    /**
     * Writes one row, quoting everything.
     *
     * Every field is quoted rather than only the ones that need it. Titles and
     * artists contain commas, quotation marks and occasionally newlines, and a
     * rule that quotes conditionally is a rule with an exception waiting in
     * someone's music library. CRLF because that is what RFC 4180 says and what
     * the spreadsheet software most likely to open this expects.
     */
    internal fun Writer.writeRow(vararg fields: String) {
        write(csvRow(fields.toList()))
    }

    internal fun csvRow(fields: List<String>): String =
        fields.joinToString(separator = ",", postfix = "\r\n") { field ->
            "\"" + field.replace("\"", "\"\"") + "\""
        }

    private fun fraction(listenedMs: Long, durationMs: Long): String =
        String.format(Locale.US, "%.4f", listenedMs.toDouble() / durationMs)

    /**
     * ISO 8601 with the offset, in the device's own timezone.
     *
     * The offset is included rather than assumed: a file covering six years of
     * listening spans a dozen daylight-saving changes, and a bare local time
     * with no offset is ambiguous for one hour every autumn.
     */
    private fun isoOf(epochMs: Long): String =
        SimpleDateFormat(ISO_PATTERN, Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(epochMs))

    private const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX"

    /** Rows per database read. Small enough to stay cheap, large enough to be one query per screenful of file. */
    private const val PAGE = 500

    private const val SQLITE_VARIABLE_LIMIT = 900

    private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
}
