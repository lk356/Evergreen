package com.example.evergreen.scoring

import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.ScoreEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A ranking, and whether it came from the cache or was just computed. */
data class Ranking(
    val tracks: List<RankedTrack>,
    val parameters: ScoringParameters,
    val computedAtMs: Long,
    val fromCache: Boolean,
)

/**
 * Joins the pure scoring code to the database.
 *
 * Everything that decides *what a score is* lives in [TrackScorer] and
 * [Ranker], which have no Android types and are tested without a device. This
 * class only loads rows and puts results away, which is why it is thin and why
 * the interesting tests are not here.
 *
 * **The cache is never read as truth.** [ranking] rebuilds whenever the stored
 * rows were computed under different parameters, and `refresh` can be called to
 * rebuild unconditionally. Dropping the `scores` table at any moment is always
 * a valid repair, which is the practical form of invariant 1a.
 */
class ScoreService(
    private val database: EvergreenDatabase,
    private val ranker: Ranker = Ranker(),
    private val scorer: TrackScorer = TrackScorer(),
) {

    private val scoringDao = database.scoringDao()

    suspend fun parameters(): ScoringParameters = scoringDao.scoringParameters()

    /**
     * The current ranking, from the cache when it is still valid for these
     * parameters and freshly computed when it is not.
     *
     * @param force recompute even if the cache matches — what the "Recompute"
     *   button does, and what the sync worker does when it wants scores decayed
     *   to this moment rather than to whenever the cache was built.
     */
    suspend fun ranking(
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): Ranking = withContext(Dispatchers.Default) { rankingOnThisThread(nowMs, force) }

    /**
     * Scoring is real work: four hundred tracks over the segments of 450 days,
     * which on the NW-A307 is seconds rather than milliseconds. It runs on
     * [Dispatchers.Default] because the Scores tab calls it from Compose's
     * main-thread scope, where it would freeze the UI -- the same class of
     * mistake as the network call that crashed the Evergreen preview, but
     * showing up as a hang rather than an exception.
     */
    private suspend fun rankingOnThisThread(nowMs: Long, force: Boolean): Ranking {
        val parameters = parameters()

        if (!force && !scoringDao.isCacheStale(parameters)) {
            val cached = scoringDao.cachedScores()
            return Ranking(
                tracks = cached.map { RankedTrack(it.trackId, it.score, it.rank) },
                parameters = parameters,
                computedAtMs = cached.firstOrNull()?.computedAt ?: nowMs,
                fromCache = true,
            )
        }

        val tracks = ranker.rank(
            inputs = loadInputs(parameters, nowMs),
            parameters = parameters,
            nowMs = nowMs,
            djWindows = loadDjWindows(parameters, nowMs),
        )

        val hash = parameters.hash()
        scoringDao.replaceScores(
            tracks.map { track ->
                ScoreEntity(
                    trackId = track.trackId,
                    score = track.score,
                    rank = track.rank,
                    computedAt = nowMs,
                    parametersHash = hash,
                )
            },
        )

        return Ranking(tracks, parameters, nowMs, fromCache = false)
    }

    /**
     * One track's score with every event that produced it, for the explain view.
     *
     * Recomputed rather than read from the cache, because the cache stores only
     * the total. That is the right trade: the breakdown is wanted for one track
     * at a time, on demand, and storing every contribution for four hundred
     * tracks would be a second copy of the listening history.
     */
    suspend fun breakdown(
        trackId: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): ScoreBreakdown? = withContext(Dispatchers.Default) {
        val parameters = parameters()
        val input = loadInputs(parameters, nowMs, onlyTrackIds = listOf(trackId)).firstOrNull()
        input?.let { scorer.score(it, parameters, nowMs, loadDjWindows(parameters, nowMs)) }
    }

    /** Every liked track still above the record floor — "what is alive". */
    suspend fun record(nowMs: Long = System.currentTimeMillis()): List<RankedTrack> =
        Membership.record(ranking(nowMs).tracks, parameters())

    private suspend fun loadDjWindows(parameters: ScoringParameters, nowMs: Long): List<DjWindow> =
        DjSessions.infer(
            scoringDao.commentaryTimestamps(
                trackUriPrefix = TrackScorer.SPOTIFY_TRACK_PREFIX,
                fromMs = Ranker.loadFromMs(nowMs, parameters),
            ),
        )

    /**
     * Loads everything the scorer needs, in a handful of queries rather than a
     * few per track.
     *
     * Four hundred tracks times three queries each is twelve hundred round
     * trips on a device with a slow CPU; this is five, whatever the track count.
     */
    private suspend fun loadInputs(
        parameters: ScoringParameters,
        nowMs: Long,
        onlyTrackIds: List<Long>? = null,
    ): List<TrackScoringInput> {
        val trackIds = onlyTrackIds ?: scoringDao.likedTrackIds()
        if (trackIds.isEmpty()) return emptyList()

        val fromMs = Ranker.loadFromMs(nowMs, parameters)
        val durations = chunked(trackIds) { chunk -> scoringDao.trackDurations(chunk) }
            .associate { it.id to it.durationMs }

        // Doubles as an existence check. Asking for a track that is not in the
        // database returns nothing rather than a breakdown showing a score of
        // zero — a fabricated answer for a song that does not exist would be
        // indistinguishable from a real one for a song nobody has played.
        val presentIds = trackIds.filter { it in durations }
        if (presentIds.isEmpty()) return emptyList()

        val segments = chunked(presentIds) { chunk -> scoringDao.segmentsForScoring(chunk, fromMs) }
            .groupBy(ListeningSegmentEntity::trackId)
        val likeEvents = chunked(presentIds) { chunk -> scoringDao.likeEventsForScoring(chunk) }
            .groupBy { it.trackId }
        // Every service row, not only the relevant ones: there are a few
        // thousand at most, and one query beats a join per track.
        val serviceRows = scoringDao.allServiceTracks().associate { row ->
            row.id to ServiceTrackInfo(row.serviceTrackId, row.durationMs)
        }

        return presentIds.map { trackId ->
            TrackScoringInput(
                trackId = trackId,
                segments = segments[trackId].orEmpty(),
                serviceRows = serviceRows,
                canonicalDurationMs = durations[trackId] ?: 0L,
                likeEvents = likeEvents[trackId].orEmpty()
                    .map { LikeEventRecord(it.event, it.atMs) },
            )
        }
    }

    /**
     * Runs a query over the track ids in batches.
     *
     * SQLite refuses a statement with more than 999 bound parameters, and the
     * liked set is only four hundred today but has no reason to stay that way.
     * A limit hit at a thousand likes would be a confusing crash years from now.
     */
    private suspend fun <T> chunked(
        trackIds: List<Long>,
        query: suspend (List<Long>) -> List<T>,
    ): List<T> = trackIds.chunked(SQLITE_VARIABLE_LIMIT).flatMap { chunk -> query(chunk) }

    private companion object {
        const val SQLITE_VARIABLE_LIMIT = 900
    }
}
