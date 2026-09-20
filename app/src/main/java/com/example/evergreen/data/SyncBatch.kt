package com.example.evergreen.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Writer

/**
 * The fact batch: *these facts, from this device, after this watermark.*
 *
 * The wire format is specified in `docs/SYNC_FORMAT.md`, which is written so
 * that a Linux agent in Phase 7 could produce a valid batch without reading any
 * of this file. **When the two disagree, the document is right** — it is the
 * contract, and this is one implementation of it.
 *
 * ### Why JSON Lines, and why by hand
 *
 * One JSON object per line, header first. A batch may hold 77,000 segments and
 * the device producing it is an NW-A307, so neither side may hold the whole
 * thing: JSON Lines is written and read a record at a time, which a single JSON
 * document is not. It is also readable in a text editor, which matters the
 * first time something is wrong.
 *
 * `org.json` per line rather than a serialisation library, because it is in the
 * framework and this adds no dependency. It is a stub on the JVM unit-test
 * classpath, which is why the tests for this are instrumented — they need a
 * real database anyway.
 *
 * ### Nothing here is a conclusion
 *
 * Every field written is something that was observed. There is no outcome, no
 * score and no ranking anywhere in a batch: a score is computed and never
 * accumulated (invariant 1a), and the receiver recomputing is the whole point.
 */
object SyncBatch {

    const val FORMAT = "evergreen-batch"
    const val FORMAT_VERSION = 1

    /** The `t` tag on each line. */
    object Type {
        const val HEADER = "header"
        const val SEGMENT = "seg"
        const val LIKE_EVENT = "like_event"
        const val LIKE = "like"
        const val PARAMS = "params"
        const val MEMBER = "member"
        const val SNAPSHOT = "snap"
        const val SNAPSHOT_ENTRY = "snap_entry"
    }

    /** A reason the user can act on. Never a stack trace, never a row. */
    class Malformed(message: String) : Exception(message)

    /**
     * One originating device the batch carries segments for.
     *
     * A list rather than a single device because a database holds facts that
     * originated on more than one: rows from a streaming-history export carry
     * the fixed origin `spotify-history-import`, and a reinstall leaves older
     * rows under the previous install id. Both are facts only this database
     * has, and a batch keyed on one device would leave them behind silently.
     */
    data class Origin(
        val deviceId: String,
        /** The batch holds nothing from this device at or below this id. */
        val afterOriginId: Long,
        /** The highest id included, or null if none of its segments are here. */
        val throughOriginId: Long?,
    )

    data class Header(
        val formatVersion: Int,
        val schemaVersion: Int,
        val producerName: String?,
        val takenAtMs: Long,
        val origins: List<Origin>,
        val counts: Map<String, Int>,
    ) {
        fun countOf(type: String): Int = counts[type] ?: 0

        val totalFacts: Int get() = counts.values.sum()

        /** For the screens, which never name a device id. */
        fun describeProducer(): String = producerName ?: "another device"
    }

    /**
     * How a fact names the track it is about.
     *
     * Content-addressed, and never a row id. `tracks` and `service_tracks` are
     * stable across devices by construction — `trackKey` from the metadata, and
     * `(service, serviceTrackId)` from the service itself — so a producer never
     * has to know the receiver's row ids and a receiver never trusts the
     * producer's.
     */
    data class TrackRef(
        val key: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val service: String?,
        val serviceTrackId: String?,
        val isrc: String?,
    )

    // --- Writing --------------------------------------------------------------

    /**
     * Writes one batch to [out].
     *
     * Deliberately not buffered here: the caller owns the stream and knows
     * whether it is writing to a file or to a test's string. Every method
     * writes one complete line and returns, so a caller can flush whenever it
     * likes and nothing is ever half-written.
     */
    class Writer(private val out: java.io.Writer) {

        private fun line(json: JSONObject) {
            out.write(json.toString())
            out.write("\n")
        }

