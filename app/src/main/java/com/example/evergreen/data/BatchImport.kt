package com.example.evergreen.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.example.evergreen.capture.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * Applies a **fact batch** to this device's database.
 *
 * The other half of [BatchExport], and the operation that turns a mirror update
 * from a 21 MiB file replacement into a few hundred kilobytes of inserts.
 *
 * ### It inserts. It does not replace.
 *
 * That is the whole difference from [MirrorImport], and everything else follows
 * from it. A mirror import stages a file, neutralises it and renames it over
 * the live database; there is no merge, so nothing has to be identified. A
 * delta lands *on top of* what is already here, so every fact has to be
 * nameable — which is what `docs/SYNC_FORMAT.md` §3 is for, and what schema v7
 * added the `originRowId` column for.
 *
 * ### Four refusals, and why each is a refusal rather than a best effort
 *
 * - **A file that is not a batch**, or a format version this build does not
 *   know. Same discipline as the mirror import's schema check.
 * - **No baseline.** A delta against an empty database, or against one holding
 *   nothing from the device that produced it, is a set of facts with nothing to
 *   attach to. Applying it would produce a database that looked complete and
 *   was not.
 * - **A gap.** A batch starting after the watermark this device holds means the
 *   facts in between exist nowhere on this device and never will. Taking the
 *   batch anyway would move the watermark past them, so the gap would be
 *   permanent *and* invisible.
 * - **A truncated file**, caught by comparing the header's counts with what was
 *   actually read.
 *
 * ### It is idempotent, and that is a property of the database
 *
 * Applying the same batch twice changes nothing: segments below the watermark
 * are skipped, and the unique index over `(deviceId, originRowId)` refuses any
 * that somehow get past. Everything else is content-addressed or an upsert.
 *
 * ### A mirror that has had deltas applied is still a mirror
 *
 * Nothing here touches mirror mode, the writer flag or the playlist id. Every
 * guard Phase 5a built still holds afterwards, and the home screen still says
 * what the device is.
 */
object BatchImport {

    private const val TAG = "BatchImport"

    /** A reason the user can act on. Never a stack trace, never a row. */
    class Refused(message: String) : Exception(message)

    /**
     * What a batch would add, read from its header alone.
     *
     * Cheap: the header is the first line, so this costs one line of a file
     * that may be 77,000 long. The confirmation can therefore name both sides
     * before anything is read, let alone written.
     */
    data class Preview(
        val producerName: String?,
        val takenAtMs: Long,
        val origins: List<OriginPreview>,
        val counts: Map<String, Int>,
    ) {
        fun describeProducer(): String = producerName ?: "another device"

        val incomingSegments: Int get() = counts[SyncBatch.Type.SEGMENT] ?: 0
        val totalFacts: Int get() = counts.values.sum()

        /** How many segments this device does not already hold, at most. */
        val newSegmentsAtMost: Int get() = incomingSegments
    }

    /** One origin in the batch, beside what this device holds from it. */
    data class OriginPreview(
        val deviceId: String,
        val afterOriginId: Long,
        val throughOriginId: Long?,
        /** What this device holds from that device, or null for nothing at all. */
        val heldThrough: Long?,
    )

    /** What actually happened. Counts only, never a row. */
    data class Applied(
        val preview: Preview,
        val segmentsAdded: Int,
        val segmentsAlreadyHeld: Int,
        val likeEventsAdded: Int,
        val likesWritten: Int,
        val parametersAdded: Int,
        val membershipAdded: Int,
        val snapshotsWritten: Int,
        val unknownLinesSkipped: Int,
        /**
         * Segments from two devices overlapping in wall-clock time on one
         * track, counted after the import. **A tripwire, not a merge rule.**
         *
         * Zero in normal use, because one person cannot listen to two things at
         * once. Rows capture already marked as remote are not counted, so this
         * going non-zero means the phantom fix has stopped working rather than
         * that it is doing its job. See `SyncDao.countCrossDeviceOverlaps`.
         */
        val crossDeviceOverlaps: Int,
        val tookMs: Long,
    ) {
        /** True when the batch was entirely made of things already here. */
        val changedNothing: Boolean
            get() = segmentsAdded == 0 && likeEventsAdded == 0 && parametersAdded == 0 &&
                membershipAdded == 0
    }

