package com.example.evergreen.capture

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where an observation came from. */
enum class EventSource {
    /** The set of active media sessions changed. */
    SESSION,

    /** Track metadata changed. */
    METADATA,

    /** Playback state changed. */
    STATE,

    /** A Spotify "Device Broadcast Status" intent arrived. */
    BROADCAST,

    /** A play segment was closed and written to the database. */
    SEGMENT,

    /** Lifecycle of the capture layer itself. */
    SYSTEM,
}

/** One line in the live log. */
data class CaptureEvent(
    val id: Long,
    val wallClockMs: Long,
    val source: EventSource,
    val packageName: String?,
    val summary: String,
    val detail: String? = null,
) {
    fun formattedTime(): String = TIME_FORMAT.format(Date(wallClockMs))

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * An in-memory ring buffer of raw observations, mirrored to Logcat.
 *
 * Nothing here is persisted, on purpose. The database stores play segments —
 * one row per listen — and raw callbacks would balloon it for no benefit. But
 * three things were still unknown at the end of Phase 0 and can only be settled
 * by watching the raw stream on a device:
 *
 *  - what a natural end-of-track auto-advance looks like with repeat off,
 *  - whether queue auto-advance can be told apart from a manual "next",
 *  - the exact shape of a session ending.
 *
 * This is the window for answering them. If they turn out to be unobservable,
 * that gets recorded as unobservable — no play is ever invented to fill a gap.
 *
 * Safe to call from any thread.
 */
object CaptureLog {
    const val TAG = "EvergreenCapture"
    const val MAX_EVENTS = 200

    private var nextId = 0L
    private val idLock = Any()

    private val _events = MutableStateFlow<List<CaptureEvent>>(emptyList())

    /** Newest event first. */
    val events: StateFlow<List<CaptureEvent>> = _events.asStateFlow()

    fun add(
        source: EventSource,
        summary: String,
        detail: String? = null,
        packageName: String? = null,
    ) {
        val event = CaptureEvent(
            id = synchronized(idLock) { nextId++ },
            wallClockMs = System.currentTimeMillis(),
            source = source,
            packageName = packageName,
            summary = summary,
            detail = detail,
        )

        // update {} is atomic, so concurrent callbacks cannot lose an event.
        _events.update { current -> (listOf(event) + current).take(MAX_EVENTS) }

        Log.i(
            TAG,
            buildString {
                append('[').append(source).append("] ")
                packageName?.let { append(it).append(" | ") }
                append(summary)
                detail?.let { append(" | ").append(it) }
            },
        )
    }

    fun clear() {
        _events.value = emptyList()
        Log.i(TAG, "[${EventSource.SYSTEM}] log cleared")
    }
}