        fun header(header: Header) {
            val origins = JSONArray()
            header.origins.forEach { origin ->
                origins.put(
                    JSONObject()
                        .put("deviceId", origin.deviceId)
                        .put("afterOriginId", origin.afterOriginId)
                        .putOrNull("throughOriginId", origin.throughOriginId),
                )
            }
            val counts = JSONObject()
            header.counts.forEach { (type, count) -> counts.put(type, count) }
            line(
                JSONObject()
                    .put("t", Type.HEADER)
                    .put("format", FORMAT)
                    .put("formatVersion", header.formatVersion)
                    .put("schemaVersion", header.schemaVersion)
                    .putOrNull("producerName", header.producerName)
                    .put("takenAtMs", header.takenAtMs)
                    .put("origins", origins)
                    .put("counts", counts),
            )
        }

        fun segment(row: SegmentFact) {
            line(
                JSONObject()
                    .put("t", Type.SEGMENT)
                    .put("deviceId", row.deviceId)
                    .put("originRowId", row.originRowId)
                    .put("track", row.track.toJson())
                    .put("appPackage", row.appPackage)
                    .put("startedAtMs", row.startedAtMs)
                    .put("endedAtMs", row.endedAtMs)
                    .putOrNull("startPositionMs", row.startPositionMs)
                    .putOrNull("endPositionMs", row.endPositionMs)
                    .put("listenedMs", row.listenedMs)
                    .put("trackDurationMs", row.trackDurationMs)
                    .putOrNull("forwardSeekCount", row.forwardSeekCount)
                    .putOrNull("seekedForwardMs", row.seekedForwardMs)
                    .put("endReason", row.endReason)
                    .putOrNull("endJumpFromMs", row.endJumpFromMs)
                    .putOrNull("endJumpToMs", row.endJumpToMs)
                    .putOrNull("lastSampleAgeMs", row.lastSampleAgeMs)
                    .put("createdAt", row.createdAt)
                    .put("source", row.source)
                    .putOrNull("sourceEndReason", row.sourceEndReason),
            )
        }

        fun likeEvent(row: LikeEventFact) {
            line(
                JSONObject()
                    .put("t", Type.LIKE_EVENT)
                    .put("track", row.track.toJson())
                    .put("event", row.event)
                    .put("observedAt", row.observedAt)
                    .putOrNull("serviceAddedAtMs", row.serviceAddedAtMs),
            )
        }

        fun like(row: LikeFact) {
            line(
                JSONObject()
                    .put("t", Type.LIKE)
                    .put("track", row.track.toJson())
                    .putOrNull("serviceAddedAtMs", row.serviceAddedAtMs)
                    .put("firstSeenLikedAt", row.firstSeenLikedAt)
                    .put("lastSeenLikedAt", row.lastSeenLikedAt)
                    .putOrNull("unlikedAt", row.unlikedAt),
            )
        }

        fun params(row: ParamsFact) {
            line(
                JSONObject()
                    .put("t", Type.PARAMS)
                    .put("changedAt", row.changedAt)
                    .put("parametersJson", row.parametersJson)
                    .put("parametersHash", row.parametersHash)
                    .putOrNull("previousJson", row.previousJson)
                    .put("current", row.current),
            )
        }

        fun member(row: MemberFact) {
            line(
                JSONObject()
                    .put("t", Type.MEMBER)
                    .put("track", row.track.toJson())
                    .put("joinedAt", row.joinedAt)
                    .putOrNull("leftAt", row.leftAt)
                    .put("joinReason", row.joinReason)
                    .putOrNull("leaveReason", row.leaveReason)
                    .put("rankAtJoin", row.rankAtJoin)
                    .putOrNull("rankAtLeave", row.rankAtLeave),
            )
        }

        fun snapshot(row: SnapshotFact) {
            line(
                JSONObject()
                    .put("t", Type.SNAPSHOT)
                    .put("monthKey", row.monthKey)
                    .put("label", row.label)
                    .put("takenAt", row.takenAt)
                    .put("parametersJson", row.parametersJson)
                    .put("playlistSize", row.playlistSize)
                    .put("trackCount", row.trackCount)
                    .putOrNull("exportedPlaylistId", row.exportedPlaylistId)
                    .putOrNull("exportedAt", row.exportedAt),
            )
        }

