package com.example.evergreen.capture

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.evergreen.Notifications
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.SyncKeys
import java.util.concurrent.TimeUnit

/**
 * Notices when capture has stopped, and says so.
 *
 * This is the only failure in the app that loses data outright. A paused sync
 * catches up when it is fixed; a playlist that is not written gets written
 * tomorrow. Plays that were never recorded are simply gone, and Spotify will
 * not export them again on demand.
 *
 * It is also the part of the app standing on the least certain ground: capture
 * depends on an undocumented Spotify broadcast setting and on media-session
 * behaviour that was reverse-engineered on a device. If Spotify changes the
 * broadcast, or an OS update drops the notification-listener permission after a
 * restart, everything else keeps running perfectly and records nothing. The
 * home screen's status line says so too, but a status line only helps someone
 * who opens the app -- and the point of this phase is not having to.
 *
 * **It does not read anything new.** The check is `MAX(endedAtMs)` over
 * captured segments, which the database already stores.
 *
 * Nothing in `capture/` else was touched: this is a new file alongside the
 * package rather than a change to how capture works.
 */
class CaptureWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = EvergreenDatabase.get(applicationContext)
        val scoringDao = db.scoringDao()

        val days = scoringDao.syncStateMs(SyncKeys.CAPTURE_SILENT_DAYS) ?: DEFAULT_SILENT_DAYS
        if (days <= 0L) return Result.success()

        val lastCaptured = db.dao().lastCapturedAtMsOnce()
        // Nothing has ever been captured. That is a fresh install rather than a
        // fault, and the home screen already says so in the right words; a
        // notification here would greet every new install with an alarm.
        if (lastCaptured == null) return Result.success()

        val now = System.currentTimeMillis()
        val silentFor = now - lastCaptured
        if (silentFor < TimeUnit.DAYS.toMillis(days)) return Result.success()

        // One notification per quiet stretch, not one a day. The marker is
        // compared against the last capture rather than against the clock, so
        // it re-arms itself the moment listening resumes -- no separate reset
        // step, and nothing to get out of sync.
        val notifiedAt = scoringDao.syncStateMs(SyncKeys.CAPTURE_SILENT_NOTIFIED_AT)
        if (notifiedAt != null && notifiedAt > lastCaptured) return Result.success()

        val silentDays = TimeUnit.MILLISECONDS.toDays(silentFor)
        Log.i(TAG, "capture silent for $silentDays days; notifying")
        Notifications.postCaptureSilent(applicationContext, silentDays)
        scoringDao.putSyncMs(SyncKeys.CAPTURE_SILENT_NOTIFIED_AT, now)
        return Result.success()
    }

    companion object {
        private const val TAG = "CaptureWatchWorker"
        private const val PERIODIC_WORK = "evergreen-capture-watch"

        /**
         * A week.
         *
         * Generous on purpose. A quiet stretch -- a holiday, a week of
         * podcasts, a fortnight of listening on something else -- is not a
         * fault, and an alert that fires after two days is one that gets
         * switched off, which would cost the whole feature.
         */
        const val DEFAULT_SILENT_DAYS = 7L

        /**
         * Daily, with no network constraint: this reads the app's own database
         * and needs nothing from Spotify. Scheduled unconditionally, because
         * capture runs whether or not Spotify is connected.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CaptureWatchWorker>(24, TimeUnit.HOURS).build(),
            )
        }
    }
}
