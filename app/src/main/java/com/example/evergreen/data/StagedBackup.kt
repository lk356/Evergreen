package com.example.evergreen.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The half of "replace this device's database with a file" that both operations
 * that do it have in common.
 *
 * There are two of them and they mean opposite things:
 *
 * - **[MirrorImport]** takes *another* device's backup and makes this device a
 *   read-only copy of it. It neutralises before replacing.
 * - **[DatabaseRestore]** takes *this* device's own backup and puts it back. It
 *   neutralises nothing, and leaves the device in whatever role it was already
 *   playing.
 *
 * Everything up to that difference is identical — copy the file somewhere safe,
 * refuse it if it is not a readable Evergreen database of exactly this schema
 * version, work out when it was taken, and finish with an atomic rename. That
 * is the part that is easy to get subtly wrong, and having one copy of it means
 * a lesson learned on one path cannot fail to reach the other.
 *
 * Extracted in Phase 5b when restore was built, from code Phase 5a
 * device-verified. Nothing about the mirror import's behaviour changed with the
 * move.
 */
internal object StagedBackup {

    /** A reason the user can act on. Never a stack trace, never a row. */
    class Refused(message: String) : Exception(message)

    /**
     * Copies the chosen file to [staging], replacing anything already there.
     *
     * @return the number of bytes copied, for the confirmation.
     */
    fun copyIn(context: Context, uri: Uri, staging: File): Long {
        staging.parentFile?.mkdirs()
        discard(staging)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Could not open the file you picked.")
        } catch (failure: IOException) {
            discard(staging)
            throw Refused("Could not read that file. ${failure.message.orEmpty()}".trim())
        }
    }

    /**
     * Refuses the file unless it is SQLite at exactly this schema version.
     *
     * Read with plain SQLite rather than Room, because Room's answer to an old
     * version is to migrate it — so without this, "can I read last year's
     * backup?" would be answered "yes, and it is now a different database".
     * A newer file is refused for the obvious reason; an older one is refused
     * for that less obvious and more dangerous one.
     */
    fun requireCurrentSchema(staging: File) {
        when (val version = readSchemaVersion(staging)) {
            null -> throw Refused(
                "That file is not an Evergreen backup. Pick the .db file the " +
                    "\"Back up database\" button wrote.",
            )

            EVERGREEN_SCHEMA_VERSION -> Unit

            else -> throw Refused(
                if (version > EVERGREEN_SCHEMA_VERSION) {
                    "That backup was taken by a newer version of Evergreen " +
                        "(database v$version; this build reads v$EVERGREEN_SCHEMA_VERSION). " +
                        "Update this device's app and try again. Nothing has been changed."
                } else {
                    "That backup was taken by an older version of Evergreen " +
                        "(database v$version; this build reads v$EVERGREEN_SCHEMA_VERSION). " +
                        "Take a fresh backup on the other device. Nothing has been changed."
                },
            )
        }
    }

    /**
     * Opens the staged file as a Room database, without migrating it.
     *
     * `TRUNCATE` rather than the live database's write-ahead logging: WAL puts
     * the most recent writes in a `-wal` file beside the database, and this one
     * is about to be renamed. A rename moves the database and leaves its log
     * behind, which is the one way this operation could lose the writes it has
     * just made.
     */
    fun open(context: Context, staging: File): EvergreenDatabase =
        Room.databaseBuilder(context, EvergreenDatabase::class.java, staging.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

    /**
     * Turns the two failures Room and SQLite raise on a plausible-looking file
     * into something a person can act on.
     *
     * Anything else is left to propagate: a message invented for a failure
     * nobody has seen would be a guess presented as a diagnosis.
     */
    fun refusalFor(failure: Throwable): Refused? = when (failure) {
        // Room's "cannot verify the data integrity" — the file is SQLite at the
        // right version but its tables are not ours.
        is IllegalStateException -> Refused(
            "That file says it is database v$EVERGREEN_SCHEMA_VERSION but its tables are " +
                "not the ones this app expects. Nothing has been changed.",
        )

        is SQLiteException -> Refused(
            "That file could not be read as a database. Nothing has been changed.",
        )

        else -> null
    }

    /**
     * Closes the live database, deletes it, and renames the staged copy on top.
     *
     * The order is the whole of the safety argument for both operations. A
     * process death between the delete and the rename leaves *no* database, so
     * Room creates an empty one on next open: no writer flag, no confirmed
     * write, nothing that could write to Spotify. That is the right way to
     * fail, and it is why the staged copy is edited before it is moved rather
     * than after.
     */
    fun swapIntoPlace(context: Context, staging: File) {
        // Room's own journal files for the staged copy, if any survived close.
        deleteAlongside(staging)

        val live = EvergreenDatabase.fileFor(context)
        EvergreenDatabase.closeForReplacement()
        live.delete()
        deleteAlongside(live)
        if (!staging.renameTo(live)) {
            throw Refused(
                "The copy could not be moved into place, and this device's database is " +
                    "now empty. Try the file again, or restore a backup.",
            )
        }
    }

    /** Throws away a staged copy nobody is going to use. */
    fun discard(staging: File) {
        staging.delete()
        deleteAlongside(staging)
    }

    /**
     * When the backup was taken, as well as it can be known.
     *
     * The document provider's own last-modified time first, because it is a
     * fact about the file rather than a guess. Failing that, the app's own
     * backup filename encodes the time it was written, and the app is what
     * wrote it. Failing both, null — and the screens say "an unknown time"
     * rather than showing today's date, which would be a claim the app cannot
     * support and the one claim that matters here.
     */
    fun takenAtOf(context: Context, uri: Uri): Long? {
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
    fun timeFromBackupName(name: String): Long? {
        val stamp = Regex("""(\d{4}-\d{2}-\d{2}-\d{4})""").find(name)?.groupValues?.get(1)
            ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).parse(stamp)?.time
        }.getOrNull()
    }

    /** For the screens: when a file was taken, in words. */
    fun describeTakenAt(takenAtMs: Long?): String =
        takenAtMs?.let { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)) }
            ?: "an unknown time"

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

    /** A SQLite database is up to three files; deleting one leaves the others. */
    fun deleteAlongside(file: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(file.path + suffix).delete()
        }
    }
}