        fun snapshotEntry(row: SnapshotEntryFact) {
            line(
                JSONObject()
                    .put("t", Type.SNAPSHOT_ENTRY)
                    .put("monthKey", row.monthKey)
                    .put("track", row.track.toJson())
                    .put("rank", row.rank)
                    .put("score", row.score)
                    .putOrNull("serviceTrackId", row.serviceTrackId)
                    .putOrNull("title", row.title)
                    .putOrNull("artist", row.artist)
                    .put("inPlaylist", row.inPlaylist),
            )
        }
    }

    // --- Reading --------------------------------------------------------------

    /**
     * Reads a batch a line at a time.
     *
     * [readHeader] must be called first and exactly once. [forEachFact] then
     * walks the rest, handing each line to the caller as a [JSONObject] — which
     * the typed `readX` helpers turn into facts. Nothing is held: a 77,000-line
     * batch costs one line of memory at a time.
     */
    class Reader(private val source: BufferedReader) {

        private var lineNumber = 0

        private fun nextObject(): JSONObject? {
            while (true) {
                val text = source.readLine() ?: return null
                lineNumber++
                if (text.isBlank()) continue
                return try {
                    JSONObject(text)
                } catch (failure: JSONException) {
                    // Never quote the line back: it is listening history, and a
                    // message is somewhere it could be read over a shoulder or
                    // pasted into a bug report.
                    throw Malformed(
                        "Line $lineNumber of that file is not valid JSON, so the batch was " +
                            "not applied. Nothing has been changed.",
                    )
                }
            }
        }

        fun readHeader(): Header {
            val json = nextObject() ?: throw Malformed(
                "That file is empty. Pick the .jsonl file the \"Export changes\" button wrote.",
            )
            if (json.optString("t") != Type.HEADER || json.optString("format") != FORMAT) {
                throw Malformed(
                    "That file is not an Evergreen changes file. Pick the .jsonl file the " +
                        "\"Export changes\" button wrote — a database backup is a different " +
                        "thing and goes through \"Import a mirror\".",
                )
            }
            val version = json.optInt("formatVersion", -1)
            if (version != FORMAT_VERSION) {
                throw Malformed(
                    if (version > FORMAT_VERSION) {
                        "That file was written by a newer version of Evergreen (changes " +
                            "format v$version; this build reads v$FORMAT_VERSION). Update " +
                            "this device's app. Nothing has been changed."
                    } else {
                        "That file was written by an older version of Evergreen (changes " +
                            "format v$version; this build reads v$FORMAT_VERSION). Export it " +
                            "again on the other device. Nothing has been changed."
                    },
                )
            }

            val origins = json.optJSONArray("origins") ?: JSONArray()
            val parsed = (0 until origins.length()).map { index ->
                val entry = origins.getJSONObject(index)
                Origin(
                    deviceId = entry.optString("deviceId").ifEmpty {
                        throw Malformed("That file's header names an origin with no device.")
                    },
                    afterOriginId = entry.optLong("afterOriginId", 0L),
                    throughOriginId = entry.longOrNull("throughOriginId"),
                )
            }
            if (parsed.isEmpty()) {
                throw Malformed("That file's header names no originating device at all.")
            }

            val countsJson = json.optJSONObject("counts") ?: JSONObject()
            val counts = countsJson.keys().asSequence()
                .associateWith { key -> countsJson.optInt(key, 0) }

            return Header(
                formatVersion = version,
                schemaVersion = json.optInt("schemaVersion", 0),
                producerName = json.stringOrNull("producerName"),
                takenAtMs = json.optLong("takenAtMs", 0L),
                origins = parsed,
                counts = counts,
            )
        }

