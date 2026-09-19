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

        /**
         * The unique name, version 2.
         *
         * Renamed rather than updated, and for the same reason the playlist
         * notification channel became `playlist_v2` in Phase 4: some things
         * cannot be changed in place. `ExistingPeriodicWorkPolicy.KEEP` exists
         * so that opening the app does not restart the interval — which also
         * means a device already holding the old 24-hour schedule would keep
         * it forever and never see the new one. A new name sidesteps that
         * with no ambiguity about what WorkManager does with a changed period,
         * and the old name is cancelled below.
         */
        private const val PERIODIC_WORK = "evergreen-playlist-sync-v2"

        /** The 24-hour schedule this replaces. Cancelled wherever it is found. */
        private const val LEGACY_PERIODIC_WORK = "evergreen-playlist-sync"

        private const val ONE_OFF_WORK = "evergreen-playlist-sync-now"

        /**
         * How often a write is **attempted** — not how often one happens.
         *
         * Deliberately shorter than [EvergreenSync.WRITE_INTERVAL_MS], and
         * that inequality is the whole point. Both numbers used to be 24
         * hours, and they are anchored to different things: this one to when
         * the work was enqueued, the guard to when the last write actually
         * happened. A manual write moves the second without moving the first,
         * and after that the two beat against each other — an attempt landing
         * even a minute inside the guard returns `TOO_SOON`, reports success
         * because nothing is wrong, and WorkManager then waits a *full further
         * period*. One unlucky alignment cost a whole day, and the steady
         * state sat within minutes of the boundary, so it oscillated rather
         * than settled. Observed on the NW-A307 on 2026-09-19: the write at
         * 03:25 on the 18th was followed by an attempt at 01:08 on the 19th,
         * 21 h 43 min later, which did nothing.
         *
         * Six hours makes the guard the only thing that decides whether a
         * write happens, and bounds the delay after it expires at six hours
         * instead of a day. A refused attempt is genuinely cheap: `TOO_SOON`
         * is checked in step 1 of [EvergreenSync.run], before the recompute
         * and before any network request, so it costs an open of the database
         * and two indexed reads. The `CONNECTED` constraint means the device
         * only wakes for it when it already has a network — which is also what
         * "sync every day, but only when it can" asks for on a player that is
         * often asleep and off Wi-Fi.
         */
        const val ATTEMPT_INTERVAL_HOURS = 6L

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Schedules the sync attempt. Safe to call repeatedly; an existing
         * schedule is kept rather than restarted.
         *
         * Cancels the old 24-hour schedule first. That call is a no-op once
         * the device has run this build even once, and it is what stops a
         * device that has been running since Phase 3 keeping the schedule this
         * replaces alongside the new one — which would be two jobs writing the
         * same playlist against the same guard.
         */
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(LEGACY_PERIODIC_WORK)
            manager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EvergreenSyncWorker>(
                    ATTEMPT_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                )
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
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(PERIODIC_WORK)
            // The old name too, so a device that has not yet run [schedule] on
            // this build is still stopped by a mirror import or a disconnect.
            manager.cancelUniqueWork(LEGACY_PERIODIC_WORK)
        }
    }
}
