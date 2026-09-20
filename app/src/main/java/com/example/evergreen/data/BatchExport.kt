package com.example.evergreen.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a **fact batch**: these facts, from this device, after this watermark.
 *
 * The other end of Phase 5a's transport, and the point of Phase 5b. A mirror
 * update stops being a 21 MiB database file and becomes a small text file
 * listing what is new. The format is specified in `docs/SYNC_FORMAT.md` and
 * implemented in [SyncBatch].
 *
 * **It reuses the create-document picker the backup button already uses.**
 * There is no third export path, no new permission and nothing new that can
 * leave the device — a batch is a file the user saves and moves themselves.
 *
 * ### What is watermarked, and what is not
 *
 * Only segments. They are append-only with ascending per-device ids, so
 * "everything after N" is a question with an answer — and, more importantly,
 * one the *receiver* can check against what it holds.
 *
 * Everything else is sent in full every time. Likes and Evergreen membership
 * change in place, so no ascending id could describe what is new about them;
 * and a watermark that only the *producer* remembers cannot be checked by
 * anyone, so a batch that quietly began after what the receiver had would lose
 * facts in silence. Sending the rest whole is always correct and costs file
 * size. See `docs/SYNC_FORMAT.md` §5.
 *
 * ### Streaming, not loading
 *
 * Every read is a keyset-paged page of [PAGE_SIZE], written out and dropped.
 * The first export on the Walkman is 77,000 segments on a device with limited
 * memory, and nothing here ever holds more than one page.
 */
object BatchExport {

    private const val TAG = "BatchExport"

    /**
     * Rows per query. The same order of magnitude as the streaming-history
     * import's 500, and for the same reason: small enough that a page is
     * nothing on the NW-A307, large enough that 77,000 rows is not 77,000
     * round trips.
     */
    private const val PAGE_SIZE = 500

    /** A reason the user can act on. Never a stack trace, never a row. */
    class Refused(message: String) : Exception(message)

    /**
     * What an export would contain, worked out before anything is written.
     *
     * Counts only — never a row. This is what the screen says before the user
     * picks a file, and what it reports afterwards beside the size.
     */
    data class Plan(
        val origins: List<SyncBatch.Origin>,
        val counts: Map<String, Int>,
        /** True when no watermark was remembered and this is the whole history. */
        val fromTheBeginning: Boolean,
    ) {
        val segments: Int get() = counts[SyncBatch.Type.SEGMENT] ?: 0
        val totalFacts: Int get() = counts.values.sum()

        /** The lowest watermark any origin starts from, for the screen to say. */
        fun describeWatermark(): String = when {
            fromTheBeginning -> "the beginning"
            origins.isEmpty() -> "nothing yet recorded"
            else -> origins.joinToString(", ") { "#${it.afterOriginId}" }
        }
    }

    /** What happened, for the confirmation line. */
    data class Written(val plan: Plan, val bytes: Long, val tookMs: Long)

    /**
     * Works out what would be exported, changing nothing.
     *
     * @param fromTheBeginning ignores the remembered watermarks and offers the
     *   whole history. That is a useful thing to be able to do — it is the only
     *   way to rebuild a mirror from batches alone — and a useful test of the
     *   format at its largest.
     */
    suspend fun plan(
        context: Context,
        fromTheBeginning: Boolean = false,
        database: EvergreenDatabase = EvergreenDatabase.get(context.applicationContext),
    ): Plan = withContext(Dispatchers.IO) {
        val sync = database.syncDao()
        val scoring = database.scoringDao()

        val origins = sync.originatingDeviceIds().map { deviceId ->
            val after = if (fromTheBeginning) {
                0L
            } else {
                scoring.syncStateMs(SyncKeys.SYNC_EXPORT_THROUGH_PREFIX + deviceId) ?: 0L
            }
            SyncBatch.Origin(
                deviceId = deviceId,
                afterOriginId = after,
                throughOriginId = sync.highestSegmentToExport(deviceId, after),
            )
        }

        val segments = origins.sumOf { sync.countSegmentsToExport(it.deviceId, it.afterOriginId) }

        Plan(
            origins = origins,
            counts = mapOf(
                SyncBatch.Type.SEGMENT to segments,
                SyncBatch.Type.LIKE_EVENT to sync.countLikeEvents(),
                SyncBatch.Type.LIKE to sync.countLikes(),
                SyncBatch.Type.PARAMS to sync.countParameterHistory(),
                SyncBatch.Type.MEMBER to sync.countMembership(),
                SyncBatch.Type.SNAPSHOT to sync.countSnapshots(),
                SyncBatch.Type.SNAPSHOT_ENTRY to sync.countSnapshotEntries(),
            ),
            fromTheBeginning = fromTheBeginning || origins.all { it.afterOriginId == 0L },
        )
    }