    /**
     * Reads the header and checks it against this database. Changes nothing.
     *
     * Both refusals that matter live here, before a single write: no baseline,
     * and a gap. Doing them in a first pass costs one line of the file and
     * means the destructive half never begins on a batch that cannot be
     * applied.
     */
    suspend fun preview(
        context: Context,
        uri: Uri,
        database: EvergreenDatabase = EvergreenDatabase.get(context.applicationContext),
    ): Preview = withContext(Dispatchers.IO) {
        val header = readHeader(context.applicationContext, uri)
        val sync = database.syncDao()

        val origins = header.origins.map { origin ->
            OriginPreview(
                deviceId = origin.deviceId,
                afterOriginId = origin.afterOriginId,
                throughOriginId = origin.throughOriginId,
                heldThrough = sync.watermarkFor(origin.deviceId),
            )
        }

        origins.forEach { origin ->
            if (origin.afterOriginId == 0L) return@forEach

            val held = origin.heldThrough ?: throw Refused(
                "That file is a list of *changes* since a point this device has never been " +
                    "at: it holds nothing at all recorded by that device. Import a full " +
                    "mirror from ${header.describeProducer()} first, then changes on top of " +
                    "it. Nothing has been changed.",
            )

            if (origin.afterOriginId > held) {
                val missing = origin.afterOriginId - held
                throw Refused(
                    "That file would leave a gap. It starts after play #${origin.afterOriginId} " +
                        "and this device holds up to #$held, so about $missing play(s) in " +
                        "between are in neither. Export the changes again from the beginning " +
                        "on ${header.describeProducer()}, or import a full mirror. Nothing " +
                        "has been changed.",
                )
            }
        }

        Preview(
            producerName = header.producerName,
            takenAtMs = header.takenAtMs,
            origins = origins,
            counts = header.counts,
        )
    }

    /**
     * Applies the batch, in one transaction.
     *
     * Whole or not at all. Half a batch is a watermark that lies: the facts in
     * the unapplied half would never arrive, because the next export would
     * start after them.
     *
     * The score cache is dropped at the end rather than updated. The facts
     * underneath it have changed, and a cache that survives a change to its
     * inputs is exactly the accumulation invariant 1a forbids. The next read
     * recomputes, which is the point — the same facts and parameters have to
     * give the same ranking here as on the device that sent them.
     */
    suspend fun apply(
        context: Context,
        uri: Uri,
        preview: Preview,
        now: Long = System.currentTimeMillis(),
        database: EvergreenDatabase = EvergreenDatabase.get(context.applicationContext),
    ): Applied = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val appContext = context.applicationContext
        val watermarks = preview.origins.associate { it.deviceId to (it.heldThrough ?: 0L) }

        val tally = Tally()

        try {
            openReader(appContext, uri).use { source ->
                val reader = SyncBatch.Reader(source)
                val header = reader.readHeader()
                val thisDeviceId = DeviceId.get(appContext)

                // One transaction around the whole file. On the NW-A307 that
                // also matters for speed: 77,000 individual commits is the
                // difference between a minute and an afternoon, the same
                // lesson the streaming-history import learned.
                database.withTransaction {
                    // Learn who wrote this file, so the hand-over screen can
                    // offer the role to it by name rather than by UUID. Before
                    // the facts, because applyRole falls back to this book when
                    // a role line carries no name.
                    header.producerDeviceId?.let { producer ->
                        database.importDao().rememberDeviceName(producer, header.producerName)
                    }

                    tally.unknown = reader.forEachFact { type, json ->
                        applyFact(database, type, json, watermarks, tally, thisDeviceId)
                    }
                    verifyCounts(preview, tally)
                    adoptNewestParameters(database)
                    database.scoringDao().dropScoreCache()
                    database.scoringDao().putSyncMs(SyncKeys.SYNC_IMPORT_AT, now)
                }
            }
        } catch (failure: SyncBatch.Malformed) {
            throw Refused(failure.message.orEmpty())
        } catch (failure: IOException) {
            throw Refused("That file could not be read. Nothing has been changed.")
        }

