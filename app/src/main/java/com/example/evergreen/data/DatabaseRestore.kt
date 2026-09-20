package com.example.evergreen.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.evergreen.capture.CaptureSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puts **this** device's own backup back.
 *
 * ### Why this is not "import a mirror"
 *
 * The backup button has produced files since Phase 3, and until Phase 5b there
 * was no operation that put one back on the writer device. The mirror import
 * looks like it would do — same file, same picker, same validation — but it
 * means the opposite thing: it *neutralises* before replacing, so importing the
 * NW-A307's own backup onto the NW-A307 would have made it a mirror of itself
 * and stopped it writing Evergreen. The route back for the daily driver has to
 * be a different operation, and this is it.
 *
 * ### The one rule: this changes the data, and nothing else
 *
 * A restore replaces every fact in the database with the file's, and leaves the
 * device in exactly the role it was already playing. Whatever this device was
 * a moment ago — the writer, not the writer, a mirror of another device, with
 * capture on or off — it still is a moment afterwards.
 *
 * That is deliberately *preserving this device's state* rather than *adopting
 * the file's*, and the difference is the point. This is the one operation in
 * the app that could silently promote or demote a device, and taking the role
 * from the file would mean a backup from a week when the writer switch happened
 * to be off could turn Evergreen's writes off without anybody choosing that.
 * Preserving what is here cannot surprise: the roles are only ever changed by
 * the switches that exist for changing them.
 *
 * ### And it refuses another device's backup
 *
 * Restoring the Walkman's backup onto the Pixel is "import a mirror", and the
 * app says so rather than doing something surprising. The check is the device
 * name the backup button stamps into the file immediately before it takes its
 * copy. A file that names no device — anything taken before 2026-09-19 — is
 * allowed rather than refused, because the rule above makes it safe: an
 * unidentifiable file can put the wrong history on the right device, which one
 * more restore undoes, but it cannot change what the device is.
 */
object DatabaseRestore {

    private const val TAG = "DatabaseRestore"

    /** A reason the user can act on. Never a stack trace, never a row. */
    class Refused(message: String) : Exception(message)

    /**
     * What a restore would replace, with what, and what it would leave alone.
     *
     * The last part is why this carries more than [MirrorImport.Preview] does.
     * The confirmation has to be able to say, in words, what this device will
     * be afterwards — otherwise the one operation that could change a device's
     * role is also the one that says least about it.
     */
    data class Preview(
        /** The device the file was taken on, or null if it does not say. */
        val sourceDevice: String?,
        /** This device, as [Build.MODEL] reports it. */
        val thisDevice: String,
        val takenAtMs: Long?,
        val fileBytes: Long,
        val incomingSegments: Int,
        val incomingLikes: Int,
        val existingSegments: Int,
        val existingLikes: Int,
        /** What this device is now, and will still be afterwards. */
        val staysWriter: Boolean,
        val staysMirrorOf: MirrorState?,
        val captureStaysOn: Boolean,
    ) {
        fun describeSource(): String = sourceDevice ?: "a device it does not name"

        /** True when the file cannot say where it came from. */
        val sourceIsUnknown: Boolean get() = sourceDevice == null

        /** One sentence for the dialog: what this device will be afterwards. */
        fun describeRoleAfterwards(): String = buildString {
            append(
                when {
                    staysMirrorOf != null ->
                        "This device stays a mirror of ${staysMirrorOf.describeSource()}, " +
                            "and still will not write to Spotify."

                    staysWriter ->
                        "This device stays the one that writes the Evergreen playlist."

                    else ->
                        "This device is not the one that writes Evergreen, and stays that way."
                },
            )
            append(' ')
            append(
                if (captureStaysOn) {
                    "Recording stays on."
                } else {
                    "Recording is off on this device and stays off."
                },
            )
        }
    }

    /**
     * Copies the chosen file to the restore staging path and reads it,
     * changing nothing.
     *
     * A staging path of its own rather than the mirror import's: the two
     * operations are reachable from the same screen, and one staged copy shared
     * between them is a way for the wrong file to be applied.
     */
    suspend fun stage(
        context: Context,
        uri: Uri,
        staging: File = EvergreenDatabase.restoreStagingFileFor(context.applicationContext),
        live: EvergreenDatabase = EvergreenDatabase.get(context.applicationContext),
        thisDevice: String = Build.MODEL,
        captureEnabled: Boolean = CaptureSwitch.isEnabled(context.applicationContext),
    ): Preview = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        val bytes = try {
            StagedBackup.copyIn(appContext, uri, staging)
        } catch (refusal: StagedBackup.Refused) {
            throw Refused(refusal.message.orEmpty())
        }

