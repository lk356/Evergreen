package com.example.evergreen.scoring

/** One track's place in the ranking. */
data class RankedTrack(
    val trackId: Long,
    val score: Double,
    /** 1 is the highest-scoring track. */
    val rank: Int,
)

/**
 * Scores every liked track and puts them in order.
 *
 * Pure, like the rest of this package: it is handed the inputs and returns the
 * ranking. The loading is the data layer's job, which keeps the ordering rules
 * testable without a database and stops the ranking quietly depending on the
 * order rows came back in.
 */
class Ranker(private val scorer: TrackScorer = TrackScorer()) {

    /**
     * @param inputs one per currently liked canonical track.
     * @return every track, highest score first, each with its 1-based rank.
     */
    fun rank(
        inputs: List<TrackScoringInput>,
        parameters: ScoringParameters,
        nowMs: Long,
        djWindows: List<DjWindow> = emptyList(),
    ): List<RankedTrack> =
        inputs
            .map { input -> scorer.score(input, parameters, nowMs, djWindows) }
            // Ties break on trackId so the ranking is stable between runs.
            // Without it, two tracks on identical scores could swap places
            // every recompute, and each swap across the cutoff would be a
            // playlist write — churn caused purely by sort instability.
            .sortedWith(compareByDescending<ScoreBreakdown> { it.score }.thenBy { it.trackId })
            .mapIndexed { index, breakdown ->
                RankedTrack(trackId = breakdown.trackId, score = breakdown.score, rank = index + 1)
            }

    companion object {
        /**
         * The oldest listening worth loading.
         *
         * Ten half-lives back, where a contribution retains 2^-10 — under a
         * tenth of a percent — of its weight. With 77,000 imported rows
         * stretching to 2020, scoring every row of every track on a device with
         * 2 GB of RAM and a slow CPU would be the difference between the
         * rankings taking a second and taking a minute.
         *
         * `RecomputeTest` pins that truncating here moves no score by more than
         * 0.001, so this is an optimisation and not a change to the answer.
         */
        fun loadFromMs(nowMs: Long, parameters: ScoringParameters): Long =
            nowMs - parameters.loadWindowMs
    }
}