        // Counted after the transaction, deliberately. It is a report about the
        // database as it now stands rather than about this file, and running it
        // outside keeps a read-only tripwire off the path that has to either
        // commit whole or not at all.
        val overlaps = database.syncDao().countCrossDeviceOverlaps()

        Log.i(
            TAG,
            "delta applied: ${tally.segmentsAdded} new segments, " +
                "${tally.segmentsAlreadyHeld} already held, $overlaps overlapping",
        )

        Applied(
            preview = preview,
            crossDeviceOverlaps = overlaps,
            segmentsAdded = tally.segmentsAdded,
            segmentsAlreadyHeld = tally.segmentsAlreadyHeld,
            likeEventsAdded = tally.likeEventsAdded,
            likesWritten = tally.likesWritten,
            parametersAdded = tally.parametersAdded,
            membershipAdded = tally.membershipAdded,
            snapshotsWritten = tally.snapshotsWritten,
            unknownLinesSkipped = tally.unknown,
            tookMs = System.currentTimeMillis() - startedAt,
        )
    }

    // --- Applying one fact ----------------------------------------------------

    private class Tally {
        var seen = mutableMapOf<String, Int>()
        var segmentsAdded = 0
        var segmentsAlreadyHeld = 0
        var likeEventsAdded = 0
        var likesWritten = 0
        var parametersAdded = 0
        var membershipAdded = 0
        var roleChanged = false

        /**
         * The month whose incoming snapshot lost to the one already stored, so
         * its entries are skipped too. Null the rest of the time.
         */
        var skippingSnapshotFor: String? = null
        var snapshotsWritten = 0
        var unknown = 0

        /**
         * The snapshot the entries being read belong to.
         *
         * Month, local row id, and when it was taken. The last of those is what
         * an entry's track is dated with if this device has never seen it: a
         * snapshot entry carries no time of its own, and using "now" would have
         * this database claim it first saw a song at the moment a file was
         * opened.
         */
        var openSnapshot: Triple<String, Long, Long>? = null

        fun count(type: String) {
            seen[type] = (seen[type] ?: 0) + 1
        }
    }

    /** @return false when the line's type is not one this build knows. */
    private suspend fun applyFact(
        database: EvergreenDatabase,
        type: String,
        json: JSONObject,
        watermarks: Map<String, Long>,
        tally: Tally,
        thisDeviceId: String,
    ): Boolean {
        when (type) {
            SyncBatch.Type.SEGMENT -> {
                tally.count(type)
                applySegment(database, SyncBatch.readSegment(json), watermarks, tally)
            }

            SyncBatch.Type.LIKE_EVENT -> {
                tally.count(type)
                applyLikeEvent(database, SyncBatch.readLikeEvent(json), tally)
            }

            SyncBatch.Type.LIKE -> {
                tally.count(type)
                applyLike(database, SyncBatch.readLike(json), tally)
            }

            SyncBatch.Type.PARAMS -> {
                tally.count(type)
                applyParams(database, SyncBatch.readParams(json), tally)
            }

            SyncBatch.Type.MEMBER -> {
                tally.count(type)
                applyMember(database, SyncBatch.readMember(json), tally)
            }

            SyncBatch.Type.SNAPSHOT -> {
                tally.count(type)
                applySnapshot(database, SyncBatch.readSnapshot(json), tally)
            }

            SyncBatch.Type.SNAPSHOT_ENTRY -> {
                tally.count(type)
                applySnapshotEntry(database, SyncBatch.readSnapshotEntry(json), tally)
            }

            SyncBatch.Type.ROLE -> {
                tally.count(type)
                applyRole(database, SyncBatch.readRole(json), tally, thisDeviceId)
            }

            else -> return false
        }
        return true
    }

    /**
     * Inserts a segment, unless this device already holds it.
     *
     * Two guards, and they are not redundant. The watermark is where
     * idempotence actually comes from and it costs nothing — a comparison
     * against a number read once. The unique index is the backstop for the case
     * where the watermark is somehow wrong, and it is the one that makes
     * idempotence a property of the *database* rather than of this function
     * being right.
     *
     * `recordSegment` does the track resolution, exactly as it does for capture
     * and for the streaming-history import: the service identifier wins when it
     * is known, and `trackKey` is only a fallback. Reversing that order forks
     * the same song into two canonical tracks, which is a bug this project has
     * already had.
     */
    private suspend fun applySegment(
        database: EvergreenDatabase,
        fact: SyncBatch.SegmentFact,
        watermarks: Map<String, Long>,
        tally: Tally,
    ) {
        val held = watermarks[fact.deviceId] ?: 0L
        if (fact.originRowId <= held) {
            tally.segmentsAlreadyHeld++
            return
        }

        val resolved = database.resolveTrackFor(fact.track, fact.endedAtMs)

        val inserted = database.syncDao().insertSegmentIfNew(
            ListeningSegmentEntity(
                trackId = resolved.trackId,
                serviceTrackRowId = resolved.serviceTrackRowId,
                appPackage = fact.appPackage,
                deviceId = fact.deviceId,
                startedAtMs = fact.startedAtMs,
                endedAtMs = fact.endedAtMs,
                startPositionMs = fact.startPositionMs,
                endPositionMs = fact.endPositionMs,
                listenedMs = fact.listenedMs,
                trackDurationMs = fact.trackDurationMs,
                forwardSeekCount = fact.forwardSeekCount,
                seekedForwardMs = fact.seekedForwardMs,
                endReason = runCatching { EndReason.valueOf(fact.endReason) }
                    .getOrDefault(EndReason.UNKNOWN),
                endJumpFromMs = fact.endJumpFromMs,
                endJumpToMs = fact.endJumpToMs,
                lastSampleAgeMs = fact.lastSampleAgeMs,
                createdAt = fact.createdAt,
                source = runCatching { SegmentSource.valueOf(fact.source) }
                    .getOrDefault(SegmentSource.CAPTURE),
                sourceEndReason = fact.sourceEndReason,
                originRowId = fact.originRowId,
                // An unrecognised value becomes null -- "not observed" -- and
                // so scores normally. That is the right fallback for a name
                // this build has not heard of, and it is safe only because a
                // batch that could be hiding *unmarked* phantoms is refused by
                // its formatVersion long before it reaches here.
                playbackType = fact.playbackType
                    ?.let { name -> runCatching { PlaybackType.valueOf(name) }.getOrNull() },
                utcOffsetMinutes = fact.utcOffsetMinutes,
            ),
        )
        if (inserted == -1L) tally.segmentsAlreadyHeld++ else tally.segmentsAdded++
    }

    private suspend fun applyLikeEvent(
        database: EvergreenDatabase,
        fact: SyncBatch.LikeEventFact,
        tally: Tally,
    ) {
        val serviceTrackRowId = database.resolveTrackFor(fact.track, fact.observedAt)
            .serviceTrackRowId ?: return
        val event = runCatching { LikeEventType.valueOf(fact.event) }.getOrNull() ?: return
        val added = database.syncDao().applyLikeEvent(
            serviceTrackRowId = serviceTrackRowId,
            event = event,
            observedAt = fact.observedAt,
            serviceAddedAtMs = fact.serviceAddedAtMs,
        )
        if (added) tally.likeEventsAdded++
    }

    private suspend fun applyLike(
        database: EvergreenDatabase,
        fact: SyncBatch.LikeFact,
        tally: Tally,
    ) {
        val serviceTrackRowId = database.resolveTrackFor(fact.track, fact.firstSeenLikedAt)
            .serviceTrackRowId ?: return
        database.syncDao().applyLikeState(
            serviceTrackRowId = serviceTrackRowId,
            serviceAddedAtMs = fact.serviceAddedAtMs,
            firstSeenLikedAt = fact.firstSeenLikedAt,
            lastSeenLikedAt = fact.lastSeenLikedAt,
            unlikedAt = fact.unlikedAt,
        )
        tally.likesWritten++
    }

    /**
     * Applies a parameter log entry, and the settings themselves if this is the
     * entry in force.
     *
     * The second half is what makes the ranking match. A receiver that took the
     * facts but not the settings would score the same history differently, and
     * the disagreement would look like a bug in the scoring rather than a
     * missing line in a file.
     */
    private suspend fun applyParams(
        database: EvergreenDatabase,
        fact: SyncBatch.ParamsFact,
        tally: Tally,
    ) {
        val added = database.syncDao().applyParameterEntry(
            ParameterHistoryEntity(
                changedAt = fact.changedAt,
                parametersJson = fact.parametersJson,
                parametersHash = fact.parametersHash,
                previousJson = fact.previousJson,
            ),
        )
        if (added) tally.parametersAdded++
        // `fact.current` is deliberately *not* acted on here. Until Phase 5c
        // the producer's current settings were simply adopted, which was right
        // while facts flowed one way: a mirror should score under the writer's
        // settings. With both devices producing changes it is wrong, and
        // silently so -- a batch taken before this device's own change would
        // roll that change back on import. Which settings are in force is now
        // derived from the merged log once every fact has landed; see
        // [adoptNewestParameters].
    }

    /**
     * Claims the writer role, or learns that somebody else holds it.
     *
     * **This is the only way a device ever becomes the writer**, apart from the
     * forced take-over on the settings screen. The releasing device stood down
     * the moment it pressed the button; this is the other half, and the gap
     * between them is the window in which nobody writes. That window is the
     * design working, not a flaw in it: the alternative — a device announcing
     * "I am the writer now" and the other standing down on hearing it — has
     * both of them holding the role for exactly as long, which is the failure
     * this whole mechanism exists to prevent.
     *
     * [WriterRole.resolve] keeps the later decision, so importing the same
     * batch twice changes nothing and importing two batches in either order
     * ends the same way.
     */
    private suspend fun applyRole(
        database: EvergreenDatabase,
        fact: SyncBatch.RoleFact,
        tally: Tally,
        thisDeviceId: String,
    ) {
        // The playlist the account uses, adopted only when this device has
        // none. Independent of who wins the role below: a device that will
        // never write still benefits from knowing which playlist is Evergreen,
        // and a device that already has one must not have it repointed.
        fact.playlistId?.let { playlistId ->
            if (database.scoringDao().syncStateText(SyncKeys.EVERGREEN_PLAYLIST_ID) == null) {
                database.scoringDao().putSyncText(SyncKeys.EVERGREEN_PLAYLIST_ID, playlistId)
            }
        }

        val incoming = WriterRole(fact.holderDeviceId, fact.decidedAtMs)
        val local = database.importDao().writerRole(thisDeviceId)
        val winner = WriterRole.resolve(local, incoming)
        if (winner == null || winner == local) return
        database.importDao().putWriterRole(
            winner,
            holderName = fact.holderName ?: database.importDao().deviceNameOf(winner.holderDeviceId),
        )
        tally.roleChanged = true
    }

    /**
     * Picks the settings in force from the merged parameter log: **the latest
     * `changedAt` wins.**
     *
     * Run once, after every fact in the batch has been applied and inside the
     * same transaction, rather than per `params` line. Deriving it from the
     * log rather than believing whichever file arrived last is what makes the
     * answer independent of import order -- the property `docs/SYNC_FORMAT.md`
     * section 5 claims for the whole format, and which the old per-line
     * behaviour quietly did not have.
     *
     * `parameter_history` is append-only and both devices' entries survive
     * regardless, so this only decides which one is *in force*. That matters
     * for the failure mode: if clock skew ever picks the wrong one, the log
     * still holds both, the settings screen shows the wrong value plainly, and
     * changing it again fixes it. Visible and recoverable, which is not true
     * of much else that could go wrong here.
     */
    private suspend fun adoptNewestParameters(database: EvergreenDatabase) {
        val newest = database.syncDao().newestParameterEntry() ?: return
        database.scoringDao().putSyncText(SyncKeys.SCORING_PARAMETERS, newest.parametersJson)
    }

    private suspend fun applyMember(
        database: EvergreenDatabase,
        fact: SyncBatch.MemberFact,
        tally: Tally,
    ) {
        val trackId = database.resolveTrackFor(fact.track, fact.joinedAt).trackId
        val added = database.syncDao().applyMembership(
            EvergreenMembershipEntity(
                trackId = trackId,
                joinedAt = fact.joinedAt,
                leftAt = fact.leftAt,
                joinReason = runCatching { MembershipReason.valueOf(fact.joinReason) }
                    .getOrDefault(MembershipReason.JOINED_BY_RANK),
                leaveReason = fact.leaveReason?.let {
                    runCatching { MembershipReason.valueOf(it) }.getOrNull()
                },
                rankAtJoin = fact.rankAtJoin,
                rankAtLeave = fact.rankAtLeave,
            ),
        )
        if (added) tally.membershipAdded++
    }

    private suspend fun applySnapshot(
        database: EvergreenDatabase,
        fact: SyncBatch.SnapshotFact,
        tally: Tally,
    ) {
        val id = database.syncDao().applySnapshot(
            SnapshotEntity(
                monthKey = fact.monthKey,
                label = fact.label,
                takenAt = fact.takenAt,
                parametersJson = fact.parametersJson,
                playlistSize = fact.playlistSize,
                trackCount = fact.trackCount,
                exportedPlaylistId = fact.exportedPlaylistId,
                exportedAt = fact.exportedAt,
            ),
        )
        if (id == null) {
            // The stored snapshot for this month is the one to keep, so the
            // entries that follow belong to the loser. Marked rather than
            // forgotten: `applySnapshotEntry` falls back to a lookup by month
            // when it sees no open snapshot, which would silently graft the
            // loser's ranking onto the winner.
            tally.openSnapshot = null
            tally.skippingSnapshotFor = fact.monthKey
            return
        }
        tally.skippingSnapshotFor = null
        tally.openSnapshot = Triple(fact.monthKey, id, fact.takenAt)
        tally.snapshotsWritten++
    }

    private suspend fun applySnapshotEntry(
        database: EvergreenDatabase,
        fact: SyncBatch.SnapshotEntryFact,
        tally: Tally,
    ) {
        if (tally.skippingSnapshotFor == fact.monthKey) return
        val open = tally.openSnapshot
        val snapshotId: Long
        val takenAt: Long
        if (open != null && open.first == fact.monthKey) {
            snapshotId = open.second
            takenAt = open.third
        } else {
            // Out of order, which the format permits: every entry names its
            // month. Costs one lookup rather than being a failure.
            snapshotId = database.syncDao().findSnapshotId(fact.monthKey)
                ?: throw SyncBatch.Malformed(
                    "That file has a snapshot entry for ${fact.monthKey} but no snapshot to " +
                        "put it in. Nothing has been changed.",
                )
            takenAt = database.syncDao().snapshotTakenAt(snapshotId) ?: 0L
        }
        val trackId = database.resolveTrackFor(fact.track, takenAt).trackId
        database.syncDao().insertSnapshotEntry(
            SnapshotEntryEntity(
                snapshotId = snapshotId,
                trackId = trackId,
                rank = fact.rank,
                score = fact.score,
                serviceTrackId = fact.serviceTrackId,
                title = fact.title,
                artist = fact.artist,
                inPlaylist = fact.inPlaylist,
            ),
        )
    }

    // --- Plumbing -------------------------------------------------------------

    /**
     * Refuses a file that turned out shorter than its header claimed.
     *
     * A truncated batch is the most plausible way this goes wrong in practice —
     * a copy interrupted, a cloud folder half synced — and it is the one
     * failure that would otherwise look like success and move the watermark
     * past facts that never arrived.
     */
    private fun verifyCounts(preview: Preview, tally: Tally) {
        preview.counts.forEach { (type, expected) ->
            val seen = tally.seen[type] ?: 0
            if (seen != expected) {
                throw SyncBatch.Malformed(
                    "That file says it holds $expected \"$type\" record(s) and $seen were " +
                        "read, so it is incomplete — most likely the copy was interrupted. " +
                        "Copy it across again. Nothing has been changed.",
                )
            }
        }
    }

    private fun readHeader(context: Context, uri: Uri): SyncBatch.Header = try {
        openReader(context, uri).use { SyncBatch.Reader(it).readHeader() }
    } catch (failure: SyncBatch.Malformed) {
        throw Refused(failure.message.orEmpty())
    } catch (failure: IOException) {
        throw Refused("That file could not be read. Nothing has been changed.")
    }

    private fun openReader(context: Context, uri: Uri): BufferedReader {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the file you picked.")
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
    }
}

