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
import java.util.concurrent.TimeUnit

/**
 * Runs the like sync in the background.
 *
 * Periodic rather than on-launch, and deliberately infrequent. Likes change
 * slowly, the archive is not time-critical, and a development-mode app has a
 * quota that is neither published nor generous. Twice a day is ample for
 * something whose job is to notice that a song stopped being played over
 * months.
 */
class SpotifySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val auth = SpotifyAuth(applicationContext)

        if (!auth.isConfigured) return Result.success()

        // A mirror does not sync likes either, and the reason is different from
        // the one that stops it writing. Nothing here touches the playlist --
        // the like sync only *reads* from Spotify -- but what it reads it
        // writes into this database, and everything in this database is
        // replaced at the next import. Rows that are certain to be discarded
        // are not worth a network request, and leaving the sync running would
        // put a "last synced" time on the home screen that means nothing.
        if (EvergreenDatabase.get(applicationContext).importDao().isMirror()) {
            Log.i(TAG, "this database is a mirror; cancelling the schedule")
            cancel(applicationContext)
            return Result.success()
        }

        when (auth.connectionState()) {
            is SpotifyConnectionState.Expired -> {
                // Nothing to do and nothing to retry. Scheduled work is
                // cancelled so a dead connection does not wake the device twice
                // a day forever; connecting schedules it again.
                //
                // This is the app's one silent failure, so it is the one thing
                // that interrupts: the six-month refresh token has died and
                // nothing on the device would say so until the app was opened.
                Log.i(TAG, "connection expired; cancelling scheduled sync until reconnected")
                Notifications.postReconnectRequired(applicationContext)
                cancel(applicationContext)
                return Result.success()
            }

            is SpotifyConnectionState.NotConnected -> {
                // Never connected, or deliberately disconnected. Not a failure
                // and not something to be told about.
                Log.i(TAG, "not connected; cancelling scheduled sync until reconnected")
                cancel(applicationContext)
                return Result.success()
            }

            // The two-week advance warning, so a reconnect can happen before
            // anything stops rather than after.
            is SpotifyConnectionState.ExpiringSoon -> {
                Notifications.postReconnectRequired(applicationContext, expiringSoon = true)
            }

            else -> Unit
        }

        val db = EvergreenDatabase.get(applicationContext)
        val sync = LikeSync(db = db, api = SpotifyApi(auth))

        return when (val outcome = sync.syncLikes()) {
            is SyncOutcome.Success -> {
                // Only backfill once the likes are in: a liked track gets its
                // ISRC free from the like sync, so doing this first would spend
                // one request per track on rows about to be filled anyway.
                when (val backfill = sync.backfillIsrcs()) {
                    is SyncOutcome.BackedOff -> Log.i(TAG, "backfill backed off")
                    is SyncOutcome.Failed -> Log.w(TAG, "backfill failed: ${backfill.reason}")
                    else -> Unit
                }
                Result.success()
            }

            // WorkManager's own backoff would compete with the window recorded
            // in sync_state, and the next scheduled run already checks it. So
            // this is a success in the sense that matters: nothing is wrong and
            // nothing should be retried sooner.
            is SyncOutcome.BackedOff -> Result.success()

            is SyncOutcome.NeedsReconnect -> {
                Notifications.postReconnectRequired(applicationContext)
                cancel(applicationContext)
                Result.success()
            }

            is SyncOutcome.Failed -> {
                Log.w(TAG, "like sync failed: ${outcome.reason}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "SpotifySyncWorker"
        private const val PERIODIC_WORK = "spotify-like-sync"
        private const val ONE_OFF_WORK = "spotify-like-sync-now"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Schedules the recurring sync. Safe to call repeatedly; an existing
         * schedule is kept rather than restarted, so opening the app does not
         * reset the interval.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SpotifySyncWorker>(12, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }

        /** A sync now, for the debug screen's button. */
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_OFF_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SpotifySyncWorker>()
                    .setConstraints(constraints)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
