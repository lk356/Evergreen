package com.example.evergreen.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.evergreen.capture.CaptureSwitch
import com.example.evergreen.spotify.EvergreenSyncWorker
import com.example.evergreen.spotify.SpotifySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Replaces this device's database with a copy of **another** device's.
 *
 * The transport is a file — the one the existing backup button writes, moved
 * across by USB, adb or any file-sharing app — and this is the other end of it.
 * There is no new network destination, no server and no new dependency, which
 * is the whole reason a mirror is the cheap version of cross-device sync.
 *
 * The file-handling half lives in [StagedBackup] and is shared with
 * [DatabaseRestore], which is the same operation pointed at *this* device's own
 * backup and means the opposite thing. The difference between them is one
 * function: this one calls [neutralise] on the copy before it lands, and
 * restore deliberately does not.
 *
 * ### Two rules shape everything here
 *
 * **Validate before replacing.** The file is copied to a staging path beside
 * the live database and checked there. A backup from a different schema
 * version is a refusal, never a migration. Half an import is the one outcome
 * there is no recovering from, so nothing is touched until the copy has been
 * read all the way through.
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

        val bytes = try {
            StagedBackup.copyIn(appContext, uri, staging)
        } catch (refusal: StagedBackup.Refused) {
            throw Refused(refusal.message.orEmpty())
        }

        try {
            // 1. Is it a database at all, and is it *this* database's version?
            StagedBackup.requireCurrentSchema(staging)

            // 2. Can Room open it? This is the check that the tables are the
            //    ones this build expects, not merely that the number matches —
            //    Room verifies its own identity hash and refuses otherwise.
            val incoming = StagedBackup.open(appContext, staging)
            val preview = try {
                Preview(
                    sourceDevice = incoming.importDao().deviceName(),
                    takenAtMs = StagedBackup.takenAtOf(appContext, uri),
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
        } catch (failure: Exception) {
            StagedBackup.discard(staging)
            throw when (failure) {
                is StagedBackup.Refused -> Refused(failure.message.orEmpty())
                else -> StagedBackup.refusalFor(failure)
                    ?.let { Refused(it.message.orEmpty()) }
                    ?: failure
            }
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
        val incoming = StagedBackup.open(appContext, staging)
        try {
            neutralise(incoming, preview, at)
        } finally {
            incoming.close()
        }

        // 4 and 5. Swap.
        try {
            StagedBackup.swapIntoPlace(appContext, staging)
        } catch (refusal: StagedBackup.Refused) {
            throw Refused(refusal.message.orEmpty())
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
    ) = StagedBackup.discard(staging)

    /** `evergreen-backup-2026-09-19-1436.db` → that moment, or null. */
    internal fun timeFromBackupName(name: String): Long? = StagedBackup.timeFromBackupName(name)

    /** For the screens: how old a mirror is, in words. */
    fun describeTakenAt(takenAtMs: Long?): String = StagedBackup.describeTakenAt(takenAtMs)
}