/** Where a [SyncBatch.TrackRef] landed in *this* database's row ids. */
private data class ResolvedTrack(val trackId: Long, val serviceTrackRowId: Long?)

/**
 * Finds or creates the rows a fact's track names, through the path that already
 * exists.
 *
 * Deliberately the *same* resolution the like sync and capture use, rather than
 * a second one written for sync. A service identifier is exact; `trackKey` is a
 * guess assembled from title, artist and album, and those change under you — a
 * "Remastered" suffix appears, an album gets renamed, a device reports metadata
 * in another language. So the service row wins when it is known, and `trackKey`
 * is only the fallback. Two paths that disagreed about that order would fork
 * the same song into two canonical tracks depending on which saw it first,
 * which is a bug this project has already had and fixed once.
 *
 * A fact with no service identifier still names a song, and resolving it by key
 * is what keeps those segments attached to the right track rather than dropped.
 *
 * @param at the fact's own timestamp, used as `firstSeenAt` for a track this
 *   device has never seen. The *other* device's observation time, not now:
 *   claiming to have first seen a track at the moment a file was opened would
 *   be this database inventing an observation.
 *
 * One consequence worth knowing rather than fixing: the shared resolution also
 * sets `lastSeenAt` from `at`, so a batch's later sections — which are ordered
 * by type rather than by time — can move a track's `lastSeenAt` backwards.
 * Nothing reads it. It is bookkeeping on `tracks` and `service_tracks`, not a
 * fact, and no part of the scoring touches either column; the alternative is
 * changing a Phase 2 path that four other callers depend on, to tidy a number
 * nobody looks at.
 */
private suspend fun EvergreenDatabase.resolveTrackFor(
    track: SyncBatch.TrackRef,
    at: Long,
): ResolvedTrack {
    val service = track.service
    val serviceTrackId = track.serviceTrackId
    if (service != null && serviceTrackId != null) {
        val rowId = spotifyDao().resolveServiceTrack(
            trackKey = track.key,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            service = service,
            serviceTrackId = serviceTrackId,
            isrc = track.isrc,
            now = at,
        )
        return ResolvedTrack(syncDao().trackIdOfServiceTrack(rowId), rowId)
    }
    return ResolvedTrack(
        trackId = syncDao().resolveTrackByKeyOnly(
            trackKey = track.key,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            firstSeenAt = at,
        ),
        serviceTrackRowId = null,
    )
}
