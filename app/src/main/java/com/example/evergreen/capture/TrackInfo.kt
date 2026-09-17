package com.example.evergreen.capture

import com.example.evergreen.data.trackKeyOf

/**
 * Identity and length of a track, as far as the media session tells us.
 *
 * Carried over from the Phase 0 spike. Contains no Android types so that the
 * capture logic built on it can run in plain JVM unit tests.
 */
data class TrackInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    /**
     * The service's own identifier, taken from the media session's `mediaId`
     * (`spotify:track:...`). Phase 0 confirmed Spotify populates this, which is
     * what lets a restart be told apart from a genuine track change even when
     * title, artist and album are unchanged.
     */
    val serviceTrackId: String? = null,
) {
    /** Fallback identity when no [serviceTrackId] is available. */
    val key: String get() = trackKeyOf(title, artist, album)

    val hasDuration: Boolean get() = durationMs > 0

    /**
     * True when this describes the same track as [other].
     *
     * Prefers the service identifier, which is exact. Falls back to
     * title/artist/album, which cannot distinguish two different recordings
     * with identical metadata — a known and accepted limitation.
     */
    fun isSameTrackAs(other: TrackInfo): Boolean =
        if (serviceTrackId != null && other.serviceTrackId != null) {
            serviceTrackId == other.serviceTrackId
        } else {
            key == other.key
        }

    /**
     * Everything that makes a metadata callback distinguishable from the last
     * one. The media session republishes byte-identical metadata many times a
     * second during transitions, so de-duplication compares *content* rather
     * than counting callbacks.
     */
    fun fingerprint(): String = "$key|$durationMs|${serviceTrackId.orEmpty()}"

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
 * where playback actually is now, extrapolate forward from that snapshot — see
 * [PositionEstimator].
 */
data class PositionSample(
    val positionMs: Long,
    val atElapsedRealtimeMs: Long,
    val speed: Float,
    val isPlaying: Boolean,
)

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
