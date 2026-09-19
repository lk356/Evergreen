package com.example.evergreen.data

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Step 3 of `docs/PHASE5A_PROMPT.md`: a mirror is validated before anything is
 * replaced, and the copy is neutralised before it lands.
 *
 * Everything here works on temporary files and databases of this test's own,
 * never on the app's — `stage` takes both as parameters for exactly that
 * reason. The one thing not covered is the file swap itself, which closes the
 * process-wide database handle; exercising that from inside a test suite would
 * pull the database out from under every test that ran afterwards, and it is
 * a rename with no branches in it.
 */
@RunWith(AndroidJUnit4::class)
class MirrorImportTest {

    private lateinit var context: Context
    private lateinit var staging: File
    private lateinit var incomingFile: File
    private lateinit var live: EvergreenDatabase

    private val at = 1_789_128_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        staging = File(context.cacheDir, "mirror-test-staging.db")
        incomingFile = File(context.cacheDir, "mirror-test-incoming.db")
        deleteAll()
        live = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        live.close()
        deleteAll()
    }

    private fun deleteAll() {
        listOf(staging, incomingFile).forEach { file ->
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }

    /**
     * Builds a file that looks exactly like the backup button's output: a real
     * Evergreen database at the current schema version, written through Room
     * and then vacuumed into a standalone file.
     */
    private fun writeIncomingBackup(deviceName: String? = "NW-A307", likes: Int = 3): Unit =
        runBlocking {
            val source = Room.databaseBuilder(
                context,
                EvergreenDatabase::class.java,
                File(context.cacheDir, "mirror-test-source.db").absolutePath,
            ).build()
            try {
                deviceName?.let { source.importDao().setDeviceName(it, at) }
                // The trap this whole mechanism exists for: the source is the
                // writer, and says so in its own sync_state.
                source.importDao().setEvergreenWriter(true)
                source.scoringDao().putSyncMs(SyncKeys.EVERGREEN_WRITE_CONFIRMED_AT, at)
                source.scoringDao().putSyncText(SyncKeys.EVERGREEN_PLAYLIST_ID, "pl_walkman")
                repeat(likes) { index -> likeOne(source, index) }
                source.scoringDao().replaceScores(
                    (0 until likes).map { index ->
                        ScoreEntity(
                            trackId = index + 1L,
                            score = 10.0 - index,
                            rank = index + 1,
                            computedAt = at,
                            parametersHash = "computed-on-the-other-device",
                        )
                    },
                )
                incomingFile.delete()
                source.openHelper.writableDatabase
                    .execSQL("VACUUM INTO ?", arrayOf<Any>(incomingFile.absolutePath))
            } finally {
                source.close()
                listOf("", "-wal", "-shm").forEach {
                    File(context.cacheDir, "mirror-test-source.db$it").delete()
                }
            }
        }

    private suspend fun likeOne(db: EvergreenDatabase, index: Int) {
        val title = "Track $index"
        val rowId = db.spotifyDao().resolveServiceTrack(
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
        db.spotifyDao().recordLiked(rowId, serviceAddedAtMs = at, now = at)
    }

    /** Rewrites `PRAGMA user_version`, which is where Room keeps the schema version. */
    private fun setSchemaVersionOf(file: File, version: Int) {
        android.database.sqlite.SQLiteDatabase
            .openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            .use { it.version = version }
    }

    private suspend fun stage() = MirrorImport.stage(
        context = context,
        uri = incomingFile.toUri(),
        staging = staging,
        live = live,
    )

    // --- Validation --------------------------------------------------------

    @Test
    fun aRealBackupIsAccepted() = runBlocking {
        writeIncomingBackup(likes = 3)

        val preview = stage()

        assertEquals("NW-A307", preview.sourceDevice)
        assertEquals(3, preview.incomingLikes)
        assertEquals("this device's own record, for the confirmation", 0, preview.existingSegments)
        assertTrue("the staged copy is kept for the import", staging.exists())
    }

    /**
     * A backup from a newer schema is a refusal, not a migration.
     *
     * Room's instinct on an unfamiliar version is to upgrade the file, which
     * for a *newer* one it cannot do and for an older one it would do silently.
     * Either way the answer to "can I read this backup" must not be "yes, and
     * it is now a different database".
     */
    @Test
    fun aBackupFromANewerSchemaIsRefusedAndNothingIsLeftBehind() = runBlocking {
        writeIncomingBackup()
        setSchemaVersionOf(incomingFile, EVERGREEN_SCHEMA_VERSION + 1)

        val refusal = runCatching { stage() }.exceptionOrNull()

        assertTrue(refusal is MirrorImport.Refused)
        assertTrue(
            "it says which way round the mismatch is",
            refusal!!.message.orEmpty().contains("newer"),
        )
        assertFalse("the staged copy is cleaned up", staging.exists())
    }

    @Test
    fun aBackupFromAnOlderSchemaIsRefused() = runBlocking {
        writeIncomingBackup()
        setSchemaVersionOf(incomingFile, EVERGREEN_SCHEMA_VERSION - 1)

        val refusal = runCatching { stage() }.exceptionOrNull()

        assertTrue(refusal is MirrorImport.Refused)
        assertTrue(refusal!!.message.orEmpty().contains("older"))
        assertFalse(staging.exists())
    }

    @Test
    fun aFileThatIsNotADatabaseIsRefused() = runBlocking {
        incomingFile.writeText("title,artist\nnot a database,at all\n")

        val refusal = runCatching { stage() }.exceptionOrNull()

        assertTrue(refusal is MirrorImport.Refused)
        assertFalse(staging.exists())
    }

    /** A backup taken before the device name existed is still a good mirror. */
    @Test
    fun aBackupWithNoDeviceNameIsAcceptedAndSaysSo() = runBlocking {
        writeIncomingBackup(deviceName = null)

        val preview = stage()

        assertNull(preview.sourceDevice)
        assertEquals("another device", preview.describeSource())
    }

    // --- Neutralising ------------------------------------------------------

    /**
     * The safety-critical half, run against the staged copy before it becomes
     * anything: mirror on, writer off, score cache gone.
     */
    @Test
    fun neutralisingTurnsTheWritersCopyIntoAMirror() = runBlocking {
        writeIncomingBackup(likes = 4)
        val preview = stage()

        val staged = Room.databaseBuilder(
            context,
            EvergreenDatabase::class.java,
            staging.absolutePath,
        ).build()
        try {
            // As it arrives, it is the Walkman in every respect.
            assertTrue("arrives as the writer", staged.importDao().isEvergreenWriter())
            assertEquals(4, staged.scoringDao().cachedScoreCount())
            assertNull(staged.importDao().mirrorState())

            MirrorImport.neutralise(staged, preview, at)

            val mirror = staged.importDao().mirrorState()
            assertNotNull("it is a mirror now", mirror)
            assertEquals("NW-A307", mirror!!.sourceDevice)
            assertEquals(at, mirror.importedAtMs)
            assertFalse("and not the writer", staged.importDao().isEvergreenWriter())
            assertEquals(
                "the other device's scores are dropped, to be recomputed here",
                0,
                staged.scoringDao().cachedScoreCount(),
            )
            assertEquals(
                "but the likes it came with are untouched",
                4,
                staged.spotifyDao().likedNowCountOnce(),
            )
            assertEquals(
                "and so is the playlist id, which names the account's playlist",
                "pl_walkman",
                staged.scoringDao().syncStateText(SyncKeys.EVERGREEN_PLAYLIST_ID),
            )
        } finally {
            staged.close()
        }
    }

    // --- The file's timestamp ----------------------------------------------

    /**
     * The backup filename is the fallback for how stale a mirror is, used when
     * the document provider will not say. Worth pinning, because getting it
     * wrong would put a confident and wrong date on the home screen.
     */
    @Test
    fun theBackupFilenameCarriesTheTimeItWasTaken() {
        val parsed = MirrorImport.timeFromBackupName("evergreen-backup-2026-09-19-1436.db")

        assertNotNull(parsed)
        assertEquals(
            "2026-09-19-1436",
            java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
                .format(java.util.Date(parsed!!)),
        )
    }

    @Test
    fun aFilenameWithNoStampGivesNoTime() {
        assertNull(MirrorImport.timeFromBackupName("walkman.db"))
    }
}
