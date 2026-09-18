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

    /** Reconnect required. On by default — the only silent failure that costs data. */
    const val CHANNEL_CONNECTION = "connection"

    /** A monthly snapshot was taken. Built, off by default. */
    const val CHANNEL_SNAPSHOTS = "snapshots"

    /** Songs joined or left Evergreen. Built, off by default. */
    const val CHANNEL_PLAYLIST = "playlist"

    private const val TAG = "Notifications"

    private const val ID_RECONNECT = 1
    private const val ID_SNAPSHOT = 2
    private const val ID_PLAYLIST = 3

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

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYLIST,
                "Playlist changes",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Songs joined or left the Evergreen playlist."
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

    /** Counts only — a lock screen is not the place to list what someone listens to. */
    fun postPlaylistChanged(context: Context, added: Int, removed: Int) {
        if (added == 0 && removed == 0) return
        post(
            context = context,
            id = ID_PLAYLIST,
            channel = CHANNEL_PLAYLIST,
            title = "Evergreen updated",
            text = buildString {
                if (added > 0) append("$added joined")
                if (added > 0 && removed > 0) append(", ")
                if (removed > 0) append("$removed left")
            },
        )
    }

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
