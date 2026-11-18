package com.example.evergreen.scoring

import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.LikeEventType
import com.example.evergreen.data.MergedSegment
import com.example.evergreen.data.PlaybackType
import com.example.evergreen.data.SegmentGrouping
import java.time.Instant
import java.time.ZoneId

/** What the scorer knows about one service identifier for a track. */
data class ServiceTrackInfo(
    val serviceTrackId: String,
    val durationMs: Long,
)

/** One like or unlike, already reduced to when it happened. */
data class LikeEventRecord(
    val type: LikeEventType,
    /**
     * When the user actually liked it.
     *
     * Spotify's own `added_at` where known, falling back to when this app first
     * observed the like (decision 10.4). The fallback matters: without it, all
     * 380 existing likes would be dated the day the app was installed and would
     * give every one of them a three-play boost that fades together, drowning
     * out the listening history for months.
     */
    val atMs: Long,
)

/** Everything needed to score one canonical track. */
data class TrackScoringInput(
    val trackId: Long,
    /** Every stored segment for this track, in any order. */
    val segments: List<ListeningSegmentEntity>,
    /** Service rows by `serviceTrackRowId`, so each segment's URI is known. */
    val serviceRows: Map<Long, ServiceTrackInfo>,
    /** The canonical track's best known length, or 0. */
    val canonicalDurationMs: Long,
    val likeEvents: List<LikeEventRecord>,
)

/**
 * Turns stored facts into one number, and shows its working.
 *
 * **A score is computed, never accumulated** (invariant 1a). There is no
 * running total that plays are added to as they arrive. Every call rebuilds the
 * score from the segments, the like events and the parameters. That is what
 * makes the parameters retunable: an accumulated score would have folded old
 * contributions in under old settings, with no way to unwind them, so changing
 * the half-life would mean re-capturing six years of listening. The `scores`
 * table is a cache of this function's output and nothing more.
 *
 * The pipeline is `docs/PHASE3_PLAN.md` section 3.2, in order.
 */
