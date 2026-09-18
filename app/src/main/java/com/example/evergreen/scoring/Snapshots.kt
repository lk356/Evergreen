package com.example.evergreen.scoring

import android.util.Log
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.SnapshotEntity
import com.example.evergreen.data.SnapshotEntryEntity
import com.example.evergreen.spotify.ApiResult
import com.example.evergreen.spotify.ApiSource
import com.example.evergreen.spotify.SpotifyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What taking a snapshot did. */
sealed interface SnapshotResult {
    data class Taken(val monthKey: String, val label: String, val trackCount: Int) : SnapshotResult

    /** Today is not the last day of the month. */
    data object NotDue : SnapshotResult

    /** This month already has one. */
    data class AlreadyExists(val monthKey: String) : SnapshotResult

    data class Empty(val monthKey: String) : SnapshotResult
}

/**
 * Monthly copies of the ranking, kept in the app.
 *
 * Taken on the last day of the month, stored locally, and turned into a real
 * Spotify playlist only on demand (decisions 5). The brief's phrase for what
 * this is for is being able to ask for "2026 August" later.
 *
 * **The whole ranking is stored, not only the playlist.** Keeping the top
 * hundred would answer "what was in Evergreen in August" and lose "what was
 * still alive in August" forever, and the second question is the one the
 * archive exists for.
 *
 * **The parameters are stored with it.** A score means nothing without the
 * settings that produced it; without them, the first retune turns every
 * earlier snapshot into a column of numbers on a scale nobody can reconstruct.
 */
class Snapshots(
    private val db: EvergreenDatabase,
    private val scores: ScoreService = ScoreService(db),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val scoringDao = db.scoringDao()

    /**
     * Takes this month's snapshot if it is due and there is not one already.
     *
     * @param force takes one whatever the date, for the debug screen's button.
     */
    suspend fun takeIfDue(force: Boolean = false): SnapshotResult {
        val at = now()
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(at), zone)

        if (!force && !isLastDayOfMonth(today)) return SnapshotResult.NotDue

        val monthKey = monthKeyOf(today)
        scoringDao.snapshotForMonth(monthKey)?.let { return SnapshotResult.AlreadyExists(monthKey) }

        val ranking = scores.ranking(at, force = true)
        if (ranking.tracks.isEmpty()) return SnapshotResult.Empty(monthKey)

        val members = scoringDao.currentMemberTrackIds().toSet()
        // Chunked for the same reason the scorer's loads are: SQLite refuses a
        // statement with more than 999 bound parameters, and the liked set has
        // no reason to stay at four hundred forever.
        val titles = ranking.tracks.map { it.trackId }
            .chunked(SQLITE_VARIABLE_LIMIT)
            .flatMap { chunk -> scoringDao.trackTitles(chunk) }
            .associateBy { it.id }

        // One query for every track's URI, keeping the first row per track --
        // the query orders the liked service row first.
        val uris = ranking.tracks.map { it.trackId }
            .chunked(SQLITE_VARIABLE_LIMIT)
            .flatMap { chunk ->
                scoringDao.preferredServiceUris(
                    chunk,
                    SERVICE_SPOTIFY,
                    SpotifyApi.TRACK_URI_PREFIX,
                )
            }
            .groupBy { it.trackId }
            .mapValues { (_, rows) -> rows.first().serviceTrackId }

        val label = labelOf(today)
        scoringDao.saveSnapshot(
            snapshot = SnapshotEntity(
                monthKey = monthKey,
                label = label,
                takenAt = at,
                parametersJson = ranking.parameters.toJson(),
                playlistSize = ranking.tracks.count { it.trackId in members },
                trackCount = ranking.tracks.size,
            ),
        ) { snapshotId ->
            ranking.tracks.map { track ->
                val info = titles[track.trackId]
                SnapshotEntryEntity(
                    snapshotId = snapshotId,
                    trackId = track.trackId,
                    rank = track.rank,
                    score = track.score,
                    // Copied in rather than looked up later, so a track that
                    // goes unavailable on Spotify -- as several of the 380
                    // already have -- stays visible in the month it was alive.
                    serviceTrackId = uris[track.trackId],
                    title = info?.title,
                    artist = info?.artist,
                    inPlaylist = track.trackId in members,
                )
            }
        }

        Log.i(TAG, "snapshot $monthKey: ${ranking.tracks.size} tracks")
        return SnapshotResult.Taken(monthKey, label, ranking.tracks.size)
    }

    /**
     * Exports a snapshot's playlist to a new Spotify playlist.
     *
     * A **new** playlist every time, never touched again by the app (decision
     * 13). That is what makes an export safe: it cannot overwrite anything, and
     * the user can rename, edit or delete it freely without the app disagreeing.
     * The Web API has no folders, so the naming convention is all there is —
     * `Evergreen — 2026 August` sorts next to its siblings.
     */
    suspend fun export(snapshotId: Long, api: ApiSource): String? = withContext(Dispatchers.IO) {
        exportOnThisThread(snapshotId, api)
    }

    /**
     * Off the main thread for the same reason [export] is: the debug screen
     * calls this from Compose's main-thread scope, and [ApiSource] blocks.
     */
    private suspend fun exportOnThisThread(snapshotId: Long, api: ApiSource): String? {
        val snapshot = scoringDao.snapshotById(snapshotId) ?: return null
        val uris = scoringDao.snapshotPlaylistEntries(snapshotId).mapNotNull { it.serviceTrackId }
        if (uris.isEmpty()) return null

        val created = api.createPlaylist(
            name = "$EXPORT_PREFIX${snapshot.label}",
            description = "Evergreen as it stood in ${snapshot.label}. A one-off copy; " +
                "the app never changes it again.",
        )
        if (created !is ApiResult.Ok) return null

        // In rank order, best first, in batches Spotify accepts.
        uris.chunked(SpotifyApi.MAX_PLAYLIST_WRITE).forEach { batch ->
            when (api.addToPlaylist(created.value.id, batch)) {
                is ApiResult.Ok -> Unit
                // An unavailable track is skipped and the rest go in, as in the
                // live sync. The snapshot keeps the track either way.
                else -> batch.forEach { uri -> api.addToPlaylist(created.value.id, listOf(uri)) }
            }
        }

        scoringDao.recordSnapshotExport(snapshotId, created.value.id, now())
        return created.value.id
    }

    companion object {
        private const val TAG = "Snapshots"
        private const val SQLITE_VARIABLE_LIMIT = 900

        const val EXPORT_PREFIX = "Evergreen — "

        /**
         * `2026-08`, sortable and searchable.
         *
         * The device's own timezone, because "August" is a local idea; a UTC
         * month boundary would put the last evening of the month into the next
         * one for anyone west of Greenwich.
         */
        fun monthKeyOf(date: LocalDate): String =
            date.format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.US))

        /** `2026 August` — the phrase from the brief. */
        fun labelOf(date: LocalDate): String =
            date.format(DateTimeFormatter.ofPattern("yyyy MMMM", Locale.US))

        /**
         * True on the last day of the month.
         *
         * The worker runs daily and does nothing on the other days. A daily
         * check that usually does nothing is simpler and far more robust than
         * trying to express "the last day of the month" as a schedule — no
         * special case for February, and a day missed to a flat battery costs
         * one month's snapshot rather than derailing the sequence.
         */
        fun isLastDayOfMonth(date: LocalDate): Boolean =
            date.dayOfMonth == date.lengthOfMonth()
    }
}