        /**
         * Walks every remaining line.
         *
         * A line whose `t` is unrecognised is counted and skipped rather than
         * refused, which is what lets a later format version add a type without
         * breaking this build. The count is reported so that "the import did
         * less than you expected" is never silent.
         *
         * @return how many lines were skipped for having an unknown type.
         */
        suspend fun forEachFact(onFact: suspend (String, JSONObject) -> Boolean): Int {
            var skipped = 0
            while (true) {
                val json = nextObject() ?: return skipped
                val type = json.optString("t")
                if (type == Type.HEADER) {
                    throw Malformed("That file has more than one header in it.")
                }
                if (!onFact(type, json)) skipped++
            }
        }
    }

    // --- The facts ------------------------------------------------------------

    data class SegmentFact(
        val deviceId: String,
        val originRowId: Long,
        val track: TrackRef,
        val appPackage: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val startPositionMs: Long?,
        val endPositionMs: Long?,
        val listenedMs: Long,
        val trackDurationMs: Long,
        val forwardSeekCount: Int?,
        val seekedForwardMs: Long?,
        val endReason: String,
        val endJumpFromMs: Long?,
        val endJumpToMs: Long?,
        val lastSampleAgeMs: Long?,
        val createdAt: Long,
        val source: String,
        val sourceEndReason: String?,
    )

    data class LikeEventFact(
        val track: TrackRef,
        val event: String,
        val observedAt: Long,
        val serviceAddedAtMs: Long?,
    )

    data class LikeFact(
        val track: TrackRef,
        val serviceAddedAtMs: Long?,
        val firstSeenLikedAt: Long,
        val lastSeenLikedAt: Long,
        val unlikedAt: Long?,
    )

    data class ParamsFact(
        val changedAt: Long,
        val parametersJson: String,
        val parametersHash: String,
        val previousJson: String?,
        val current: Boolean,
    )

    data class MemberFact(
        val track: TrackRef,
        val joinedAt: Long,
        val leftAt: Long?,
        val joinReason: String,
        val leaveReason: String?,
        val rankAtJoin: Int,
        val rankAtLeave: Int?,
    )

    data class SnapshotFact(
        val monthKey: String,
        val label: String,
        val takenAt: Long,
        val parametersJson: String,
        val playlistSize: Int,
        val trackCount: Int,
        val exportedPlaylistId: String?,
        val exportedAt: Long?,
    )

    data class SnapshotEntryFact(
        val monthKey: String,
        val track: TrackRef,
        val rank: Int,
        val score: Double,
        val serviceTrackId: String?,
        val title: String?,
        val artist: String?,
        val inPlaylist: Boolean,
    )

    // --- Turning a line into a fact ------------------------------------------

    fun readSegment(json: JSONObject) = SegmentFact(
        deviceId = json.requireString("deviceId"),
        originRowId = json.requireLong("originRowId"),
        track = readTrack(json),
        appPackage = json.requireString("appPackage"),
        startedAtMs = json.requireLong("startedAtMs"),
        endedAtMs = json.requireLong("endedAtMs"),
        startPositionMs = json.longOrNull("startPositionMs"),
        endPositionMs = json.longOrNull("endPositionMs"),
        listenedMs = json.requireLong("listenedMs"),
        trackDurationMs = json.requireLong("trackDurationMs"),
        forwardSeekCount = json.longOrNull("forwardSeekCount")?.toInt(),
        seekedForwardMs = json.longOrNull("seekedForwardMs"),
        endReason = json.requireString("endReason"),
        endJumpFromMs = json.longOrNull("endJumpFromMs"),
        endJumpToMs = json.longOrNull("endJumpToMs"),
        lastSampleAgeMs = json.longOrNull("lastSampleAgeMs"),
        createdAt = json.requireLong("createdAt"),
        source = json.optString("source").ifEmpty { SegmentSource.CAPTURE.name },
        sourceEndReason = json.stringOrNull("sourceEndReason"),
    )

    fun readLikeEvent(json: JSONObject) = LikeEventFact(
        track = readTrack(json),
        event = json.requireString("event"),
        observedAt = json.requireLong("observedAt"),
        serviceAddedAtMs = json.longOrNull("serviceAddedAtMs"),
    )

