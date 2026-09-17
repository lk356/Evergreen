package com.example.evergreen.capture

import android.content.Context
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SERVICE_SPOTIFY
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persists finished play segments.
 *
 * Writes happen off the callback thread, one at a time. Serialising them is not
 * only about contention: resolving a track means "insert if new, otherwise look
 * up", and letting two of those interleave would be asking for trouble even
 * with the unique indexes as a backstop.
 *
 * The write rate is already low by construction. [SegmentTracker] emits roughly
 * one segment per song rather than one per callback, which is the batching the
 * NW-A307 needs — there is nothing to gain from buffering further, and buffering
 * would only widen the window in which a killed process loses data.
 */
class SegmentWriter(
    context: Context,
    private val deviceId: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val dao = EvergreenDatabase.get(context).dao()

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error ->
            CaptureLog.add(
                EventSource.SYSTEM,
                "failed to store a segment",
                detail = error.message,
            )
        },
    )

    fun write(segment: ClosedSegment) {
        scope.launch {
            dao.recordSegment(
                trackKey = segment.track.key,
                title = segment.track.title,
                artist = segment.track.artist,
                album = segment.track.album,
                durationMs = segment.trackDurationMs,
                service = SERVICE_SPOTIFY,
                serviceTrackId = segment.track.serviceTrackId,
                segment = segment.toEntity(deviceId),
            )

            CaptureLog.add(
                source = EventSource.SEGMENT,
                summary = "${segment.track.describe()} -> ${segment.endReason}",
                detail = buildString {
                    append("listened=").append(formatMs(segment.listenedMs))
                    append(" reached=").append(formatMs(segment.endPositionMs))
                    if (segment.trackDurationMs > 0) {
                        val percent = segment.endPositionMs * 100 / segment.trackDurationMs
                        append(" (").append(percent).append("%)")
                    }
                    if (segment.forwardSeekCount > 0) {
                        append(" seeks=").append(segment.forwardSeekCount)
                        append(" skipped=").append(formatMs(segment.seekedForwardMs))
                    }
                    segment.endJumpFromMs?.let { from ->
                        append(" jumpBack=").append(formatMs(from))
                        append("->").append(formatMs(segment.endJumpToMs ?: 0))
                    }
                },
                packageName = segment.appPackage,
            )
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}

/** Maps the capture layer's plain facts onto the database row. */
fun ClosedSegment.toEntity(deviceId: String): ListeningSegmentEntity = ListeningSegmentEntity(
    // Resolved inside the transaction that writes this row.
    trackId = 0,
    serviceTrackRowId = null,
    appPackage = appPackage,
    deviceId = deviceId,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    startPositionMs = startPositionMs,
    endPositionMs = endPositionMs,
    listenedMs = listenedMs,
    trackDurationMs = trackDurationMs,
    forwardSeekCount = forwardSeekCount,
    seekedForwardMs = seekedForwardMs,
    endReason = endReason,
    endJumpFromMs = endJumpFromMs,
    endJumpToMs = endJumpToMs,
    lastSampleAgeMs = lastSampleAgeMs,
    createdAt = System.currentTimeMillis(),
)
