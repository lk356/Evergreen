package com.example.evergreen.data

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.capture.DeviceId
import com.example.evergreen.scoring.ScoreService
import com.example.evergreen.scoring.ScoringParameters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Steps 5, 6 and 7 of `docs/PHASE5B_PROMPT.md`: a batch of facts moves between
 * two databases, and applying it is safe however many times it happens and in
 * whatever order.
 *
 * Everything here works on temporary files and databases of this test's own.
 * Both `BatchExport` and `BatchImport` take their database as a parameter for
 * exactly that reason — a function that resolves its own dependencies cannot be
 * tested against a different one, and the test that appears to do so is quietly
 * asserting about something else.
 *
 * **The test this file exists for is [deltaGivesTheSameRankingAsTheWholeThing].**
 * It is Phase 5b's equivalent of `CrossDeviceRankingTest`: a database built by
 * full copy and a database built by older copy plus delta have to rank
 * identically, or the phase has not delivered what it claims.
 */
/** A stable install id for these tests, standing in for DeviceId.get(). */
private const val TEST_DEVICE_ID = "test-device-id"

@RunWith(AndroidJUnit4::class)
class BatchSyncTest {

    private lateinit var context: Context
    private lateinit var writer: EvergreenDatabase
    private lateinit var mirror: EvergreenDatabase
    private val files = mutableListOf<File>()

