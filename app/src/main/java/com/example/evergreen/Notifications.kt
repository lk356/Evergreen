package com.example.evergreen

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The app's notifications. There are three channels and one of them is on.
 *
 * **Channels rather than a single stream**, because Android then lets the user
 * silence any of them without silencing the others, from the system settings
 * rather than from a preferences screen this app does not have yet.
 *
 * Nothing here is gated on the permission being granted: every post is a
 * best-effort attempt that quietly does nothing if it is refused. Declining the
 * prompt costs the warnings and nothing else.
 *
 * **No notification ever names a track.** These appear on a lock screen, which
 * is a more public place than the app, and none of what they have to say needs
 * a title: "3 songs joined Evergreen" carries the same information as listing
 * them, and the app is where the list belongs.
 */
object Notifications {

    /** Reconnect required. On by default — a silent failure that costs data. */
    const val CHANNEL_CONNECTION = "connection"

    /** A monthly snapshot was taken. Quiet by default. */
    const val CHANNEL_SNAPSHOTS = "snapshots"

    /**
     * Songs joined or left Evergreen. On by default as of Phase 4.
     *
     * **The id changed, and it had to.** Android sets a channel's importance
     * once, at creation, and deliberately ignores later changes so an app
     * cannot make itself noisier than the user agreed to. The original
     * `playlist` channel was created at IMPORTANCE_LOW, so raising it in place
     * is impossible; a new id is the only way. The old one is deleted so it
     * does not sit in the system settings as a second, dead entry.
     *
     * The point of the change is not having to open the app: the changes come
     * to the user and the app gets opened when something looks wrong. What
     * stops it being noise is already in place -- hysteresis, the once-a-day
     * write guard, one summarising notification per sync rather than one per
     * track, and suppression entirely when the movement was caused by a
     * parameter change rather than by a day passing.
     */
    const val CHANNEL_PLAYLIST = "playlist_v2"

    /** The pre-Phase-4 id, deleted on first run. See [CHANNEL_PLAYLIST]. */
    private const val CHANNEL_PLAYLIST_OLD = "playlist"

    /**
     * Capture has recorded nothing for a while. On by default.
     *
     * The most valuable alert in the app, because it is the only failure that
     * loses data outright. A paused sync catches up when it is fixed; plays
     * that were never recorded are gone. Capture also rests on the least
     * certain ground here -- an undocumented Spotify broadcast setting and
     * media-session behaviour reverse-engineered on a device -- so if Spotify
     * changes the broadcast, or an OS update drops the notification-listener
     * permission after a reboot, the app keeps running and silently records
     * nothing. A status line only helps someone who looks at it.
     */
    const val CHANNEL_CAPTURE = "capture"

    private const val TAG = "Notifications"

    private const val ID_RECONNECT = 1
    private const val ID_SNAPSHOT = 2
    private const val ID_PLAYLIST = 3
    private const val ID_CAPTURE_SILENT = 4

    /**
     * Creates the channels. Safe to call repeatedly.
     *
     * Importance is set once, at creation: Android deliberately ignores later
     * changes so an app cannot make itself noisier than the user agreed to.
     * That is why the two optional channels are created at [NotificationManager.IMPORTANCE_LOW]
     * — they appear silently in the shade, and the user can raise them.
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION,
                "Connection",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Tells you when Spotify needs reconnecting. " +
                    "Until it is, likes stop syncing and Evergreen stops being updated."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SNAPSHOTS,
                "Snapshots",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "A monthly snapshot of Evergreen was taken."
            },
        )

        // Deleted before the replacement is created, so the system settings
        // show one "Playlist changes" entry rather than two.
        manager.deleteNotificationChannel(CHANNEL_PLAYLIST_OLD)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYLIST,
                "Playlist changes",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Songs joined or left the Evergreen playlist. At most one a " +
                    "day, summarising, and never one per song."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                "Capture has stopped",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Tells you when no listening has been recorded for a while. " +
                    "Listening that is never captured cannot be recovered later."
            },
        )
    }

    /** True when the app may post. False is an ordinary state, not an error. */
    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The one notification that is on by default.
     *
     * The refresh token dies six months after the original login — refreshing
     * does not extend it — and until the user logs in again, the likes stop
     * syncing and Evergreen stops being written. Nothing on the device says so
     * unless the app is opened. This is the only failure in the app that
     * silently costs data, which is why it is the only one that interrupts.
     */
    fun postReconnectRequired(context: Context, expiringSoon: Boolean = false) {
        post(
            context = context,
            id = ID_RECONNECT,
            channel = CHANNEL_CONNECTION,
            title = if (expiringSoon) "Spotify login expires soon" else "Reconnect Spotify",
            text = if (expiringSoon) {
                "Evergreen will stop updating when it expires. Open the app to reconnect."
            } else {
                "Likes are not syncing and Evergreen is not being updated. " +
                    "Open the app to reconnect."
            },
        )
    }

    fun postSnapshotTaken(context: Context, label: String, trackCount: Int) {
        post(
            context = context,
            id = ID_SNAPSHOT,
            channel = CHANNEL_SNAPSHOTS,
            title = "Snapshot taken: $label",
            text = "$trackCount tracks recorded.",
        )
    }

    /**
     * One notification per sync, summarising.
     *
     * Counts only, never titles: a lock screen is a more public place than the
     * app, and "3 songs joined Evergreen" carries the same information as
     * listing them. **Never one per track** — a sync can produce one change or
     * forty, and forty notifications for one run would be the end of anyone
     * leaving this channel on. A fixed notification id means a second sync
     * replaces the first rather than stacking.
     */
    fun postPlaylistChanged(context: Context, added: Int, removed: Int) {
        if (added == 0 && removed == 0) return
        post(
            context = context,
            id = ID_PLAYLIST,
            channel = CHANNEL_PLAYLIST,
            title = "Evergreen updated",
            text = summarisePlaylistChange(added, removed),
        )
    }

    /** The wording of [postPlaylistChanged], separated so it can be tested. */
    fun summarisePlaylistChange(added: Int, removed: Int): String = buildString {
        if (added > 0) append("$added song${plural(added)} joined")
        if (added > 0 && removed > 0) append(", ")
        if (removed > 0) append("$removed left")
    }

    /**
     * Nothing has been recorded for a while.
     *
     * Says how long rather than when, and names the two things that actually
     * cause it: Spotify's "Device Broadcast Status" setting being turned off,
     * and the notification-listener permission being dropped after a restart.
     * Both are things the user can check in a minute; neither is guessable from
     * "capture has stopped".
     */
    fun postCaptureSilent(context: Context, days: Long) {
        post(
            context = context,
            id = ID_CAPTURE_SILENT,
            channel = CHANNEL_CAPTURE,
            title = "Nothing recorded for $days days",
            text = "If you have been listening, Evergreen is missing it — and listening " +
                "that is never recorded cannot be recovered. Check that Spotify's " +
                "\"Device Broadcast Status\" is still on, and that this app still has " +
                "notification access.",
        )
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        if (!canPost(context)) return

        val openApp = android.app.PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (denied: SecurityException) {
            // The permission can be revoked between the check and the post.
            // Nothing here is important enough to crash over.
            Log.i(TAG, "notification not posted: permission refused")
        }
    }
}
