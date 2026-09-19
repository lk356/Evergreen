package com.example.evergreen.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.evergreen.capture.CaptureSwitch
import com.example.evergreen.spotify.EvergreenSyncWorker
import com.example.evergreen.spotify.SpotifySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replaces this device's database with a copy of another device's.
 *
 * The transport is a file — the one the existing backup button writes, moved
 * across by USB, adb or any file-sharing app — and this is the other end of it.
 * There is no new network destination, no server and no new dependency, which
 * is the whole reason a mirror is the cheap version of cross-device sync.
 *
 * ### Two rules shape everything here
 *
 * **Validate before replacing.** The file is copied to a staging path beside
 * the live database and checked there. A backup from a different schema
 * version is a refusal, never a migration: Room would happily migrate a v5 file
 * forwards on open, which would mean the answer to "can I read last year's
 * backup" was "yes, and it quietly became a different database". Half an import
 * is the one outcome there is no recovering from, so nothing is touched until
 * the copy has been read all the way through.
 *
 * **Neutralise before replacing, not after.** A backup carries the source
 * device's `sync_state` — its writer flag, its playlist id, its confirmed first
 * write — so a database that landed unedited would believe it was the Walkman.
 * Editing it after the swap would leave a window, however brief, in which the
 * live database was that. So the staged copy is marked as a mirror, stripped of
 * the writer flag and emptied of its score cache *while it is still a file
 * nothing is reading*, and the swap itself is a rename.
 */
object MirrorImport {

    private const val TAG = "MirrorImport"

    /**
     * What an import would replace, and with what — everything the
     * confirmation dialog needs, in counts only.
     */
    data class Preview(
        val sourceDevice: String?,
        val takenAtMs: Long?,
        val fileBytes: Long,
        val incomingSegments: Int,
        val incomingLikes: Int,
        val existingSegments: Int,
        val existingLikes: Int,
    ) {
        fun describeSource(): String = sourceDevice ?: "another device"
    }

    /** A reason the user can act on. Never a stack trace, never a row. */
    class Refused(message: String) : Exception(message)

    /**
     * Copies the chosen file to the staging path and reads it, changing nothing.
     *
     * Leaves the staged file in place for [apply]; call [discard] if the user
     * says no.
     */
    suspend fun stage(
        context: Context,
        uri: Uri,
        /**
         * Where the copy lands, and which database it is replacing.
         *
         * Defaulted rather than reached for, so a test can point this at a
         * temporary file and an in-memory database instead of at the app's
         * own. That is the Phase 4 lesson about seams, applied before it bites:
         * a function that resolves its own dependencies cannot be tested
         * against a different one, and the test that appears to do so is
         * quietly asserting about something else.
         */
        staging: File = EvergreenDatabase.stagingFileFor(context.applicationContext),
        live: EvergreenDatabase = EvergreenDatabase.get(context.applicationContext),
    ): Preview = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        staging.parentFile?.mkdirs()
        discard(appContext, staging)

