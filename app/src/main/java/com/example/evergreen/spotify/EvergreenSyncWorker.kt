package com.example.evergreen.spotify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.evergreen.Notifications
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.SyncKeys
import java.util.concurrent.TimeUnit

/**
 * Writes the Evergreen playlist, once a day at most.
 *
 * Daily rather than hourly because that is the cap decisions 6 sets, and
 * because nothing here is urgent: the whole point of the scoring is to notice
 * changes that happen over weeks. Unliking therefore takes effect within a day
 * rather than instantly, which is the cost of not polling.
 *
 * The worker itself decides nothing. Every guard — is this the writer device,
 * has the first write been confirmed, is a backoff open, was there a write
 * today — lives in [EvergreenSync], so the scheduled path and the debug
 * screen's button cannot disagree about when it is safe to write.
 */
class EvergreenSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val auth = SpotifyAuth(applicationContext)
        if (!auth.isConfigured) return Result.success()

        val db = EvergreenDatabase.get(applicationContext)

        // Checked before the writer flag, because a mirror arrives with that
        // flag already set — it is the Walkman's, copied in with everything
        // else — and would otherwise sail straight past the next check and
        // rewrite the Walkman's playlist from a ranking as stale as the last
        // import. EvergreenSync refuses as well; this stops the device waking
        // up for it at all.
        if (db.importDao().isMirror()) {
            Log.i(TAG, "this database is a mirror; cancelling the schedule")
            cancel(applicationContext)
            return Result.success()
        }

        // Checked before the connection, because a device that is not the
        // writer should not even wake up for this. Cancelling the schedule
        // means the Pixel stops running it entirely once the Walkman is set as
        // the writer.
        if (!db.importDao().isEvergreenWriter()) {
            Log.i(TAG, "not the writer device; cancelling the schedule")
            cancel(applicationContext)
            return Result.success()
        }

        when (auth.connectionState()) {
            is SpotifyConnectionState.NotConnected,
            is SpotifyConnectionState.Expired,
            -> {
                Log.i(TAG, "not connected; cancelling scheduled sync until reconnected")
                cancel(applicationContext)
                return Result.success()
            }
            else -> Unit
        }

        val sync = EvergreenSync(db = db, api = SpotifyApi(auth))

        val result = sync.sync()
        return when (result.outcome) {
            EvergreenSyncResult.Outcome.FAILED -> {
                Log.w(TAG, "evergreen sync failed: ${result.message}")
                Result.retry()
            }

            EvergreenSyncResult.Outcome.NEEDS_RECONNECT -> {
                Notifications.postReconnectRequired(applicationContext)
                cancel(applicationContext)
                Result.success()
            }

            // Everything else is a success in the sense that matters: nothing
            // is wrong and nothing would be fixed by retrying sooner. A backoff
            // window is recorded in sync_state and the next scheduled run
            // checks it, so WorkManager's own retry would only compete with it.
            // AWAITING_CONFIRMATION is the ordinary state before the first
            // write and must not look like a failure.
            else -> {
                Log.i(TAG, "evergreen sync: ${result.outcome} ${result.message.orEmpty()}")
                if (result.added > 0 || result.removed > 0) {
                    // One notification per sync, summarising -- never one per
                    // track, because a sync can produce one change or forty.
                    // Notifications.postPlaylistChanged has always counted
                    // rather than listed; what is new is that it can be
                    // suppressed.
                    //
                    // Suppressed when the movement was caused by a parameter
                    // change rather than by a day passing. Forty joins and
                    // leaves after nudging the skip weight is the arithmetic
                    // doing as it was told, not news about listening -- and the
                    // settings screen makes that a thing to do on a whim. The
                    // flag is taken and cleared in one transaction, so the
                    // suppression applies to exactly one write.
                    val causedByParameterChange =
                        db.scoringDao().takeSyncStateMs(SyncKeys.PARAMETERS_CHANGED_AT) != null
                    if (causedByParameterChange) {
                        Log.i(TAG, "suppressing the change notification: parameters changed")
                    } else {
                        Notifications.postPlaylistChanged(
                            applicationContext,
                            added = result.added,
                            removed = result.removed,
                        )
                    }
                }
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "EvergreenSyncWorker"
        private const val PERIODIC_WORK = "evergreen-playlist-sync"
        private const val ONE_OFF_WORK = "evergreen-playlist-sync-now"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Schedules the daily sync. Safe to call repeatedly; an existing
         * schedule is kept rather than restarted.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EvergreenSyncWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }

        /** "Sync now", for the debug screen. Still subject to every guard. */
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_OFF_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EvergreenSyncWorker>()
                    .setConstraints(constraints)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