    /** The writer's install id, and the origin of everything it records. */
    private val walkman = "device-walkman"

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_780_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        writer = freshDatabase("batch-test-writer.db")
        mirror = freshDatabase("batch-test-mirror.db")
    }

    @After
    fun tearDown() {
        writer.close()
        mirror.close()
        files.forEach { file ->
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }

    private fun freshDatabase(name: String): EvergreenDatabase {
        val file = File(context.cacheDir, name)
        files += file
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        return Room.databaseBuilder(context, EvergreenDatabase::class.java, file.absolutePath)
            .build()
    }

    private fun tempFile(name: String): File {
        val file = File(context.cacheDir, name)
        files += file
        // All three, not just the database: a Room handle opened on this path
        // earlier leaves a -wal beside it, and `VACUUM INTO` refuses to write
        // where anything already is.
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        return file
    }

    // --- Building a writer database ------------------------------------------

    /**
     * Records one play on the writer, the way capture would.
     *
     * `originRowId` is left null, which is what "this device recorded it"
     * means. The export turns the row's own id into the originating id, and
     * that pairing is the segment's global name.
     */
    private suspend fun play(
        index: Int,
        at: Long,
        listenedMs: Long = 180_000,
        durationMs: Long = 200_000,
        deviceId: String = walkman,
        playbackType: PlaybackType? = null,
        utcOffsetMinutes: Int? = null,
    ) {
        val title = "Track $index"
        writer.dao().recordSegment(
            trackKey = trackKeyOf(title, "Artist", "Album"),
            title = title,
            artist = "Artist",
            album = "Album",
            durationMs = durationMs,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:t$index",
            segment = ListeningSegmentEntity(
                trackId = 0,
                serviceTrackRowId = null,
                appPackage = "com.spotify.music",
                deviceId = deviceId,
                startedAtMs = at,
                endedAtMs = at + listenedMs,
                startPositionMs = 0,
                endPositionMs = listenedMs,
                listenedMs = listenedMs,
                trackDurationMs = durationMs,
                forwardSeekCount = 0,
                seekedForwardMs = 0,
                endReason = EndReason.TRACK_CHANGE,
                endJumpFromMs = null,
                endJumpToMs = null,
                lastSampleAgeMs = 100,
                createdAt = at + listenedMs,
                playbackType = playbackType,
                utcOffsetMinutes = utcOffsetMinutes,
            ),
        )
    }

    /** One row as the streaming-history import writes them. */
    private suspend fun playImported(index: Int, at: Long) {
        val title = "Track $index"
        writer.dao().recordSegment(
            trackKey = trackKeyOf(title, "Artist", "Album"),
            title = title,
            artist = "Artist",
            album = "Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:t$index",
            segment = ListeningSegmentEntity(
                trackId = 0,
                serviceTrackRowId = null,
                appPackage = "com.spotify.music",
                deviceId = "spotify-history-import",
                startedAtMs = at,
                endedAtMs = at + 180_000,
                startPositionMs = null,
                endPositionMs = null,
                listenedMs = 180_000,
                trackDurationMs = 200_000,
                forwardSeekCount = null,
                seekedForwardMs = null,
                endReason = EndReason.IMPORTED,
                endJumpFromMs = null,
                endJumpToMs = null,
                lastSampleAgeMs = null,
                createdAt = at,
                source = SegmentSource.IMPORT_SPOTIFY_HISTORY,
            ),
        )
    }

    private suspend fun like(index: Int, at: Long) {
        val title = "Track $index"
        val rowId = writer.spotifyDao().resolveServiceTrack(
            trackKey = trackKeyOf(title, "Artist", "Album"),
            title = title,
            artist = "Artist",
            album = "Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = "spotify:track:t$index",
            isrc = null,
            now = at,
        )
        writer.spotifyDao().recordLiked(rowId, serviceAddedAtMs = at - 30 * day, now = at)
    }

    /** Six liked tracks with a spread of listening, plus the settings in force. */
    private suspend fun buildBaseline() {
        (1..6).forEach { index -> like(index, t0) }
        (1..6).forEach { index ->
            repeat(index) { n -> play(index, t0 + n * day, listenedMs = 180_000) }
        }
        writer.scoringDao().putScoringParameters(ScoringParameters(), t0)
    }

    // --- Moving a batch ------------------------------------------------------

    private suspend fun exportTo(file: File, fromTheBeginning: Boolean = false): Long {
        val plan = BatchExport.plan(context, fromTheBeginning, writer)
        BatchExport.writeTo(context, file.toUri(), plan, now = t0, database = writer)
        return file.length()
    }

    private suspend fun importInto(
        target: EvergreenDatabase,
        file: File,
    ): BatchImport.Applied {
        val preview = BatchImport.preview(context, file.toUri(), target)
        return BatchImport.apply(context, file.toUri(), preview, now = t0, database = target)
    }

    /** A whole-file copy of the writer, the Phase 5a way. */
    private fun copyWholeDatabase(into: EvergreenDatabase) = runBlocking {
        val snapshot = tempFile("batch-test-snapshot.db")
        writer.openHelper.writableDatabase
            .execSQL("VACUUM INTO ?", arrayOf<Any>(snapshot.absolutePath))
        // Copy row by row rather than by file, because `into` is an open Room
        // handle this test keeps using. The point is a database holding the
        // same facts, not the file mechanics, which MirrorImportTest covers.
        val source = Room.databaseBuilder(
            context,
            EvergreenDatabase::class.java,
            snapshot.absolutePath,
        ).build()
        try {
            copyFacts(source, into)
        } finally {
            source.close()
        }
    }

    /**
     * Copies every fact from one database to another through a batch from the
     * beginning, which is the format's own definition of "everything".
     */
    private suspend fun copyFacts(source: EvergreenDatabase, into: EvergreenDatabase) {
        val file = tempFile("batch-test-whole-${into.hashCode()}.jsonl")
        val plan = BatchExport.plan(context, fromTheBeginning = true, database = source)
        BatchExport.writeTo(context, file.toUri(), plan, now = t0, database = source)
        val preview = BatchImport.preview(context, file.toUri(), into)
        BatchImport.apply(context, file.toUri(), preview, now = t0, database = into)
    }

    /**
     * The ranking as **titles** in order, never as row ids.
     *
     * Row ids are local to a database, so comparing two databases by them would
     * compare nothing. Titles are content, which is the same reason the batch
     * format sends content rather than ids.
     */
    private suspend fun ranking(database: EvergreenDatabase): List<String?> =
        ScoreService(database).ranking(nowMs = t0 + 60 * day, force = true)
            .tracks
            .map { titleOf(database, it.trackId) }

    private fun titleOf(database: EvergreenDatabase, trackId: Long): String? =
        database.openHelper.readableDatabase
            .query("SELECT title FROM tracks WHERE id = ?", arrayOf<Any>(trackId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private suspend fun segmentCount(database: EvergreenDatabase) =
        database.dao().segmentCountOnce()

    // --- Export --------------------------------------------------------------

    @Test
    fun anExportCarriesEveryOriginItHolds() = runBlocking {
        buildBaseline()
        // A second origin, exactly as the streaming-history import produces.
        play(99, t0, deviceId = "spotify-history-import")

        val plan = BatchExport.plan(context, fromTheBeginning = true, database = writer)

        val origins = plan.origins.map { it.deviceId }.toSet()
        assertTrue(
            "both the device's own facts and the imported ones: $origins",
            origins.containsAll(setOf(walkman, "spotify-history-import")),
        )
        // Since Phase 5c this device's own install id is always an origin too,
        // even when it has recorded nothing under it -- otherwise a device
        // holding only received rows could not export its likes at all. It
        // carries no segments here, because this fixture records everything
        // under `walkman`.
        val own = DeviceId.get(context)
        assertTrue("this device names itself: $origins", own in origins)
        assertNull(
            "and it offers no segments, having recorded none",
            plan.origins.single { it.deviceId == own }.throughOriginId,
        )
        assertTrue(plan.segments > 0)
    }

    @Test
    fun theSecondExportOffersOnlyWhatIsNew() = runBlocking {
        buildBaseline()
        val first = tempFile("batch-first.jsonl")
        exportTo(first, fromTheBeginning = true)
        val before = BatchExport.plan(context, false, writer).segments
        assertEquals("nothing new immediately afterwards", 0, before)

        play(1, t0 + 40 * day)
        play(2, t0 + 41 * day)

        val plan = BatchExport.plan(context, false, writer)

        assertEquals(2, plan.segments)
        assertFalse(plan.fromTheBeginning)
    }

    // --- Import: the refusals -------------------------------------------------

    @Test
    fun aDeltaAgainstAnEmptyDatabaseIsRefused() = runBlocking {
        buildBaseline()
        exportTo(tempFile("batch-baseline.jsonl"), fromTheBeginning = true)
        play(1, t0 + 40 * day)
        val delta = tempFile("batch-delta.jsonl")
        exportTo(delta)

        val refusal = runCatching {
            BatchImport.preview(context, delta.toUri(), mirror)
        }.exceptionOrNull()

        assertTrue(refusal is BatchImport.Refused)
        assertTrue(
            "it says a full mirror has to come first",
            refusal!!.message.orEmpty().contains("full mirror"),
        )
        assertEquals("and nothing was added", 0, segmentCount(mirror))
    }

    /**
     * The refusal that stops facts disappearing silently.
     *
     * A batch starting after what the receiver holds means the plays in between
     * exist nowhere on this device. Taking it anyway would move the watermark
     * past them, so the gap would be permanent *and* invisible — which is the
     * failure mode this whole design is built to rule out.
     */
    @Test
    fun aBatchThatWouldLeaveAGapIsRefusedAndSaysWhatIsMissing() = runBlocking {
        buildBaseline()
        copyWholeDatabase(mirror)
        val heldThrough = mirror.syncDao().watermarkFor(walkman)
        assertNotNull(heldThrough)

        // Two more plays on the writer, exported as *two* batches — and only
        // the second one is offered to the mirror.
        play(1, t0 + 40 * day)
        exportTo(tempFile("batch-skipped.jsonl"))
        play(2, t0 + 41 * day)
        val second = tempFile("batch-second.jsonl")
        exportTo(second)

        val refusal = runCatching {
            BatchImport.preview(context, second.toUri(), mirror)
        }.exceptionOrNull()

        assertTrue(refusal is BatchImport.Refused)
        val message = refusal!!.message.orEmpty()
        assertTrue("it says there is a gap", message.contains("gap"))
        assertTrue("and how much is missing", message.contains("play(s)"))
    }

    @Test
    fun aFileThatIsNotABatchIsRefused() = runBlocking {
        val notABatch = tempFile("not-a-batch.jsonl")
        notABatch.writeText("title,artist\nnot a batch,at all\n")

        val refusal = runCatching {
            BatchImport.preview(context, notABatch.toUri(), mirror)
        }.exceptionOrNull()

        assertTrue(refusal is BatchImport.Refused)
    }

    /** A file cut short is the most likely real failure, and it must not pass. */
    @Test
    fun aTruncatedBatchIsRefusedAndChangesNothing() = runBlocking {
        buildBaseline()
        val whole = tempFile("batch-whole.jsonl")
        exportTo(whole, fromTheBeginning = true)
        val truncated = tempFile("batch-truncated.jsonl")
        truncated.writeText(whole.readLines().let { it.take(it.size / 2) }.joinToString("\n") + "\n")

        val preview = BatchImport.preview(context, truncated.toUri(), mirror)
        val refusal = runCatching {
            BatchImport.apply(context, truncated.toUri(), preview, now = t0, database = mirror)
        }.exceptionOrNull()

        assertTrue(refusal is BatchImport.Refused)
        assertTrue(refusal!!.message.orEmpty().contains("incomplete"))
        assertEquals("the transaction rolled back", 0, segmentCount(mirror))
    }

    // --- Import: idempotence and order ----------------------------------------

    /**
     * The property the whole design rests on.
     *
     * Not "it does not crash": the database afterwards has to be identical, row
     * for row, to the database after one application.
     */
    @Test
    fun applyingTheSameBatchTwiceChangesNothing() = runBlocking {
        buildBaseline()
        val whole = tempFile("batch-idem.jsonl")
        exportTo(whole, fromTheBeginning = true)

        val first = importInto(mirror, whole)
        val afterOnce = fingerprint(mirror)

        val second = importInto(mirror, whole)
        val afterTwice = fingerprint(mirror)

        assertTrue("the first application did something", first.segmentsAdded > 0)
        assertEquals("the second added nothing", 0, second.segmentsAdded)
        assertTrue("and said so", second.changedNothing)
        assertEquals(afterOnce, afterTwice)
    }

    @Test
    fun applyingABatchThreeTimesInterleavedWithAnotherChangesNothingExtra() = runBlocking {
        buildBaseline()
        val whole = tempFile("batch-a.jsonl")
        exportTo(whole, fromTheBeginning = true)
        play(3, t0 + 40 * day)
        play(4, t0 + 41 * day)
        val delta = tempFile("batch-b.jsonl")
        exportTo(delta)

        importInto(mirror, whole)
        importInto(mirror, delta)
        val settled = fingerprint(mirror)

        importInto(mirror, whole)
        importInto(mirror, delta)
        importInto(mirror, whole)

        assertEquals(settled, fingerprint(mirror))
    }

    /**
     * Two batches applied in either order give the same database.
     *
     * The two are deliberately **overlapping** rather than consecutive, and
     * that is what makes the test honest. Two consecutive deltas can only be
     * applied in one order — the second one alone would leave a gap, and the
     * import refuses it, which is a different property and is tested above. So
     * order independence is stated where it is actually claimed: two batches
     * that both stand on their own, one a superset of the other, may arrive in
     * either order and the database that results is the same.
     *
     * This also exercises the part that would fail if identity were wrong.
     * Every fact in the earlier batch appears again in the later one, so the
     * second application has to recognise several hundred facts as things it
     * already holds rather than as new ones.
     */
    @Test
    fun twoOverlappingBatchesAppliedInEitherOrderAgree() = runBlocking {
        buildBaseline()
        val earlier = tempFile("batch-order-a.jsonl")
        exportTo(earlier, fromTheBeginning = true)

        play(5, t0 + 40 * day)
        play(6, t0 + 41 * day)
        val later = tempFile("batch-order-b.jsonl")
        exportTo(later, fromTheBeginning = true)

        importInto(mirror, earlier)
        importInto(mirror, later)
        val forwards = fingerprint(mirror)

        val other = freshDatabase("batch-test-other.db")
        try {
            importInto(other, later)
            importInto(other, earlier)

            assertEquals(forwards, fingerprint(other))
        } finally {
            other.close()
        }
    }

    // --- The test the phase exists for ---------------------------------------

    /**
     * A database built by whole copy, and a database built by older copy plus
     * the delta covering the difference, rank identically.
     *
     * Phase 5b's equivalent of `CrossDeviceRankingTest`, and the thing most
     * worth seeing pass. If it fails, the batch is losing or distorting a fact
     * that the ranking depends on — and it would do so invisibly, because a
     * ranking computed from incomplete facts still looks like a ranking.
     */
    @Test
    fun deltaGivesTheSameRankingAsTheWholeThing() = runBlocking {
        buildBaseline()

        // The mirror takes a whole copy here, as of the baseline.
        copyWholeDatabase(mirror)

        // More listening happens on the writer, enough to reorder the ranking.
        repeat(9) { n -> play(1, t0 + (30 + n) * day, listenedMs = 190_000) }
        play(6, t0 + 45 * day, listenedMs = 10_000, durationMs = 200_000)

        // The mirror gets it as a delta...
        val delta = tempFile("batch-ranking.jsonl")
        exportTo(delta)
        val applied = importInto(mirror, delta)

        // ...and a third database gets the whole thing, the Phase 5a way.
        val whole = freshDatabase("batch-test-whole.db")
        try {
            copyWholeDatabase(whole)

            assertEquals(
                "the delta carried every play",
                segmentCount(whole),
                segmentCount(mirror),
            )
            assertEquals(
                "and the rankings agree, which is the point of the phase",
                ranking(whole),
                ranking(mirror),
            )
            assertEquals(
                "and so does the writer's own",
                ranking(writer),
                ranking(mirror),
            )
            assertTrue(applied.segmentsAdded >= 10)
        } finally {
            whole.close()
        }
    }

    /**
     * A delta carries the settings in force, or the ranking would not match.
     *
     * The subtle half of the test above. Scores are a pure function of facts,
     * parameters and `now`; a receiver that took the facts and kept its own
     * parameters would compute a different ranking from the same history, and
     * the disagreement would look like a bug in the scoring rather than a
     * missing line in a file.
     */
    @Test
    fun aDeltaCarriesTheScoringParametersInForce() = runBlocking {
        buildBaseline()
        copyWholeDatabase(mirror)

        val retuned = ScoringParameters().copy(halfLifeDays = 10.0)
        writer.scoringDao().putScoringParameters(retuned, t0 + 30 * day)
        play(2, t0 + 31 * day)

        val delta = tempFile("batch-params.jsonl")
        exportTo(delta)
        importInto(mirror, delta)

        assertEquals(
            "the settings in force crossed",
            retuned.toJson(),
            mirror.scoringDao().syncStateText(SyncKeys.SCORING_PARAMETERS),
        )
        assertEquals(
            "and both log entries that explain them",
            writer.syncDao().countParameterHistory(),
            mirror.syncDao().countParameterHistory(),
        )
    }

    /**
     * The same like arriving twice collapses to one fact.
     *
     * `like_events` deliberately has no device column: a like is an observation
     * of *Spotify's* state, so two devices seeing one like are seeing one fact
     * twice. Its identity has to be content, or the archive would double every
     * time a batch was applied.
     */
    @Test
    fun theSameLikeArrivingTwiceIsOneFact() = runBlocking {
        buildBaseline()
        val whole = tempFile("batch-likes-a.jsonl")
        exportTo(whole, fromTheBeginning = true)

        importInto(mirror, whole)
        val afterOne = mirror.syncDao().countLikeEvents()

        // A second batch covering the same likes, as a second device observing
        // the same library would produce.
        val again = tempFile("batch-likes-b.jsonl")
        exportTo(again, fromTheBeginning = true)
        importInto(mirror, again)

        assertEquals(6, afterOne)
        assertEquals("no like was counted twice", afterOne, mirror.syncDao().countLikeEvents())
        assertEquals(6, mirror.spotifyDao().likedNowCountOnce())
    }

    /**
     * A delta does not make a mirror stop being one.
     *
     * Every guard Phase 5a built has to still hold against a database that has
     * had deltas applied — the import inserts facts and touches nothing about
     * what the device *is*.
     */
    @Test
    fun aMirrorThatHasHadDeltasAppliedIsStillAMirror() = runBlocking {
        buildBaseline()
        val whole = tempFile("batch-guard-a.jsonl")
        exportTo(whole, fromTheBeginning = true)
        importInto(mirror, whole)

        mirror.importDao().markAsMirror("NW-A307", takenAtMs = t0, importedAtMs = t0)
        mirror.importDao().clearWriterRole()

        play(1, t0 + 40 * day)
        val delta = tempFile("batch-guard-b.jsonl")
        exportTo(delta)
        importInto(mirror, delta)

        assertNotNull("still a mirror", mirror.importDao().mirrorState())
        assertEquals("NW-A307", mirror.importDao().mirrorState()!!.sourceDevice)
        assertFalse("and still not the writer", mirror.importDao().isEvergreenWriter(TEST_DEVICE_ID))
    }

    /** The score cache is dropped rather than carried, so the ranking is recomputed. */
    @Test
    fun anImportDropsTheScoreCache() = runBlocking {
        buildBaseline()
        val whole = tempFile("batch-cache.jsonl")
        exportTo(whole, fromTheBeginning = true)
        importInto(mirror, whole)
        mirror.scoringDao().replaceScores(
            listOf(
                ScoreEntity(
                    trackId = 1,
                    score = 99.0,
                    rank = 1,
                    computedAt = t0,
                    parametersHash = "stale",
                ),
            ),
        )

        play(1, t0 + 40 * day)
        val delta = tempFile("batch-cache-b.jsonl")
        exportTo(delta)
        importInto(mirror, delta)

        assertEquals(0, mirror.scoringDao().cachedScoreCount())
    }

    // --- Comparing two databases ---------------------------------------------

    /**
     * Everything about a database that a batch could affect, as one string.
     *
     * Counts and shapes, never a row printed anywhere: this runs in a test, but
     * an assertion message ends up in a log, and the rule is the rule. Segments
     * are fingerprinted by their global name and their listened time, which is
     * what a duplicate would disturb.
     */
    // --- The overlap tripwire, Phase 5c step 6 -------------------------------

    @Test
    fun normalListeningOnTwoDevicesOverlapsNotAtAll() = runBlocking {
        // The baseline the tripwire is calibrated against: two devices that
        // took turns, which is what really happens.
        like(1, t0)
        like(2, t0)
        play(1, t0 + day, deviceId = "device-a", playbackType = PlaybackType.LOCAL)
        play(2, t0 + 2 * day, deviceId = "device-b", playbackType = PlaybackType.LOCAL)

        assertEquals(0, writer.syncDao().countCrossDeviceOverlaps())
    }

    @Test
    fun twoDevicesPlayingOneTrackAtOnceIsCounted() = runBlocking {
        // One person cannot listen to two things at once, so this is the
        // signal: the phantom fix has stopped working somewhere.
        like(1, t0)
        play(1, t0 + day, deviceId = "device-a", playbackType = PlaybackType.LOCAL)
        play(1, t0 + day, deviceId = "device-b", playbackType = PlaybackType.LOCAL)

        assertEquals(1, writer.syncDao().countCrossDeviceOverlaps())
    }

    @Test
    fun aPhantomTheFixAlreadyCaughtIsNotCounted() = runBlocking {
        // What makes the number mean something. Counting correctly-marked
        // phantoms would leave the tripwire permanently tripped, and a warning
        // that is always on is a warning nobody reads.
        like(1, t0)
        play(1, t0 + day, deviceId = "device-a", playbackType = PlaybackType.LOCAL)
        play(1, t0 + day, deviceId = "device-b", playbackType = PlaybackType.REMOTE)

        assertEquals(0, writer.syncDao().countCrossDeviceOverlaps())
    }

    @Test
    fun oneDeviceReplayingATrackIsNotAnOverlap() = runBlocking {
        // Same device, same track, overlapping rows -- which capture can
        // produce around a replay. Not a cross-device signal, so not counted.
        like(1, t0)
        play(1, t0 + day, deviceId = "device-a", playbackType = PlaybackType.LOCAL)
        play(1, t0 + day, deviceId = "device-a", playbackType = PlaybackType.LOCAL)

        assertEquals(0, writer.syncDao().countCrossDeviceOverlaps())
    }

    @Test
    fun importedHistoryIsNeverAnOverlap() = runBlocking {
        // Every imported row carries the fixed origin spotify-history-import,
        // so it is "a different device" from every captured row. Counting it
        // would report an overlap for a play that was simply both imported and
        // captured.
        like(1, t0)
        play(1, t0 + day, deviceId = "device-a", playbackType = PlaybackType.LOCAL)
        playImported(1, t0 + day)

        assertEquals(0, writer.syncDao().countCrossDeviceOverlaps())
    }

    @Test
    fun theImportReportsTheCount() = runBlocking {
        // What the screen shows, end to end.
        like(1, t0)
        play(1, t0 + day, deviceId = "device-a", playbackType = PlaybackType.LOCAL)
        play(1, t0 + day, deviceId = "device-b", playbackType = PlaybackType.LOCAL)
        writer.scoringDao().putScoringParameters(ScoringParameters(), t0)

        val file = tempFile("batch-overlap.jsonl")
        exportTo(file, fromTheBeginning = true)
        val applied = importInto(mirror, file)

        assertEquals(1, applied.crossDeviceOverlaps)
    }

    // --- The writer role, Phase 5c step 5 ------------------------------------

    @Test
    fun theRoleCrossesAndIsClaimedOnlyOnImport() = runBlocking {
        buildBaseline()
        val walkmanId = "device-walkman-install"
        val pixelId = "device-pixel-install"

        writer.importDao().putWriterRole(WriterRole(walkmanId, t0), "NW-A300Series")
        copyWholeDatabase(mirror)
        mirror.importDao().putWriterRole(WriterRole(walkmanId, t0), "NW-A300Series")

        assertTrue("the writer holds it", writer.importDao().isEvergreenWriter(walkmanId))
        assertFalse("the other does not", mirror.importDao().isEvergreenWriter(pixelId))

        // The writer releases, and stands down immediately.
        writer.importDao().putWriterRole(WriterRole.release(pixelId, t0 + day), "Pixel 10 Pro")
        assertFalse(
            "the releasing device stops writing at once",
            writer.importDao().isEvergreenWriter(walkmanId),
        )
        assertFalse(
            "and the other has not heard yet -- briefly nobody, never two",
            mirror.importDao().isEvergreenWriter(pixelId),
        )

        val file = tempFile("batch-role.jsonl")
        exportTo(file)
        importInto(mirror, file)

        assertTrue(
            "claimed on import, and only then",
            mirror.importDao().isEvergreenWriter(pixelId),
        )
        assertEquals("Pixel 10 Pro", mirror.importDao().writerName())
    }

    @Test
    fun importingTheSameHandoverTwiceChangesNothing() = runBlocking {
        buildBaseline()
        val pixelId = "device-pixel-install"
        copyWholeDatabase(mirror)

        writer.importDao().putWriterRole(WriterRole.release(pixelId, t0 + day), "Pixel 10 Pro")
        val file = tempFile("batch-role-twice.jsonl")
        exportTo(file, fromTheBeginning = true)

        importInto(mirror, file)
        val after = mirror.importDao().writerRole(pixelId)
        importInto(mirror, file)
        importInto(mirror, file)

        assertEquals("idempotent, like every other fact", after, mirror.importDao().writerRole(pixelId))
        assertTrue(mirror.importDao().isEvergreenWriter(pixelId))
    }

    @Test
    fun aStaleHandoverDoesNotTakeTheRoleBack() = runBlocking {
        // The order-independence property, for the one piece of state where
        // getting it wrong means two devices writing one playlist.
        buildBaseline()
        val walkmanId = "device-walkman-install"
        val pixelId = "device-pixel-install"
        copyWholeDatabase(mirror)

        writer.importDao().putWriterRole(WriterRole(walkmanId, t0), "NW-A300Series")
        val old = tempFile("batch-role-old.jsonl")
        exportTo(old, fromTheBeginning = true)

        writer.importDao().putWriterRole(WriterRole.release(pixelId, t0 + day), "Pixel 10 Pro")
        val fresh = tempFile("batch-role-fresh.jsonl")
        exportTo(fresh, fromTheBeginning = true)

        // Newest first, then the stale one -- what a re-synced folder can do.
        importInto(mirror, fresh)
        importInto(mirror, old)

        assertTrue(
            "the later decision still stands",
            mirror.importDao().isEvergreenWriter(pixelId),
        )
    }

    @Test
    fun aBatchTeachesTheReceiverWhoSentIt() = runBlocking {
        // What makes "hand the role to the Pixel 10 Pro" possible rather than
        // "hand it to f642cfc7-...".
        buildBaseline()
        val file = tempFile("batch-producer.jsonl")
        exportTo(file, fromTheBeginning = true)
        importInto(mirror, file)

        val known = mirror.importDao().knownDevices(exceptDeviceId = "somebody-else")
        assertTrue("the producer is now a device it can hand the role to", known.isNotEmpty())
        assertTrue(known.all { it.describe().isNotBlank() })
    }

    // --- Settings merging both ways, Phase 5c step 4 -------------------------

    @Test
    fun anOlderSettingChangeDoesNotRollBackANewerOne() = runBlocking {
        // The two-way case the one-way import got wrong. The mirror changes a
        // setting AFTER the writer took its batch. Importing that batch must
        // not quietly undo the change -- which is exactly what adopting the
        // producer's `current` flag used to do.
        buildBaseline()
        copyWholeDatabase(mirror)

        val writerChange = ScoringParameters().copy(halfLifeDays = 10.0)
        writer.scoringDao().putScoringParameters(writerChange, t0 + 30 * day)
        val delta = tempFile("batch-params-older.jsonl")
        exportTo(delta, fromTheBeginning = true)

        // ...and only now does the other device change something, later.
        val mirrorChange = ScoringParameters().copy(halfLifeDays = 90.0)
        mirror.scoringDao().putScoringParameters(mirrorChange, t0 + 31 * day)

        importInto(mirror, delta)

        assertEquals(
            "the later change is the one in force",
            mirrorChange.toJson(),
            mirror.scoringDao().syncStateText(SyncKeys.SCORING_PARAMETERS),
        )
        assertEquals(
            "and the earlier one is still in the log, not lost",
            2,
            mirror.syncDao().countParameterHistory(),
        )
    }

    @Test
    fun whichSettingWinsDoesNotDependOnImportOrder() = runBlocking {
        // The property the old per-line behaviour did not have: two batches
        // applied in either order leave the same settings in force.
        buildBaseline()
        copyWholeDatabase(mirror)

        val early = ScoringParameters().copy(halfLifeDays = 10.0)
        val late = ScoringParameters().copy(halfLifeDays = 90.0)

        writer.scoringDao().putScoringParameters(early, t0 + 30 * day)
        val first = tempFile("batch-order-a.jsonl")
        exportTo(first, fromTheBeginning = true)

        writer.scoringDao().putScoringParameters(late, t0 + 31 * day)
        val second = tempFile("batch-order-b.jsonl")
        exportTo(second, fromTheBeginning = true)

        // Newest first, then oldest -- the order a folder sync can deliver.
        importInto(mirror, second)
        importInto(mirror, first)

        assertEquals(
            "the later change still wins, whichever file arrived last",
            late.toJson(),
            mirror.scoringDao().syncStateText(SyncKeys.SCORING_PARAMETERS),
        )
    }

    // --- Format v2: the two schema v8 columns cross ---------------------------

    @Test
    fun aRemoteSegmentIsStillRemoteAfterCrossing() = runBlocking {
        // The whole reason for the formatVersion bump. If the playback type did
        // not cross, a phantom recorded on one device would arrive on the other
        // with nothing marking it and would score as real listening -- which is
        // exactly the harm the phase set out to remove, arriving by a different
        // door.
        like(1, t0)
        play(1, t0 + day, playbackType = PlaybackType.LOCAL, utcOffsetMinutes = 60)
        play(1, t0 + 2 * day, playbackType = PlaybackType.REMOTE, utcOffsetMinutes = 60)
        play(1, t0 + 3 * day, playbackType = null, utcOffsetMinutes = -300)
        writer.scoringDao().putScoringParameters(ScoringParameters(), t0)

        val file = tempFile("batch-v2.jsonl")
        exportTo(file, fromTheBeginning = true)
        importInto(mirror, file)

        val crossed = mirror.syncDao()
            .segmentsToExport(walkman, 0, 100)
            .ifEmpty { null }
        assertNull("a received row is never re-exported as this device's own", crossed)

        val rows = mirror.query(
            "SELECT playbackType, utcOffsetMinutes FROM listening_segments " +
                "ORDER BY startedAtMs",
            emptyArray(),
        )
        rows.use { c ->
            assertEquals(3, c.count)
            assertTrue(c.moveToNext())
            assertEquals("LOCAL", c.getString(0))
            assertEquals(60, c.getInt(1))
            assertTrue(c.moveToNext())
            assertEquals("REMOTE", c.getString(0))
            assertEquals(60, c.getInt(1))
            assertTrue(c.moveToNext())
            assertTrue("null stays null -- not observed, not LOCAL", c.isNull(0))
            assertEquals(-300, c.getInt(1))
        }
    }

    @Test
    fun aRemoteSegmentDoesNotCountTowardsTheRankingOnEitherDevice() = runBlocking {
        // The property that matters more than the column surviving: both
        // devices reach the same conclusion about it.
        like(1, t0)
        like(2, t0)
        repeat(3) { n -> play(1, t0 + n * day, playbackType = PlaybackType.LOCAL) }
        repeat(3) { n -> play(2, t0 + n * day, playbackType = PlaybackType.LOCAL) }
        // Track 2 additionally picks up six phantoms, which must buy it nothing.
        repeat(6) { n -> play(2, t0 + (n + 4) * day, playbackType = PlaybackType.REMOTE) }
        writer.scoringDao().putScoringParameters(ScoringParameters(), t0)

        val file = tempFile("batch-v2-ranking.jsonl")
        exportTo(file, fromTheBeginning = true)
        importInto(mirror, file)

        assertEquals(
            "the same facts give the same ranking on both devices",
            ranking(writer),
            ranking(mirror),
        )
    }

    private suspend fun fingerprint(database: EvergreenDatabase): String {
        val segments = database.dao().segmentPage(limit = 100_000, offset = 0)
            .map { "${it.segment.deviceId}/${it.segment.originRowId ?: it.segment.id}" +
                ":${it.segment.listenedMs}" }
            .sorted()
        return buildString {
            append("segments=").append(segments.size).append('\n')
            append(segments.joinToString("\n")).append('\n')
            append("likeEvents=").append(database.syncDao().countLikeEvents()).append('\n')
            append("likes=").append(database.syncDao().countLikes()).append('\n')
            append("likedNow=").append(database.spotifyDao().likedNowCountOnce()).append('\n')
            append("params=").append(database.syncDao().countParameterHistory()).append('\n')
            append("membership=").append(database.syncDao().countMembership()).append('\n')
            append("snapshots=").append(database.syncDao().countSnapshots()).append('\n')
            append("snapshotEntries=").append(database.syncDao().countSnapshotEntries())
        }
    }
}