        val bytes = try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Could not open the file you picked.")
        } catch (failure: IOException) {
            discard(appContext, staging)
            throw Refused("Could not read that file. ${failure.message.orEmpty()}".trim())
        }

        try {
            // 1. Is it a database at all, and is it *this* database's version?
            //    Read with plain SQLite rather than Room, because Room's answer
            //    to an old version is to migrate it.
            val version = readSchemaVersion(staging)
            when {
                version == null -> throw Refused(
                    "That file is not an Evergreen backup. Pick the .db file the " +
                        "\"Back up database\" button wrote.",
                )

                version > EVERGREEN_SCHEMA_VERSION -> throw Refused(
                    "That backup was taken by a newer version of Evergreen " +
                        "(database v$version; this build reads v$EVERGREEN_SCHEMA_VERSION). " +
                        "Update this device's app and try again. Nothing has been changed.",
                )

                version < EVERGREEN_SCHEMA_VERSION -> throw Refused(
                    "That backup was taken by an older version of Evergreen " +
                        "(database v$version; this build reads v$EVERGREEN_SCHEMA_VERSION). " +
                        "Take a fresh backup on the other device. Nothing has been changed.",
                )
            }

            // 2. Can Room open it? This is the check that the tables are the
            //    ones this build expects, not merely that the number matches —
            //    Room verifies its own identity hash and refuses otherwise.
            val incoming = openStaged(appContext, staging)
            val preview = try {
                Preview(
                    sourceDevice = incoming.importDao().deviceName(),
                    takenAtMs = takenAtOf(appContext, uri),
                    fileBytes = bytes,
                    incomingSegments = incoming.dao().segmentCountOnce(),
                    incomingLikes = incoming.spotifyDao().likedNowCountOnce(),
                    existingSegments = live.dao().segmentCountOnce(),
                    existingLikes = live.spotifyDao().likedNowCountOnce(),
                )
            } finally {
                incoming.close()
            }

            Log.i(TAG, "staged a mirror: ${preview.incomingSegments} segments")
            preview
        } catch (refusal: Refused) {
            discard(appContext, staging)
            throw refusal
        } catch (failure: IllegalStateException) {
            // Room's "cannot verify the data integrity" — the file is a v6
            // SQLite database but not one of ours.
            discard(appContext, staging)
            throw Refused(
                "That file says it is database v$EVERGREEN_SCHEMA_VERSION but its tables are " +
                    "not the ones this app expects. Nothing has been changed.",
            )
        } catch (failure: SQLiteException) {
            discard(appContext, staging)
            throw Refused("That file could not be read as a database. Nothing has been changed.")
        }
    }

    /**
     * Neutralises the staged copy and swaps it in.
     *
     * The order matters and is the whole of the safety argument:
     *
     * 1. capture off, so nothing is written to a database about to be replaced;
     * 2. the schedules cancelled, before the state that would let them run is
     *    gone;
     * 3. the staged copy marked as a mirror, its writer flag cleared and its
     *    score cache dropped — while it is still a file nothing is reading;
     * 4. the live database closed and deleted;
     * 5. the staged file renamed into its place, which is one atomic step.
     *
     * A process death between 4 and 5 leaves no database, so Room creates an
     * empty one on next open: no writer flag, no confirmed write, nothing that
     * could write to Spotify. That is the right way to fail.
     */
    suspend fun apply(context: Context, at: Long, preview: Preview) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val staging = EvergreenDatabase.stagingFileFor(appContext)
        if (!staging.exists()) throw Refused("The staged copy is gone. Pick the file again.")

        // 1. Stop capture. Segments recorded into a mirror are discarded at the
        //    next import, and leaving capture running would imply they counted.
        CaptureSwitch.setEnabled(appContext, false)

        // 2. Stop the scheduled work that talks to Spotify. Both workers refuse
        //    on a mirror as well; cancelling means the device does not wake for
        //    them at all.
        EvergreenSyncWorker.cancel(appContext)
        SpotifySyncWorker.cancel(appContext)

        // 3. Neutralise the copy before it becomes the live database.
        val incoming = openStaged(appContext, staging)
        try {
            neutralise(incoming, preview, at)
        } finally {
            incoming.close()
        }
        // Room's own journal files for the staged copy, if any survived close.
        deleteAlongside(staging)

        // 4 and 5. Swap.
        val live = EvergreenDatabase.fileFor(appContext)
        EvergreenDatabase.closeForReplacement()
        live.delete()
        deleteAlongside(live)
        if (!staging.renameTo(live)) {
            throw Refused(
                "The copy could not be moved into place, and this device's database is " +
                    "now empty. Import the file again, or restore a backup.",
            )
        }

        Log.i(TAG, "mirror imported: ${preview.incomingSegments} segments replaced ${preview.existingSegments}")
    }

    /**
     * Strips a copy of everything that makes it the *other* device's record.
     *
     * Separated out and `internal` because it is the part worth testing on its
     * own: the swap around it is a file rename, but this is where the guard
     * either holds or does not, and it runs against any database handed to it.
     *
     * Deliberately *not* a wholesale clear of `sync_state`. The scoring
     * parameters, the parameter log and the like-sync cursor all belong to the
     * data rather than to the device, and a mirror that scored its copy under
     * different settings would not be a mirror of anything. The playlist id
     * stays too: it names the playlist the *account* uses, so if this device is
     * ever promoted to writer it should keep that playlist up to date rather
     * than create a second one.
     */
    internal suspend fun neutralise(database: EvergreenDatabase, preview: Preview, at: Long) {
        database.importDao().setEvergreenWriter(false)
        database.importDao().markAsMirror(
            sourceDevice = preview.sourceDevice,
            takenAtMs = preview.takenAtMs,
            importedAtMs = at,
        )
        // The score cache is the one table that must not be trusted from
        // another device: its rows were computed there, and the point of this
        // phase is to prove that computing them here gives the same ranking. A
        // cache carried across would hide exactly the disagreement worth
        // finding. Dropping it is always a valid repair — that is what makes
        // `scores` a cache.
        database.scoringDao().dropScoreCache()
    }

    /** Throws away a staged copy the user decided not to import. */
    fun discard(
        context: Context,
        staging: File = EvergreenDatabase.stagingFileFor(context.applicationContext),
    ) {
        staging.delete()
        deleteAlongside(staging)
    }

    // --- Plumbing ------------------------------------------------------------

    /**
     * Opens the staged file as a Room database, without migrating it.
     *
     * `TRUNCATE` rather than the live database's write-ahead logging: WAL puts
     * the most recent writes in a `-wal` file beside the database, and this one
     * is about to be renamed. A rename moves the database and leaves its log
     * behind, which is the one way this operation could lose the writes it has
     * just made.
     */
    private fun openStaged(context: Context, staging: File): EvergreenDatabase =
        Room.databaseBuilder(context, EvergreenDatabase::class.java, staging.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

    /**
     * The database's schema version, or null if the file is not SQLite.
     *
     * `PRAGMA user_version` is where Room records it, and reading it read-only
     * cannot migrate, upgrade or otherwise alter the file.
     */
    private fun readSchemaVersion(file: File): Int? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            .use { database -> database.version }
    } catch (failure: SQLiteException) {
        null
    }

    /**
     * When the backup was taken, as well as it can be known.
     *
     * The document provider's own last-modified time first, because it is a
     * fact about the file rather than a guess. Failing that, the app's own
     * backup filename encodes the time it was written, and the app is what
     * wrote it. Failing both, null — and the screens say "taken at an unknown
     * time" rather than showing today's date, which would be a claim the app
     * cannot support and the one claim that matters here.
     */
    private fun takenAtOf(context: Context, uri: Uri): Long? {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            OpenableColumns.DISPLAY_NAME,
        )
        var modified: Long? = null
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    modified = cursor.getLong(modifiedIndex).takeIf { it > 0 }
                }
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            }
        }
        return modified ?: name?.let(::timeFromBackupName)
    }

    /** `evergreen-backup-2026-09-19-1436.db` → that moment, or null. */
    internal fun timeFromBackupName(name: String): Long? {
        val stamp = Regex("""(\d{4}-\d{2}-\d{2}-\d{4})""").find(name)?.groupValues?.get(1)
            ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).parse(stamp)?.time
        }.getOrNull()
    }

    /** A SQLite database is up to three files; deleting one leaves the others. */
    private fun deleteAlongside(file: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(file.path + suffix).delete()
        }
    }

    /** For the screens: how old a mirror is, in words. */
    fun describeTakenAt(takenAtMs: Long?): String =
        takenAtMs?.let { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)) }
            ?: "an unknown time"
}
