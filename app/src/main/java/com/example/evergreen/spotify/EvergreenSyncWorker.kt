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
import com.example.evergreen.data.EvergreenDatabase
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