class TrackScorer(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun score(
        input: TrackScoringInput,
        parameters: ScoringParameters,
        nowMs: Long,
        djWindows: List<DjWindow> = emptyList(),
        browsingWindows: List<BrowsingWindow> = emptyList(),
    ): ScoreBreakdown {
        val contributions = mutableListOf<ScoreContribution>()

        // 1. Drop commentary, and keep it visible in the breakdown so that a
        //    track whose rows are all commentary explains itself rather than
        //    looking like a scoring failure.
        val (commentary, songs) = input.segments.partition { segment -> isCommentary(segment, input) }
        commentary.forEach { segment ->
            contributions += ScoreContribution(
                kind = ContributionKind.LISTEN,
                atMs = segment.endedAtMs,
                fractionHeard = null,
                listenedMs = segment.listenedMs,
                rawPlayEquivalents = 0.0,
                playEquivalents = 0.0,
                decayWeight = 0.0,
                decayedValue = 0.0,
                note = ContributionNote.COMMENTARY,
                segmentIds = listOf(segment.id),
            )
        }

        // 1b. Drop phantoms: rows recorded from a session that was controlling
        //     another device's volume, which means this device was almost
        //     certainly not the one playing. Kept visible for the same reason
        //     commentary is -- a track whose rows are all phantoms should
        //     explain itself rather than look like a scoring failure.
        //
        //     Before the fold, deliberately. A phantom and a real listen of the
        //     same track can sit next to each other in time, and folding them
        //     together would produce one listen that is partly somebody else's.
        val (remote, local) = songs.partition { it.playbackType == PlaybackType.REMOTE }
        remote.forEach { segment ->
            contributions += ScoreContribution(
                kind = ContributionKind.LISTEN,
                atMs = segment.endedAtMs,
                fractionHeard = null,
                listenedMs = segment.listenedMs,
                rawPlayEquivalents = 0.0,
                playEquivalents = 0.0,
                decayWeight = 0.0,
                decayedValue = 0.0,
                note = ContributionNote.REMOTE_SESSION,
                segmentIds = listOf(segment.id),
            )
        }

        // 2. Fold replays. Capture cuts a new segment at every backwards jump
        //    over 1000 ms because cutting finely is reversible; this is the
        //    reverse operation, applying the replay threshold that was
        //    deliberately never stored. Imported rows carry no jump fields and
        //    so are never folded into anything.
        val listens = SegmentGrouping
            .merge(local.sortedByDescending { it.endedAtMs }, parameters.replayJumpThresholdMs)
            .sortedBy { it.endedAtMs }

        // 3-5. Fraction heard, the curve, browsing runs, DJ suppression.
        val scored = listens.map { listen ->
            scoreListen(listen, input, parameters, djWindows, browsingWindows, listens)
        }

        // 6. The daily cap, applied before decay, per section 3.2.
        contributions += applyDailyCap(scored, parameters, nowMs)

        // 8. Likes.
        input.likeEvents.sortedBy { it.atMs }.forEach { event ->
            contributions += likeContribution(event, parameters, nowMs)
        }

        contributions.sortBy { it.atMs }

        // 9. Sum.
        return ScoreBreakdown(
            trackId = input.trackId,
            score = contributions.sumOf { it.decayedValue },
            contributions = contributions,
            parameters = parameters,
            nowMs = nowMs,
        )
    }

    /**
     * True for Spotify DJ commentary.
     *
     * Recognised by the URI scheme, which is structural, rather than by the
     * title or the zero duration, which are user-visible text and will be
     * localised or reworded. A segment with no service identifier at all is not
     * commentary — commentary always carries a `spotify:media:` URI — so it is
     * scored normally.
     */
    private fun isCommentary(segment: ListeningSegmentEntity, input: TrackScoringInput): Boolean {
        val uri = segment.serviceTrackRowId?.let { input.serviceRows[it]?.serviceTrackId }
            ?: return false
        return !uri.startsWith(SPOTIFY_TRACK_PREFIX)
    }

    /** Steps 3 to 5 for one folded listen. */
    private fun scoreListen(
        listen: MergedSegment,
        input: TrackScoringInput,
        parameters: ScoringParameters,
        djWindows: List<DjWindow>,
        browsingWindows: List<BrowsingWindow>,
        allListens: List<MergedSegment>,
    ): ScoreContribution {
        val outcome = ScoringCurve.outcome(
            listenedMs = listen.listenedMs,
            durationMs = durationFor(listen, input),
            parameters = parameters,
        )

        var value = outcome.playEquivalents
        var note = when (outcome.note) {
            CurveNote.UNKNOWN_DURATION -> ContributionNote.UNKNOWN_DURATION
            CurveNote.SCORED -> ContributionNote.SCORED
        }

        // 4b. Browsing runs (scoring freshness, change B). Before the DJ
        //     rule, and instead of it: a skip inside a run takes the run's
        //     discount and no other, so the breakdown gives one reason.
        if (value < 0.0 && isBrowsing(listen, parameters, browsingWindows)) {
            if (isRevisit(listen, allListens)) {
                value = 0.0
                note = ContributionNote.BROWSING_REVISIT
            } else {
                value *= parameters.rapidRunSkipWeight
                note = ContributionNote.BROWSING
            }
        } else if (value < 0.0 && DjSessions.isDuringSession(djWindows, listen.startedAtMs)) {
            // 5. DJ suppression. Negatives only: a skip during a DJ session is
            //    browsing, but letting a DJ pick play through is still real
            //    information about the song.
            value = 0.0
            note = ContributionNote.DJ_SUPPRESSED
        }

        return ScoreContribution(
            kind = ContributionKind.LISTEN,
            atMs = listen.endedAtMs,
            fractionHeard = outcome.fractionHeard,
            listenedMs = listen.listenedMs,
            rawPlayEquivalents = outcome.playEquivalents,
            playEquivalents = value,
            decayWeight = 0.0,
            decayedValue = 0.0,
            note = note,
            segmentIds = listen.rows.map { it.id },
            utcOffsetMinutes = listen.utcOffsetMinutes,
        )
    }

    /**
     * True when [listen] is one of the short listens of a browsing run on its
     * own device's timeline. Off, and never true, when runs are off.
     */
    private fun isBrowsing(
        listen: MergedSegment,
        parameters: ScoringParameters,
        windows: List<BrowsingWindow>,
    ): Boolean =
        parameters.browsingRunsOn &&
            listen.listenedMs <= parameters.rapidRunMaxListenMs &&
            BrowsingRuns.isInRun(windows, listen.rows.first().deviceId, listen.startedAtMs)

    /**
     * True when this track was already heard on the same timeline within
     * [BrowsingRuns.REVISIT_WINDOW_MS] before [listen] started: going back
     * past it, not judging it. Any earlier listen counts, a flick included,
     * so passing a song twice in one search costs at most once.
     */
    private fun isRevisit(listen: MergedSegment, allListens: List<MergedSegment>): Boolean {
        val timeline = listen.rows.first().deviceId
        val from = listen.startedAtMs - BrowsingRuns.REVISIT_WINDOW_MS
        return allListens.any { earlier ->
            earlier !== listen &&
                earlier.rows.first().deviceId == timeline &&
                earlier.endedAtMs <= listen.startedAtMs &&
                earlier.endedAtMs >= from
        }
    }

    /**
     * Where a listen's duration comes from, most specific first.
     *
     * The segment's own reading is preferred because then the numerator and the
     * denominator come from the same source — the media session rounds to the
     * second while the Spotify broadcast is exact, and mixing them makes
     * percentages quietly wrong.
     *
     * The service row is the fallback, and it is what makes the streaming
     * history import work: the export carries no durations, so imported rows
     * store 0, and the length arrives later when the track is liked or played.
     * The same stored row then starts scoring with nothing re-imported.
     */
    private fun durationFor(listen: MergedSegment, input: TrackScoringInput): Long {
        listen.trackDurationMs.takeIf { it > 0 }?.let { return it }
        listen.rows.asSequence()
            .mapNotNull { row -> row.serviceTrackRowId?.let { input.serviceRows[it]?.durationMs } }
            .firstOrNull { it > 0 }
            ?.let { return it }
        return input.canonicalDurationMs
    }

    /**
     * Step 6: one track may contribute at most [ScoringParameters.dailyCapPlays]
     * positive play-equivalents per calendar day.
     *
     * This is what handles repeat-one overnight — forty loops score two plays,
     * not forty — with a cap rather than a diminishing-returns curve, and with
     * no loop detection to write (decisions 2e).
     *
     * Negatives are not capped. Capping them would mean that skipping a track
     * repeatedly in one sitting, which is about the clearest rejection there
     * is, counted for less the more emphatically it happened.
     *
     * Applied before decay, per section 3.2. Within a day the earliest listens
     * fill the allowance first, so the one that crosses the line is trimmed and
     * later ones are zeroed.
     */
    private fun applyDailyCap(
        listens: List<ScoreContribution>,
        parameters: ScoringParameters,
        nowMs: Long,
    ): List<ScoreContribution> {
        val usedPerDay = mutableMapOf<Long, Double>()

        return listens.sortedBy { it.atMs }.map { contribution ->
            var value = contribution.playEquivalents
            var note = contribution.note

            if (value > 0.0) {
                val day = localDayOf(contribution.atMs, contribution.utcOffsetMinutes)
                val used = usedPerDay.getOrDefault(day, 0.0)
                val allowed = (parameters.dailyCapPlays - used).coerceAtLeast(0.0)
                if (value > allowed) {
                    value = allowed
                    note = ContributionNote.DAILY_CAP
                }
                usedPerDay[day] = used + value
            }

            val weight = Decay.weight(contribution.atMs, nowMs, parameters)
            contribution.copy(
                playEquivalents = value,
                decayWeight = weight,
                decayedValue = value * weight,
                note = note,
            )
        }
    }

    /**
     * The calendar day a moment falls in, **where it happened**.
     *
     * Local rather than UTC because "in one night" is what the cap is about, and
     * a UTC day would cut an evening's listening in half for anyone east of
     * Greenwich. That was unambiguous while one device produced every fact.
     *
     * **Schema v8 makes it unambiguous again with two.** A segment carries the
     * offset that was in force when it was captured, so the day it falls in is
     * a property of the listening rather than of whichever device happens to be
     * doing the arithmetic. Without that, the same history recomputed in a
     * different zone would bucket plays on different days and cap them
     * differently — two devices, same facts, same parameters, different
     * rankings, which is the one outcome cross-device sync exists to rule out.
     *
     * [offsetMinutes] null falls back to this device's current zone, which is
     * precisely what this function did before v8, so every pre-v8 row keeps the
     * day it always had.
     */
    private fun localDayOf(atMs: Long, offsetMinutes: Int?): Long =
        if (offsetMinutes == null) {
            Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate().toEpochDay()
        } else {
            Math.floorDiv(atMs + offsetMinutes * MS_PER_MINUTE, MS_PER_DAY)
        }

    /**
     * Step 8. A like is a scoring event worth a few plays, decaying like any
     * other (decisions 2c).
     *
     * This is what replaces the "grace period" earlier drafts had: a freshly
     * liked song enters strong and fades if listening does not back it up, with
     * no protected-window mechanism to build and one fewer parameter to tune.
     *
     * Note that a track liked, unliked and liked again carries two LIKED events
     * and so contributes twice, as does a song whose album and single versions
     * were both liked and later merged. Section 3.1 says "like events for every
     * service row of the track", so that is what this does; both decay, and
     * both describe something the user really did.
     */
    private fun likeContribution(
        event: LikeEventRecord,
        parameters: ScoringParameters,
        nowMs: Long,
    ): ScoreContribution {
        val isLike = event.type == LikeEventType.LIKED
        val value = if (isLike) parameters.likeWeight else 0.0
        val weight = Decay.weight(event.atMs, nowMs, parameters)
        return ScoreContribution(
            kind = ContributionKind.LIKE,
            atMs = event.atMs,
            fractionHeard = null,
            listenedMs = null,
            rawPlayEquivalents = value,
            playEquivalents = value,
            decayWeight = weight,
            decayedValue = value * weight,
            note = if (isLike) ContributionNote.LIKE else ContributionNote.UNLIKE_IGNORED,
        )
    }

    companion object {
        const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
    }
}