    /**
     * Writes the batch to [destination], then remembers how far it got.
     *
     * The watermarks are written **after** the file is closed, and only if
     * nothing threw. An export that failed half way leaves them alone, so the
     * next one covers the same ground again — which is harmless, because
     * applying a batch twice changes nothing. Recording them first and failing
     * afterwards would lose facts, which is not harmless at all, so the order
     * is not an accident.
     */
    suspend fun writeTo(
        context: Context,
        destination: Uri,
        plan: Plan,
        now: Long = System.currentTimeMillis(),
        database: EvergreenDatabase = EvergreenDatabase.get(context.applicationContext),
    ): Written = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val appContext = context.applicationContext
        val sync = database.syncDao()

        val stream = appContext.contentResolver.openOutputStream(destination)
            ?: throw Refused("Could not write to the file you chose.")

        val bytes = try {
            stream.use { out ->
                val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
                val batch = SyncBatch.Writer(writer)

                batch.header(
                    SyncBatch.Header(
                        formatVersion = SyncBatch.FORMAT_VERSION,
                        schemaVersion = EVERGREEN_SCHEMA_VERSION,
                        producerName = Build.MODEL,
                        takenAtMs = now,
                        origins = plan.origins,
                        counts = plan.counts,
                    ),
                )

                plan.origins.forEach { origin -> writeSegments(sync, batch, origin) }
                writeLikeEvents(sync, batch)
                writeLikes(sync, batch)
                writeParameters(database, sync, batch)
                writeMembership(sync, batch)
                writeSnapshots(sync, batch)

                writer.flush()
            }
            sizeOf(appContext, destination)
        } catch (failure: IOException) {
            throw Refused("The file could not be written. ${failure.message.orEmpty()}".trim())
        }

        // Only now, and only because nothing threw.
        val scoring = database.scoringDao()
        plan.origins.forEach { origin ->
            origin.throughOriginId?.let { through ->
                scoring.putSyncMs(SyncKeys.SYNC_EXPORT_THROUGH_PREFIX + origin.deviceId, through)
            }
        }
        scoring.putSyncMs(SyncKeys.SYNC_EXPORT_AT, now)