    fun readLike(json: JSONObject) = LikeFact(
        track = readTrack(json),
        serviceAddedAtMs = json.longOrNull("serviceAddedAtMs"),
        firstSeenLikedAt = json.requireLong("firstSeenLikedAt"),
        lastSeenLikedAt = json.requireLong("lastSeenLikedAt"),
        unlikedAt = json.longOrNull("unlikedAt"),
    )

    fun readParams(json: JSONObject) = ParamsFact(
        changedAt = json.requireLong("changedAt"),
        parametersJson = json.requireString("parametersJson"),
        parametersHash = json.requireString("parametersHash"),
        previousJson = json.stringOrNull("previousJson"),
        current = json.optBoolean("current", false),
    )

    fun readMember(json: JSONObject) = MemberFact(
        track = readTrack(json),
        joinedAt = json.requireLong("joinedAt"),
        leftAt = json.longOrNull("leftAt"),
        joinReason = json.requireString("joinReason"),
        leaveReason = json.stringOrNull("leaveReason"),
        rankAtJoin = json.requireLong("rankAtJoin").toInt(),
        rankAtLeave = json.longOrNull("rankAtLeave")?.toInt(),
    )

    fun readSnapshot(json: JSONObject) = SnapshotFact(
        monthKey = json.requireString("monthKey"),
        label = json.requireString("label"),
        takenAt = json.requireLong("takenAt"),
        parametersJson = json.requireString("parametersJson"),
        playlistSize = json.requireLong("playlistSize").toInt(),
        trackCount = json.requireLong("trackCount").toInt(),
        exportedPlaylistId = json.stringOrNull("exportedPlaylistId"),
        exportedAt = json.longOrNull("exportedAt"),
    )

    fun readSnapshotEntry(json: JSONObject) = SnapshotEntryFact(
        monthKey = json.requireString("monthKey"),
        track = readTrack(json),
        rank = json.requireLong("rank").toInt(),
        score = json.optDouble("score", 0.0),
        serviceTrackId = json.stringOrNull("serviceTrackId"),
        title = json.stringOrNull("title"),
        artist = json.stringOrNull("artist"),
        inPlaylist = json.optBoolean("inPlaylist", false),
    )

    private fun readTrack(parent: JSONObject): TrackRef {
        val json = parent.optJSONObject("track")
            ?: throw Malformed("A line in that file names no track. Nothing has been changed.")
        val service = json.stringOrNull("service")
        val serviceTrackId = json.stringOrNull("serviceTrackId")
        if ((service == null) != (serviceTrackId == null)) {
            throw Malformed(
                "A line in that file names a service identifier without its service, or the " +
                    "other way round. Nothing has been changed.",
            )
        }
        return TrackRef(
            key = json.requireString("key"),
            title = json.stringOrNull("title"),
            artist = json.stringOrNull("artist"),
            album = json.stringOrNull("album"),
            durationMs = json.optLong("durationMs", 0L),
            service = service,
            serviceTrackId = serviceTrackId,
            isrc = json.stringOrNull("isrc"),
        )
    }

    private fun TrackRef.toJson(): JSONObject = JSONObject()
        .put("key", key)
        .putOrNull("title", title)
        .putOrNull("artist", artist)
        .putOrNull("album", album)
        .put("durationMs", durationMs)
        .putOrNull("service", service)
        .putOrNull("serviceTrackId", serviceTrackId)
        .putOrNull("isrc", isrc)

    // --- org.json, made to say what it means ---------------------------------
    //
    // `JSONObject.put(key, null)` removes the key, and `optString` answers ""
    // for both "absent" and "present and null". Both are fine defaults for a
    // web API and wrong here, where null is a value that means "not observed"
    // and is different from zero and from absent. These four keep that
    // distinction rather than each call site having to remember it.

    private fun JSONObject.putOrNull(key: String, value: Any?): JSONObject =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key)

    private fun JSONObject.requireString(key: String): String =
        stringOrNull(key) ?: throw Malformed(
            "A line in that file is missing \"$key\". Nothing has been changed.",
        )

    private fun JSONObject.requireLong(key: String): Long =
        longOrNull(key) ?: throw Malformed(
            "A line in that file is missing \"$key\". Nothing has been changed.",
        )
}
