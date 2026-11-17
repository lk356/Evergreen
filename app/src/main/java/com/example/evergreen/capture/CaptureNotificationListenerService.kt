package com.example.evergreen.capture

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat

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
class CaptureNotificationListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private val watchers = mutableMapOf<MediaSession.Token, SessionWatcher>()
    private var broadcastReceiver: SpotifyBroadcastReceiver? = null
    private var writer: SegmentWriter? = null

    /**
     * One monitor for the whole device, not one per session.
     *
     * "Is this device making a sound" has a single answer however many media
     * sessions are open, and `registerAudioPlaybackCallback` is a device-wide
     * subscription. Registering it per watcher would mean several subscriptions
     * reporting the same thing on a device with little to spare. Changes are
     * fanned out to every live watcher instead.
     */
    private var audioMonitor: ActiveAudioMonitor? = null

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
        CaptureLog.add(EventSource.SYSTEM, "notification listener connected")

        writer = SegmentWriter(applicationContext, DeviceId.get(applicationContext))
        registerSpotifyBroadcasts()

        // Before the watchers, so that a session that is already playing when
        // this binds opens its first segment with the reading already in hand.
        audioMonitor = ActiveAudioMonitor(
            audio = getSystemService(AudioManager::class.java),
            handler = Handler(Looper.getMainLooper()),
            onChanged = { producing -> onActiveAudioChanged(producing) },
        ).also { it.start() }

        val manager = getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            CaptureLog.add(EventSource.SYSTEM, "MediaSessionManager unavailable")
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
            CaptureLog.add(
                EventSource.SYSTEM,
                "denied access to media sessions",
                detail = e.message,
            )
        }
    }

    override fun onListenerDisconnected() {
        CaptureLog.add(EventSource.SYSTEM, "notification listener disconnected")
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
        // stop() flushes each watcher's open segment, so this must happen before
        // the writer is shut down or the last listen of the session is lost.
        watchers.values.forEach { it.stop() }
        watchers.clear()
        audioMonitor?.stop()
        audioMonitor = null
        unregisterSpotifyBroadcasts()
        writer?.shutdown()
        writer = null
    }

    // --- Spotify broadcasts -------------------------------------------------

    /**
     * Registered at runtime rather than in the manifest: since Android 8,
     * manifest-declared receivers do not receive most implicit broadcasts, so a
     * manifest entry would silently never fire.
     */
    private fun registerSpotifyBroadcasts() {
        if (broadcastReceiver != null) return
        val receiver = SpotifyBroadcastReceiver()
        // RECEIVER_EXPORTED is mandatory from Android 13 for receivers that
        // listen to other apps' broadcasts; without it this throws at runtime.
        ContextCompat.registerReceiver(
            this,
            receiver,
            SpotifyBroadcastReceiver.intentFilter(),
            ContextCompat.RECEIVER_EXPORTED,
        )
        broadcastReceiver = receiver
        CaptureLog.add(EventSource.SYSTEM, "listening for Spotify broadcast intents")
    }

    private fun unregisterSpotifyBroadcasts() {
        val receiver = broadcastReceiver ?: return
        broadcastReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    // --- Media sessions -----------------------------------------------------

    private fun handleActiveSessions(controllers: List<MediaController>) {
        // Allowlist filter, applied before any metadata is read. Non-allowlisted
        // controllers are not stored, not logged by name, and not inspected.
        val allowed = controllers.filter { it.packageName in ALLOWED_PACKAGES }

        val ignoredCount = controllers.size - allowed.size
        if (ignoredCount > 0) {
            CaptureLog.add(
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

            val watcher = SessionWatcher(
                controller = controller,
                onSegmentClosed = { segment -> writer?.write(segment) },
                onSessionDestroyedCallback = { finished -> dropWatcher(finished) },
                producingMediaAudio = { audioMonitor?.isProducingMediaAudio },
            )
            watchers[token] = watcher
            CaptureLog.add(
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
            CaptureLog.add(
                EventSource.SESSION,
                "stopped watching media session",
                packageName = watcher.packageName,
            )
        }
    }

    /**
     * A change in whether this device is producing media audio.
     *
     * PRIVACY: gated on there being an allowlisted session to be about. The
     * audio framework is device-wide and says nothing about which app is
     * playing, so a transition arriving with no watcher open is a transition
     * caused by something Evergreen is not entitled to observe — it is dropped
     * here without being stored or logged, the same discipline the allowlist
     * applies to sessions. [ActiveAudioMonitor.isProducingMediaAudio] still
     * tracks it in memory, because a watcher opening a moment later has to know
     * what the device was already doing.
     */
    private fun onActiveAudioChanged(producing: Boolean) {
        if (watchers.isEmpty()) return
        watchers.values.forEach { it.onActiveAudio(producing) }
        CaptureLog.add(
            source = EventSource.SYSTEM,
            summary = if (producing) "this device is producing audio" else "this device is silent",
            detail = "media playback on this device; no app is identified",
        )
    }

    private fun dropWatcher(watcher: SessionWatcher) {
        val token = watchers.entries.firstOrNull { it.value === watcher }?.key ?: return
        watchers.remove(token)
    }

    companion object {
        /**
         * The only packages whose media sessions we will look at. Everything
         * else is discarded immediately.
         */
        val ALLOWED_PACKAGES = setOf("com.spotify.music")

        fun componentName(context: Context): ComponentName =
            ComponentName(context, CaptureNotificationListenerService::class.java)

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
