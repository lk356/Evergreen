package com.example.evergreen.scoring

import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.ScoreEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One track's score at one instant, for the score-over-time line. */
data class ScorePoint(val atMs: Long, val score: Double)

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

    /**
     * One track's score as it stood at a series of past instants.
     *
     * **Recomputed, never stored.** A score is a pure function of (segments,
     * like events, parameters, now), so "what did this score on the 3rd of
     * June" is just that function with a different `now` and the events after
     * that date left out. Storing a score history would be a derived table that
     * quietly became truth -- the exact thing invariant 1a exists to prevent --
     * and it would be wrong the moment a parameter changed, which is precisely
     * when this view is most wanted.
     *
     * It is cheap because it is one track: a few hundred segments scored sixty
     * times, rather than four hundred tracks scored once. The whole-ranking
     * version of this would be sixty full recomputes, which is minutes on the
     * NW-A307 and is why there is no line for the playlist cutoff.
     *
     * @param days how far back to go.
     * @param points how many samples across that span, including both ends.
     */
    suspend fun scoreHistory(
        trackId: Long,
        nowMs: Long = System.currentTimeMillis(),
        days: Int = HISTORY_DAYS,
        points: Int = HISTORY_POINTS,
    ): List<ScorePoint> = withContext(Dispatchers.Default) {
        require(points >= 2) { "a line needs at least two points" }
        val parameters = parameters()
        val spanMs = days * ScoringParameters.MS_PER_DAY.toLong()
        val firstMs = nowMs - spanMs

        // Loaded from the window of the *earliest* point, not of now. A segment
        // ten half-lives before June is irrelevant to June's score but the
        // default window is measured from today, so using it would silently
        // truncate the left-hand end of the line -- and the line would slope
        // upwards for a reason that was an artefact of the query.
        val loadFrom = Ranker.loadFromMs(firstMs, parameters)
        val input = loadInputs(
            parameters = parameters,
            nowMs = nowMs,
            onlyTrackIds = listOf(trackId),
            fromMs = loadFrom,
        ).firstOrNull() ?: return@withContext emptyList()

        val djWindows = DjSessions.infer(
            scoringDao.commentaryTimestamps(TrackScorer.SPOTIFY_TRACK_PREFIX, loadFrom),
        )

        (0 until points).map { index ->
            val at = firstMs + spanMs * index / (points - 1)
            // Two bounds, and both matter.
            //
            // The upper one: events after this instant have not happened yet.
            // Without it the decay weight of a future event is 2^(positive) --
            // clamped to 1.0 by Decay, so each one quietly adds a whole play
            // rather than blowing up -- and the line shows a score the track
            // never had.
            //
            // The lower one: the same ten-half-life load window the ranker
            // uses, measured from *this* instant. Without it each point is
            // computed over more history than the app itself would use, and
            // the line disagrees with the score shown above it -- by 0.01 on a
            // real track, which is small but visible when both numbers are on
            // one screen. Like events are not windowed, matching loadInputs.
            val windowStart = Ranker.loadFromMs(at, parameters)
            val asOf = input.copy(
                segments = input.segments.filter { it.endedAtMs in windowStart..at },
                likeEvents = input.likeEvents.filter { it.atMs <= at },
            )
            ScorePoint(at, scorer.score(asOf, parameters, at, djWindows).score)
        }
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
        /** Overrides the load window, for reading a score at a past instant. */
        fromMs: Long = Ranker.loadFromMs(nowMs, parameters),
    ): List<TrackScoringInput> {
        val trackIds = onlyTrackIds ?: scoringDao.likedTrackIds()
        if (trackIds.isEmpty()) return emptyList()

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

        /**
         * Six months and sixty points, so each sample is about three days.
         *
         * Finer than the 45-day half-life by an order of magnitude, which is
         * what makes a single skip visible as a step rather than being averaged
         * away -- and the whole reason this exists is to see whether one skip
         * really does drag a score down further than a day of listening wins
         * back.
         */
        const val HISTORY_DAYS = 180
        const val HISTORY_POINTS = 60
    }
}
