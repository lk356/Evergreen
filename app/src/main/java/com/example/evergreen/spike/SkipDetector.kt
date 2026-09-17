package com.example.evergreen.spike

/**
 * Skip detection for the Phase 0 spike.
 *
 * Deliberately contains no Android types so it can run in plain JVM unit tests.
 * The Android layer converts MediaMetadata/PlaybackState into [TrackInfo] and
 * [PositionSample] before handing them over.
 *
 * The rules are crude on purpose. Finding out where they are wrong against real
 * playback is the point of the spike.
 */

/** What we concluded about a track once it stopped playing. */
enum class Outcome {
    /** Played to (near) the end. */
    COMPLETED,

    /** Abandoned well before the end. */
    SKIPPED,

    /** Not enough trustworthy data to say. */
    UNKNOWN,
}

/** Identity and length of a track, as far as the media session tells us. */
data class TrackInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
) {
    /**
     * Tracks have no stable ID in media-session metadata, so identity is the
     * title/artist/album triple. Consequence: a track repeating back-to-back
     * looks like the same track continuing. Noted as a known limitation.
     */
    val key: String get() = "${title.orEmpty()}|${artist.orEmpty()}|${album.orEmpty()}"

    val hasDuration: Boolean get() = durationMs > 0

    fun describe(): String = buildString {
        append(title ?: "(no title)")
        append(" — ").append(artist ?: "(no artist)")
        if (hasDuration) append(" [").append(formatMs(durationMs)).append(']')
    }
}

/**
 * A playback-position reading.
 *
 * Android reports position as a *snapshot* ([positionMs]) taken at a moment on
 * the monotonic clock ([atElapsedRealtimeMs]), not as a live value. To know
 * where playback actually is now, extrapolate forward from that snapshot.
 */
data class PositionSample(
    val positionMs: Long,
    val atElapsedRealtimeMs: Long,
    val speed: Float,
    val isPlaying: Boolean,
)

/** What we concluded about a track that just ended, and why. */
data class Verdict(
    val track: TrackInfo,
    val outcome: Outcome,
    val playedMs: Long?,
    val fraction: Double?,
    val reason: String,
) {
    fun describe(): String = buildString {
        append(outcome.name)
        if (playedMs != null && fraction != null) {
            append(" — played ").append(formatMs(playedMs))
            if (track.hasDuration) append(" of ").append(formatMs(track.durationMs))
            append(" (").append((fraction * 100).toInt()).append("%)")
        }
        append(" — ").append(reason)
    }
}

class SkipDetector(
    /** At or above this fraction of the track, we call it completed. */
    private val completionFraction: Double = DEFAULT_COMPLETION_FRACTION,
    /**
     * If our newest position reading is older than this when a track changes,
     * extrapolating it would be guesswork, so we report UNKNOWN instead.
     * Callbacks can go quiet while the screen is off, which is exactly the
     * case this guards against.
     */
    private val stalePositionMs: Long = DEFAULT_STALE_POSITION_MS,
) {
    private var currentTrack: TrackInfo? = null
    private var lastSample: PositionSample? = null

    /** Record a position reading for the track currently playing. */
    fun onPositionSample(sample: PositionSample) {
        if (currentTrack != null) lastSample = sample
    }

    /**
     * Report that metadata now describes [next].
     *
     * Returns a [Verdict] about the track that was playing *before* this one,
     * or null if this is the first track or the metadata refers to the same
     * track we were already on.
     */
    fun onTrackChanged(next: TrackInfo, nowElapsedRealtimeMs: Long): Verdict? {
        val previous = currentTrack

        if (previous == null) {
            currentTrack = next
            lastSample = null
            return null
        }

        if (previous.key == next.key) {
            // Same track; metadata refreshed. Duration often arrives late, so
            // take it if we did not have it before.
            if (!previous.hasDuration && next.hasDuration) currentTrack = next
            return null
        }

        val verdict = judge(previous, nowElapsedRealtimeMs)
        currentTrack = next
        lastSample = null
        return verdict
    }

    /** Best estimate of how far into the current track we are, or null. */
    fun estimatePositionMs(nowElapsedRealtimeMs: Long): Long? {
        val sample = lastSample ?: return null
        return estimate(sample, nowElapsedRealtimeMs, currentTrack?.durationMs ?: 0L)
    }

    private fun judge(track: TrackInfo, nowElapsedRealtimeMs: Long): Verdict {
        val sample = lastSample
            ?: return Verdict(track, Outcome.UNKNOWN, null, null, "no position reading for this track")

        if (!track.hasDuration) {
            return Verdict(track, Outcome.UNKNOWN, null, null, "track duration unknown")
        }

        val gapMs = nowElapsedRealtimeMs - sample.atElapsedRealtimeMs
        if (sample.isPlaying && gapMs > stalePositionMs) {
            return Verdict(
                track,
                Outcome.UNKNOWN,
                null,
                null,
                "position reading ${formatMs(gapMs)} stale",
            )
        }

        val playedMs = estimate(sample, nowElapsedRealtimeMs, track.durationMs)
        val fraction = playedMs.toDouble() / track.durationMs
        val outcome = if (fraction >= completionFraction) Outcome.COMPLETED else Outcome.SKIPPED
        val reason = if (outcome == Outcome.COMPLETED) {
            "reached ${(completionFraction * 100).toInt()}% threshold"
        } else {
            "ended below ${(completionFraction * 100).toInt()}% threshold"
        }
        return Verdict(track, outcome, playedMs, fraction, reason)
    }

    private fun estimate(
        sample: PositionSample,
        nowElapsedRealtimeMs: Long,
        durationMs: Long,
    ): Long {
        val elapsed = if (sample.isPlaying) {
            val gap = (nowElapsedRealtimeMs - sample.atElapsedRealtimeMs).coerceAtLeast(0L)
            (gap * sample.speed).toLong()
        } else {
            0L
        }
        val raw = sample.positionMs + elapsed
        // Extrapolation can overshoot the end of the track; clamp it.
        return if (durationMs > 0) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_COMPLETION_FRACTION = 0.90
        const val DEFAULT_STALE_POSITION_MS = 30_000L
    }
}

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