        Log.i(TAG, "exported ${plan.totalFacts} facts, $bytes bytes")
        Written(plan = plan, bytes = bytes, tookMs = System.currentTimeMillis() - startedAt)
    }

    // --- One section per fact type -------------------------------------------

    private suspend fun writeSegments(
        sync: SyncDao,
        batch: SyncBatch.Writer,
        origin: SyncBatch.Origin,
    ) {
        var cursor = origin.afterOriginId
        while (true) {
            val page = sync.segmentsToExport(origin.deviceId, cursor, PAGE_SIZE)
            if (page.isEmpty()) return
            page.forEach { row ->
                batch.segment(
                    SyncBatch.SegmentFact(
                        deviceId = row.deviceId,
                        // The row's own id *is* its originating id: this device
                        // assigned it, which is what `originRowId IS NULL`
                        // means in the query that selected it.
                        originRowId = row.id,
                        track = row.track(),
                        appPackage = row.appPackage,
                        startedAtMs = row.startedAtMs,
                        endedAtMs = row.endedAtMs,
                        startPositionMs = row.startPositionMs,
                        endPositionMs = row.endPositionMs,
                        listenedMs = row.listenedMs,
                        trackDurationMs = row.trackDurationMs,
                        forwardSeekCount = row.forwardSeekCount,
                        seekedForwardMs = row.seekedForwardMs,
                        endReason = row.endReason,
                        endJumpFromMs = row.endJumpFromMs,
                        endJumpToMs = row.endJumpToMs,
                        lastSampleAgeMs = row.lastSampleAgeMs,
                        createdAt = row.createdAt,
                        source = row.source,
                        sourceEndReason = row.sourceEndReason,
                    ),
                )
            }
            cursor = page.last().id
        }
    }

    private suspend fun writeLikeEvents(sync: SyncDao, batch: SyncBatch.Writer) {
        var cursor = 0L
        while (true) {
            val page = sync.likeEventsToExport(cursor, PAGE_SIZE)
            if (page.isEmpty()) return
            page.forEach { row ->
                batch.likeEvent(
                    SyncBatch.LikeEventFact(
                        track = row.track(),
                        event = row.event,
                        observedAt = row.observedAt,
                        serviceAddedAtMs = row.serviceAddedAtMs,
                    ),
                )
            }
            cursor = page.last().id
        }
    }

    private suspend fun writeLikes(sync: SyncDao, batch: SyncBatch.Writer) {
        var cursor = 0L
        while (true) {
            val page = sync.likesToExport(cursor, PAGE_SIZE)
            if (page.isEmpty()) return
            page.forEach { row ->
                batch.like(
                    SyncBatch.LikeFact(
                        track = row.track(),
                        serviceAddedAtMs = row.serviceAddedAtMs,
                        firstSeenLikedAt = row.firstSeenLikedAt,
                        lastSeenLikedAt = row.lastSeenLikedAt,
                        unlikedAt = row.unlikedAt,
                    ),
                )
            }
            cursor = page.last().id
        }
    }

    /**
     * The parameter log, with the entry in force marked `current`.
     *
     * Marking it is not decoration. A receiver that applied the facts but not
     * the settings would compute a different ranking from the same history,
     * which is the one outcome this phase exists to rule out.
     */
    private suspend fun writeParameters(
        database: EvergreenDatabase,
        sync: SyncDao,
        batch: SyncBatch.Writer,
    ) {
        // `SCORING_PARAMETERS` holds the settings themselves as JSON, not a
        // hash of them — the same canonical string a snapshot stores, so the
        // two can be matched to each other.
        val currentJson = database.scoringDao().syncStateText(SyncKeys.SCORING_PARAMETERS)
        var cursor = 0L
        var newest: ParameterHistoryEntity? = null
        val pages = mutableListOf<ParameterHistoryEntity>()
        while (true) {
            val page = sync.parameterHistoryToExport(cursor, PAGE_SIZE)
            if (page.isEmpty()) break
            pages += page
            newest = page.last()
            cursor = page.last().id
        }
        pages.forEach { entry ->
            batch.params(
                SyncBatch.ParamsFact(
                    changedAt = entry.changedAt,
                    parametersJson = entry.parametersJson,
                    parametersHash = entry.parametersHash,
                    previousJson = entry.previousJson,
                    // The newest entry is the one in force, unless the settings
                    // in `sync_state` say otherwise — which they do on a
                    // database whose parameters have never been changed, where
                    // the log is empty and the defaults are what is running.
                    current = entry.id == newest?.id &&
                        (currentJson == null || currentJson == entry.parametersJson),
                ),
            )
        }
    }

    private suspend fun writeMembership(sync: SyncDao, batch: SyncBatch.Writer) {
        var cursor = 0L
        while (true) {
            val page = sync.membershipToExport(cursor, PAGE_SIZE)
            if (page.isEmpty()) return
            page.forEach { row ->
                batch.member(
                    SyncBatch.MemberFact(
                        track = row.track(),
                        joinedAt = row.joinedAt,
                        leftAt = row.leftAt,
                        joinReason = row.joinReason,
                        leaveReason = row.leaveReason,
                        rankAtJoin = row.rankAtJoin,
                        rankAtLeave = row.rankAtLeave,
                    ),
                )
            }
            cursor = page.last().id
        }
    }

    /**
     * Each snapshot, immediately followed by its entries.
     *
     * The order is not required by the format — every entry names its month —
     * but it is what lets a receiver insert them as it streams, instead of
     * holding a whole ranking in memory waiting for the snapshot it belongs to.
     */
    private suspend fun writeSnapshots(sync: SyncDao, batch: SyncBatch.Writer) {
        sync.snapshotsToExport().forEach { snapshot ->
            batch.snapshot(
                SyncBatch.SnapshotFact(
                    monthKey = snapshot.monthKey,
                    label = snapshot.label,
                    takenAt = snapshot.takenAt,
                    parametersJson = snapshot.parametersJson,
                    playlistSize = snapshot.playlistSize,
                    trackCount = snapshot.trackCount,
                    exportedPlaylistId = snapshot.exportedPlaylistId,
                    exportedAt = snapshot.exportedAt,
                ),
            )
            var cursor = 0L
            while (true) {
                val page = sync.snapshotEntriesToExport(snapshot.id, cursor, PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { row ->
                    batch.snapshotEntry(
                        SyncBatch.SnapshotEntryFact(
                            monthKey = snapshot.monthKey,
                            track = row.track(),
                            rank = row.rank,
                            score = row.score,
                            serviceTrackId = row.entryServiceTrackId,
                            title = row.entryTitle,
                            artist = row.entryArtist,
                            inPlaylist = row.inPlaylist,
                        ),
                    )
                }
                cursor = page.last().id
            }
        }
    }

    // --- Plumbing -------------------------------------------------------------

    private fun SyncDao.SegmentExportRow.track() = SyncBatch.TrackRef(
        trackKey, title, artist, album, trackDuration, service, serviceTrackId, isrc,
    )

    private fun SyncDao.LikeEventExportRow.track() = SyncBatch.TrackRef(
        trackKey, title, artist, album, trackDuration, service, serviceTrackId, isrc,
    )

    private fun SyncDao.LikeExportRow.track() = SyncBatch.TrackRef(
        trackKey, title, artist, album, trackDuration, service, serviceTrackId, isrc,
    )

    private fun SyncDao.MemberExportRow.track() = SyncBatch.TrackRef(
        trackKey, title, artist, album, trackDuration, service, serviceTrackId, isrc,
    )

    private fun SyncDao.SnapshotEntryExportRow.track() = SyncBatch.TrackRef(
        trackKey, title, artist, album, trackDuration, service, serviceTrackId, isrc,
    )

    /** How big the file turned out, or 0 if the provider will not say. */
    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it > 0 } ?: 0L

    /**
     * A filename that sorts chronologically and says what it is.
     *
     * `.jsonl` rather than `.json`, because it is not one JSON document and
     * calling it one would mislead the first tool that tried to open it.
     */
    fun suggestedFileName(nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(nowMs))
        val device = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "device" }
        return "evergreen-batch-$device-$stamp.jsonl"
    }

    /** For the screens: a file size a person can read. */
    fun describeSize(bytes: Long): String = when {
        bytes <= 0 -> "an unknown size"
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "%.1f KiB".format(Locale.getDefault(), bytes / 1024.0)
        else -> "%.1f MiB".format(Locale.getDefault(), bytes / (1024.0 * 1024.0))
    }
}