        try {
            StagedBackup.requireCurrentSchema(staging)

            val incoming = StagedBackup.open(appContext, staging)
            val preview = try {
                val sourceDevice = incoming.importDao().deviceName()
                if (sourceDevice != null && sourceDevice != thisDevice) {
                    throw StagedBackup.Refused(
                        "That backup was taken on $sourceDevice, and this device is a " +
                            "$thisDevice. Restoring it here would put another device's " +
                            "listening onto this one while leaving this device's own " +
                            "settings in place, which is not something you would ever " +
                            "mean. If you want this device to show $sourceDevice's " +
                            "listening, use \"Import a mirror\" instead. Nothing has been " +
                            "changed.",
                    )
                }
                Preview(
                    sourceDevice = sourceDevice,
                    thisDevice = thisDevice,
                    takenAtMs = StagedBackup.takenAtOf(appContext, uri),
                    fileBytes = bytes,
                    incomingSegments = incoming.dao().segmentCountOnce(),
                    incomingLikes = incoming.spotifyDao().likedNowCountOnce(),
                    existingSegments = live.dao().segmentCountOnce(),
                    existingLikes = live.spotifyDao().likedNowCountOnce(),
                    staysWriter = live.importDao().isEvergreenWriter(),
                    staysMirrorOf = live.importDao().mirrorState(),
                    captureStaysOn = captureEnabled,
                )
            } finally {
                incoming.close()
            }

            Log.i(TAG, "staged a restore: ${preview.incomingSegments} segments")
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
     * Puts the staged copy in place.
     *
     * The order is the mirror import's, minus the two steps that make a mirror:
     *
     * 1. capture off, so nothing is written to a database about to be replaced;
     * 2. this device's own role written onto the staged copy, while it is still
     *    a file nothing is reading;
     * 3. the live database closed, deleted, and the staged file renamed into
     *    its place;
     * 4. capture put back exactly as it was found.
     *
     * The scheduled Spotify work is deliberately **not** cancelled. It lives in
     * WorkManager rather than in the database, the device is still whatever it
     * was, and a writer that stopped writing Evergreen because it restored a
     * backup would be the mirror import's behaviour arriving by the back door.
     *
     * Step 4 is the one place this can leave something behind: a process death
     * between 1 and 4 leaves recording off on a device that was recording. The
     * settings screen says so and offers to turn it back on, which is why that
     * control exists outside mirror mode.
     */
    suspend fun apply(context: Context, at: Long, preview: Preview) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val staging = EvergreenDatabase.restoreStagingFileFor(appContext)
        if (!staging.exists()) throw Refused("The staged copy is gone. Pick the file again.")

        val captureWasEnabled = preview.captureStaysOn
        CaptureSwitch.setEnabled(appContext, false)
        try {
            val incoming = StagedBackup.open(appContext, staging)
            try {
                reassertThisDevice(incoming, preview, at)
            } finally {
                incoming.close()
            }

            try {
                StagedBackup.swapIntoPlace(appContext, staging)
            } catch (refusal: StagedBackup.Refused) {
                throw Refused(refusal.message.orEmpty())
            }
        } finally {
            if (captureWasEnabled) CaptureSwitch.setEnabled(appContext, true)
        }

        Log.i(
            TAG,
            "restored: ${preview.incomingSegments} segments replaced ${preview.existingSegments}",
        )
    }

    /**
     * Writes this device's own role onto the staged copy before it lands.
     *
     * The counterpart of [MirrorImport.neutralise], and `internal` for the same
     * reason: the rename around it has no branches, and this is where the
     * promise either holds or does not.
     *
     * Four rows, and the reasoning for each:
     *
     * - **the writer flag** and **mirror mode** are what this device is. They
     *   are carried over from the live database rather than taken from the
     *   file, so a restore cannot change the role. Restoring onto a mirror
     *   leaves a mirror; restoring onto the writer leaves the writer.
     * - **the device name** is re-stamped, because the row means "the device
     *   this database belongs to" and the database now belongs to this one.
     *   In the ordinary case it is already right; setting it costs nothing and
     *   removes the one way a restored file could go on claiming otherwise.
     * - **the score cache** is dropped. Not because it is suspect — it was
     *   computed here, under these parameters — but because the facts under it
     *   have just changed wholesale, and a cache that survives a change to its
     *   inputs is the accumulation invariant 1a forbids. Recomputing takes a
     *   couple of seconds on the slowest device in the project.
     *
     * Everything else in `sync_state` comes from the file and should: the
     * playlist id, the scoring parameters, the like-sync cursor and the import
     * bookkeeping all belong to the data, and this is that data's own device.
     */
    internal suspend fun reassertThisDevice(
        database: EvergreenDatabase,
        preview: Preview,
        at: Long,
    ) {
        database.importDao().setEvergreenWriter(preview.staysWriter)
        val mirror = preview.staysMirrorOf
        if (mirror == null) {
            database.importDao().clearMirror()
        } else {
            database.importDao().markAsMirror(
                sourceDevice = mirror.sourceDevice,
                takenAtMs = mirror.takenAtMs,
                importedAtMs = mirror.importedAtMs,
            )
        }
        database.importDao().setDeviceName(preview.thisDevice, at)
        database.scoringDao().dropScoreCache()
    }

    /** Throws away a staged copy the user decided not to restore. */
    fun discard(
        context: Context,
        staging: File = EvergreenDatabase.restoreStagingFileFor(context.applicationContext),
    ) = StagedBackup.discard(staging)

    /** For the screens: when the file was taken, in words. */
    fun describeTakenAt(takenAtMs: Long?): String = StagedBackup.describeTakenAt(takenAtMs)
}
