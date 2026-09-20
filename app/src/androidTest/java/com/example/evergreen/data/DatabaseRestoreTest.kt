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
 * Step 1 of `docs/PHASE5B_PROMPT.md`: a backup can be put back on the device
 * that took it, and doing so changes the data and nothing else.
 *
 * The contrast with [MirrorImportTest] is the point of this file. The same
 * file, staged the same way and validated by the same code, means two opposite
 * things depending on which operation reads it — and the one test here that
 * runs *both* over one file is the one that would catch them being confused
 * for each other.
 *
 * Everything works on temporary files and databases of this test's own, never
 * on the app's: `stage` takes both as parameters for exactly that reason. The
 * file swap itself is not covered, for the reason [MirrorImportTest] gives —
 * it closes the process-wide database handle, and it is a rename with no
 * branches in it.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseRestoreTest {

    private lateinit var context: Context
    private lateinit var staging: File
    private lateinit var incomingFile: File
    private lateinit var live: EvergreenDatabase

    private val at = 1_789_128_000_000L
    private val takenAt = 1_789_041_600_000L

    private val thisDevice = "NW-A307"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        staging = File(context.cacheDir, "restore-test-staging.db")
        incomingFile = File(context.cacheDir, "restore-test-incoming.db")
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
     * Builds a file that looks exactly like the backup button's output, taken
     * on a device that was the writer at the time.
     */
    private fun writeBackupTakenOn(deviceName: String?, likes: Int = 3): Unit = runBlocking {
        val source = Room.databaseBuilder(
            context,
            EvergreenDatabase::class.java,
            File(context.cacheDir, "restore-test-source.db").absolutePath,
        ).build()
        try {
            deviceName?.let { source.importDao().setDeviceName(it, takenAt) }
            source.importDao().setEvergreenWriter(true)
            source.scoringDao().putSyncText(SyncKeys.EVERGREEN_PLAYLIST_ID, "pl_walkman")
            source.scoringDao().putSyncMs(SyncKeys.EVERGREEN_WRITE_CONFIRMED_AT, takenAt)
            repeat(likes) { index -> likeOne(source, index) }
            source.scoringDao().replaceScores(
                (0 until likes).map { index ->
                    ScoreEntity(
                        trackId = index + 1L,
                        score = 10.0 - index,
                        rank = index + 1,
                        computedAt = takenAt,
                        parametersHash = "computed-before-the-restore",
                    )
                },
            )
            incomingFile.delete()
            source.openHelper.writableDatabase
                .execSQL("VACUUM INTO ?", arrayOf<Any>(incomingFile.absolutePath))
        } finally {
            source.close()
            listOf("", "-wal", "-shm").forEach {
                File(context.cacheDir, "restore-test-source.db$it").delete()
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
            now = takenAt,
        )
        db.spotifyDao().recordLiked(rowId, serviceAddedAtMs = takenAt, now = takenAt)
    }

    private suspend fun stage(
        device: String = thisDevice,
        captureEnabled: Boolean = true,
    ) = DatabaseRestore.stage(
        context = context,
        uri = incomingFile.toUri(),
        staging = staging,
        live = live,
        thisDevice = device,
        captureEnabled = captureEnabled,
    )

    /** Opens the staged copy the way `apply` does, for asserting against it. */
    private fun openStaged(): EvergreenDatabase = Room.databaseBuilder(
        context,
        EvergreenDatabase::class.java,
        staging.absolutePath,
    ).build()

    // --- What a restore refuses ---------------------------------------------

    /**
     * The one refusal that matters. Restoring the Walkman's backup onto the
     * Pixel is "import a mirror", and the app has to say so rather than leaving
     * the Pixel showing the Walkman's listening while claiming to be its own
     * record.
     */
    @Test
    fun aBackupFromAnotherDeviceIsRefusedAndPointsAtTheMirrorImport() = runBlocking {
        writeBackupTakenOn("NW-A307")

        val refusal = runCatching { stage(device = "Pixel 10 Pro") }.exceptionOrNull()

        assertTrue(refusal is DatabaseRestore.Refused)
        val message = refusal!!.message.orEmpty()
        assertTrue("it names the device the file came from", message.contains("NW-A307"))
        assertTrue("and this one", message.contains("Pixel 10 Pro"))
        assertTrue("and says what to do instead", message.contains("Import a mirror"))
        assertFalse("the staged copy is cleaned up", staging.exists())
    }

    /**
     * A file from before the device stamp existed is allowed, not refused.
     *
     * It is safe because a restore never changes what the device is: the worst
     * an unidentifiable file can do is put the wrong history on the right
     * device, which one more restore undoes. Refusing it would close the only
     * route back for every backup taken before 2026-09-19.
     */
    @Test
    fun aBackupWithNoDeviceNameIsAllowedAndSaysItCannotTell() = runBlocking {
        writeBackupTakenOn(deviceName = null)

        val preview = stage()

        assertNull(preview.sourceDevice)
        assertTrue(preview.sourceIsUnknown)
    }

    @Test
    fun aFileThatIsNotADatabaseIsRefused() = runBlocking {
        incomingFile.writeText("title,artist\nnot a database,at all\n")

        val refusal = runCatching { stage() }.exceptionOrNull()

        assertTrue(refusal is DatabaseRestore.Refused)
        assertFalse(staging.exists())
    }

    @Test
    fun aBackupFromAnotherSchemaIsRefused() = runBlocking {
        writeBackupTakenOn(thisDevice)
        android.database.sqlite.SQLiteDatabase
            .openDatabase(
                incomingFile.path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            .use { it.version = EVERGREEN_SCHEMA_VERSION - 1 }

        val refusal = runCatching { stage() }.exceptionOrNull()

        assertTrue(refusal is DatabaseRestore.Refused)
        assertTrue(refusal!!.message.orEmpty().contains("older"))
        assertFalse(staging.exists())
    }

    // --- What the confirmation is able to say -------------------------------

    @Test
    fun thePreviewNamesBothSidesAndTheRoleThatSurvives() = runBlocking {
        runBlocking { live.importDao().setEvergreenWriter(true) }
        writeBackupTakenOn(thisDevice, likes = 4)

        val preview = stage()

        assertEquals(thisDevice, preview.sourceDevice)
        assertEquals(thisDevice, preview.thisDevice)
        assertEquals(4, preview.incomingLikes)
        assertEquals("this device's own record, for the confirmation", 0, preview.existingLikes)
        assertTrue(preview.staysWriter)
        assertNull(preview.staysMirrorOf)
        assertTrue(preview.describeRoleAfterwards().contains("stays the one that writes"))
        assertTrue(preview.describeRoleAfterwards().contains("Recording stays on"))
        assertTrue("the staged copy is kept for the restore", staging.exists())
    }

    @Test
    fun thePreviewSaysSoWhenRecordingIsAlreadyOff() = runBlocking {
        writeBackupTakenOn(thisDevice)

        val preview = stage(captureEnabled = false)

        assertFalse(preview.captureStaysOn)
        assertTrue(preview.describeRoleAfterwards().contains("stays off"))
    }

    // --- What a restore does, and deliberately does not do ------------------

    /**
     * The phase's first promise: the writer is still the writer afterwards,
     * and is not a mirror of itself.
     */
    @Test
    fun restoringOnTheWriterLeavesItTheWriterAndNotAMirror() = runBlocking {
        live.importDao().setEvergreenWriter(true)
        writeBackupTakenOn(thisDevice, likes = 4)
        val preview = stage()

        val staged = openStaged()
        try {
            DatabaseRestore.reassertThisDevice(staged, preview, at)

            assertTrue("still the writer", staged.importDao().isEvergreenWriter())
            assertNull("and not a mirror of itself", staged.importDao().mirrorState())
            assertEquals(
                "the device name is re-stamped as this device",
                thisDevice,
                staged.importDao().deviceName(),
            )
            assertEquals(
                "the playlist id comes back with the file",
                "pl_walkman",
                staged.scoringDao().syncStateText(SyncKeys.EVERGREEN_PLAYLIST_ID),
            )
            assertEquals(
                "the likes it came with are untouched",
                4,
                staged.spotifyDao().likedNowCountOnce(),
            )
            assertEquals(
                "the score cache is dropped — its inputs have just changed wholesale",
                0,
                staged.scoringDao().cachedScoreCount(),
            )
        } finally {
            staged.close()
        }
    }

    /**
     * A restore cannot promote a device either. Restoring onto a device that
     * is not the writer leaves it not the writer, even though the file says it
     * was one when it was taken.
     */
    @Test
    fun restoringOnANonWriterDoesNotPromoteIt() = runBlocking {
        // live is left as a fresh database: no writer flag, no mirror.
        writeBackupTakenOn(thisDevice)
        val preview = stage()
        assertFalse(preview.staysWriter)

        val staged = openStaged()
        try {
            assertTrue("the file arrives as the writer", staged.importDao().isEvergreenWriter())

            DatabaseRestore.reassertThisDevice(staged, preview, at)

            assertFalse("and is not one afterwards", staged.importDao().isEvergreenWriter())
        } finally {
            staged.close()
        }
    }

    /** And it cannot demote one: a mirror that restores its own backup stays a mirror. */
    @Test
    fun restoringOnAMirrorLeavesItAMirror() = runBlocking {
        live.importDao().markAsMirror(
            sourceDevice = "NW-A307",
            takenAtMs = takenAt,
            importedAtMs = at,
        )
        writeBackupTakenOn("Pixel 10 Pro")
        val preview = stage(device = "Pixel 10 Pro")

        assertNotNull(preview.staysMirrorOf)
        assertTrue(preview.describeRoleAfterwards().contains("stays a mirror of NW-A307"))

        val staged = openStaged()
        try {
            DatabaseRestore.reassertThisDevice(staged, preview, at)

            val mirror = staged.importDao().mirrorState()
            assertNotNull("still a mirror", mirror)
            assertEquals("NW-A307", mirror!!.sourceDevice)
            assertEquals(takenAt, mirror.takenAtMs)
            assertFalse("and still not the writer", staged.importDao().isEvergreenWriter())
        } finally {
            staged.close()
        }
    }

    /**
     * The contrast, over one file: the same backup read by the mirror import
     * still neutralises.
     *
     * This is the test that would catch the two operations being wired to each
     * other, which is the single most damaging mistake available here — it
     * would stop the daily driver writing Evergreen, silently, at the moment
     * the user was trying to recover it.
     */
    @Test
    fun theSameFileImportedAsAMirrorStillNeutralises() = runBlocking {
        live.importDao().setEvergreenWriter(true)
        writeBackupTakenOn(thisDevice, likes = 4)

        val mirrorStaging = File(context.cacheDir, "restore-test-mirror-staging.db")
        listOf("", "-wal", "-shm", "-journal").forEach { File(mirrorStaging.path + it).delete() }
        try {
            val preview = MirrorImport.stage(
                context = context,
                uri = incomingFile.toUri(),
                staging = mirrorStaging,
                live = live,
            )
            val staged = Room.databaseBuilder(
                context,
                EvergreenDatabase::class.java,
                mirrorStaging.absolutePath,
            ).build()
            try {
                MirrorImport.neutralise(staged, preview, at)

                assertNotNull("a mirror", staged.importDao().mirrorState())
                assertFalse("and not the writer", staged.importDao().isEvergreenWriter())
            } finally {
                staged.close()
            }
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach {
                File(mirrorStaging.path + it).delete()
            }
        }
    }

    /**
     * The two operations stage into different files, so one cannot apply the
     * other's copy. Cheap to assert and the kind of thing that only ever goes
     * wrong once.
     */
    @Test
    fun theTwoOperationsDoNotShareAStagingFile() {
        assertFalse(
            EvergreenDatabase.stagingFileFor(context).absolutePath ==
                EvergreenDatabase.restoreStagingFileFor(context).absolutePath,
        )
    }
}
