package com.example.evergreen.scoring

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.evergreen.data.EvergreenDatabase
import java.util.concurrent.TimeUnit

/**
 * Takes the monthly snapshot.
 *
 * Runs **daily** and does nothing on all but one day of the month. That is
 * deliberate and simpler than it sounds: expressing "the last day of the month"
 * as a schedule means special-casing February and leap years, and a run missed
 * to a flat battery would derail the sequence rather than costing one day.
 * Checking daily costs a wake-up and a date comparison.
 *
 * No network constraint: a snapshot is taken from the app's own database and
 * needs nothing from Spotify. Exporting one to a playlist is a separate,
 * manual act.
 */
class SnapshotWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = EvergreenDatabase.get(applicationContext)

        return when (val result = Snapshots(db).takeIfDue()) {
            is SnapshotResult.Taken -> {
                Log.i(TAG, "took snapshot ${result.monthKey} of ${result.trackCount} tracks")
                Result.success()
            }
            // All three are ordinary: not the right day, already have one, or
            // nothing scored yet on a fresh install.
            is SnapshotResult.NotDue,
            is SnapshotResult.AlreadyExists,
            is SnapshotResult.Empty,
            -> Result.success()
        }
    }

    companion object {
        private const val TAG = "SnapshotWorker"
        private const val PERIODIC_WORK = "evergreen-monthly-snapshot"
        private const val ONE_OFF_WORK = "evergreen-snapshot-now"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SnapshotWorker>(24, TimeUnit.HOURS).build(),
            )
        }

        /** For the debug screen's "Take a snapshot now". */
        fun snapshotNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_OFF_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SnapshotWorker>().build(),
            )
        }
    }
}
