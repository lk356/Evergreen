package com.example.evergreen.spike

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where an observation came from. Shown as a tag in the UI. */
enum class EventSource {
    /** The set of active media sessions changed (a player appeared or went away). */
    SESSION,

    /** MediaController told us the track metadata changed. */
    METADATA,

    /** MediaController told us the playback state changed. */
    STATE,

    /** A Spotify "Device Broadcast Status" intent arrived. */
    BROADCAST,

    /** Our own verdict about a track that just ended. */
    VERDICT,

    /** Lifecycle of the spike itself: listener connected, permission lost, errors. */
    SYSTEM,
}

/**
 * One line in the log. [summary] is the headline shown in the list; [detail] holds
 * the full field dump and is shown underneath.
 */
data class SpikeEvent(
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
 * In-memory ring buffer of the most recent observations, plus a mirror to Logcat.
 *
 * Phase 0 only: nothing is written to disk. Everything here is lost when the
 * process dies, which is intentional — the database arrives in Phase 1.
 *
 * Safe to call from any thread.
 */
object SpikeLog {
    const val TAG = "EvergreenSpike"
    const val MAX_EVENTS = 100

    private var nextId = 0L
    private val idLock = Any()

    private val _events = MutableStateFlow<List<SpikeEvent>>(emptyList())

    /** Newest event first. */
    val events: StateFlow<List<SpikeEvent>> = _events.asStateFlow()

    fun add(
        source: EventSource,
        summary: String,
        detail: String? = null,
        packageName: String? = null,
    ) {
        val event = SpikeEvent(
            id = synchronized(idLock) { nextId++ },
            wallClockMs = System.currentTimeMillis(),
            source = source,
            packageName = packageName,
            summary = summary,
            detail = detail,
        )

        // update {} is atomic, so concurrent callbacks cannot lose an event.
        _events.update { current -> (listOf(event) + current).take(MAX_EVENTS) }

        val line = buildString {
            append('[').append(source).append("] ")
            packageName?.let { append(it).append(" | ") }
            append(summary)
            detail?.let { append(" | ").append(it) }
        }
        Log.i(TAG, line)
    }

    fun clear() {
        _events.value = emptyList()
        Log.i(TAG, "[${EventSource.SYSTEM}] log cleared")
    }
}
