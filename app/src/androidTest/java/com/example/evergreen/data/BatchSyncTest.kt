package com.example.evergreen.data

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.scoring.ScoreService
import com.example.evergreen.scoring.ScoringParameters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

        assertEquals(
            "both the device's own facts and the imported ones",
            setOf(walkman, "spotify-history-import"),
            plan.origins.map { it.deviceId }.toSet(),
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
        mirror.importDao().setEvergreenWriter(false)

        play(1, t0 + 40 * day)
        val delta = tempFile("batch-guard-b.jsonl")
        exportTo(delta)
        importInto(mirror, delta)

        assertNotNull("still a mirror", mirror.importDao().mirrorState())
        assertEquals("NW-A307", mirror.importDao().mirrorState()!!.sourceDevice)
        assertFalse("and still not the writer", mirror.importDao().isEvergreenWriter())
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
