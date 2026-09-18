package com.example.evergreen.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a copy of the database to a file the user picks.
 *
 * **Why this exists in Phase 3 rather than Phase 4.** After the streaming
 * history import the database holds six years of listening that cannot be
 * reconstructed from anywhere else — Spotify will not export the same history
 * twice on demand, and the app's own capture only goes back to 2026-09-17. At
 * the same time, running the instrumented tests uninstalls the app and destroys
 * its database. The backup button is what makes that an inconvenience rather
 * than a loss.
 *
 * **Nothing secret can end up in the file.** Tokens live in encrypted storage
 * outside the database entirely, a Phase 2 decision made for exactly this
 * reason. A backup is listening history, likes and settings, and physically
 * cannot contain a credential.
 */
object DatabaseBackup {

    /**
     * Copies the database to [destination].
     *
     * Uses SQLite's own `VACUUM INTO` rather than copying the file. In
     * write-ahead-logging mode the database is really three files — the
     * database, the `-wal` log and the `-shm` index — and the recent writes
     * live in the log rather than in the main file. Copying only the main file
     * would silently lose whatever had not been checkpointed yet, and copying
     * all three would hand the user something they cannot restore without
     * knowing that. `VACUUM INTO` writes one consistent, self-contained,
     * already-compacted snapshot, taken safely while the capture service may
     * still be writing.
     *
     * The snapshot is built in the app's own cache directory first, because
     * `VACUUM INTO` needs a filesystem path and the picker gives a content URI.
     * It is deleted afterwards, including when the copy fails.
     *
     * @return the size of the file written, in bytes.
     */
    suspend fun writeTo(context: Context, destination: Uri): Long = withContext(Dispatchers.IO) {
        val database = EvergreenDatabase.get(context)
        val snapshot = File(context.cacheDir, "evergreen-backup-snapshot.db")
        // VACUUM INTO refuses to overwrite, so a snapshot left behind by an
        // interrupted run would block every later backup.
        snapshot.delete()

        try {
            database.openHelper.writableDatabase.execSQL(
                "VACUUM INTO ?",
                arrayOf<Any>(snapshot.absolutePath),
            )

            val written = context.contentResolver.openOutputStream(destination)?.use { out ->
                snapshot.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Could not write to the file you chose.")

            written
        } finally {
            snapshot.delete()
        }
    }

    /**
     * A filename that sorts chronologically and says what it is.
     *
     * `.db` rather than something friendlier: restoring means putting this file
     * back where the app keeps its database, and a name that hides what it is
     * would make that harder to work out later.
     */
    fun suggestedFileName(nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(nowMs))
        return "evergreen-backup-$stamp.db"
    }
}
