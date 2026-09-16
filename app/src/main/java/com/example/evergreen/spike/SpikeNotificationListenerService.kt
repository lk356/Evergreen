package com.example.evergreen.spike

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Exists for one reason: Android only hands out active media sessions to apps
 * that hold notification-listener access. This service is the key to that door.
 *
 * PRIVACY: it never reads notification content. [onNotificationPosted] and
 * [onNotificationRemoved] are deliberately empty and must stay that way. The
 * only data this class touches is media-session metadata from packages on
 * [ALLOWED_PACKAGES]; every other session is dropped before its metadata is
 * read.
 */
class SpikeNotificationListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private val watchers = mutableMapOf<MediaSession.Token, SessionWatcher>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            handleActiveSessions(controllers.orEmpty())
        }

    // --- Notifications: intentionally ignored -------------------------------

    /** Never inspect notification content. See the class comment. */
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

    /** Never inspect notification content. See the class comment. */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    // --- Listener lifecycle -------------------------------------------------

    override fun onListenerConnected() {
        super.onListenerConnected()
        SpikeLog.add(EventSource.SYSTEM, "notification listener connected")

        val manager = getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            SpikeLog.add(EventSource.SYSTEM, "MediaSessionManager unavailable")
            return
        }
        sessionManager = manager

        val component = componentName(this)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            handleActiveSessions(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Thrown when notification access is not actually granted, which can
            // happen briefly after the permission is toggled off.
            SpikeLog.add(
                EventSource.SYSTEM,
                "denied access to media sessions",
                detail = e.message,
            )
        }
    }

    override fun onListenerDisconnected() {
        SpikeLog.add(EventSource.SYSTEM, "notification listener disconnected")
        teardown()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        sessionManager = null
        watchers.values.forEach { it.stop() }
        watchers.clear()
    }

    // --- Media sessions -----------------------------------------------------

    private fun handleActiveSessions(controllers: List<MediaController>) {
        // Allowlist filter, applied before any metadata is read. Non-allowlisted
        // controllers are not stored, not logged by name, and not inspected.
        val allowed = controllers.filter { it.packageName in ALLOWED_PACKAGES }

        val ignoredCount = controllers.size - allowed.size
        if (ignoredCount > 0) {
            SpikeLog.add(
                EventSource.SESSION,
                "ignored $ignoredCount non-allowlisted session(s)",
                detail = "package names not recorded, by policy",
            )
        }

        val liveTokens = mutableSetOf<MediaSession.Token>()
        for (controller in allowed) {
            val token = controller.sessionToken
            liveTokens += token
            if (watchers.containsKey(token)) continue

            val watcher = SessionWatcher(controller) { finished -> dropWatcher(finished) }
            watchers[token] = watcher
            SpikeLog.add(
                EventSource.SESSION,
                "watching media session",
                packageName = controller.packageName,
            )
            watcher.start()
        }

        val goneTokens = watchers.keys - liveTokens
        for (token in goneTokens) {
            val watcher = watchers.remove(token) ?: continue
            watcher.stop()
            SpikeLog.add(
                EventSource.SESSION,
                "stopped watching media session",
                packageName = watcher.packageName,
            )
        }
    }

    private fun dropWatcher(watcher: SessionWatcher) {
        val token = watchers.entries.firstOrNull { it.value === watcher }?.key ?: return
        watchers.remove(token)?.stop()
    }

    companion object {
        /**
         * The only packages whose media sessions we will look at. Everything
         * else is discarded immediately.
         */
        val ALLOWED_PACKAGES = setOf("com.spotify.music")

        fun componentName(context: Context): ComponentName =
            ComponentName(context, SpikeNotificationListenerService::class.java)

        /**
         * True when the user has granted this app notification-listener access.
         *
         * Read from Settings.Secure rather than asked of the service, because
         * the service is not running until access is granted.
         */
        fun isAccessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val target = componentName(context)
            return enabled.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == target
            }
        }
    }
}
