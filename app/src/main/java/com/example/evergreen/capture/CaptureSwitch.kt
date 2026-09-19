package com.example.evergreen.capture

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Turns capture off, and back on, on this device.
 *
 * **A new file beside `capture/`, changing nothing inside it** — the same
 * arrangement as `CaptureWatchWorker` in Phase 4. Nothing about how a segment
 * is recognised, cut or written is touched here; this only decides whether the
 * listener runs at all.
 *
 * ### Why the component, and not a flag the writer checks
 *
 * The alternative was a `sync_state` row that `SegmentWriter` consults before
 * every write. It would have been three lines, and it would have been worse in
 * two ways. It changes `capture/`, for a decision that is not capture's to
 * make. And it leaves the listener bound and reading media sessions in order to
 * throw the results away, so "capture is off" would be a statement about a
 * discard rather than about the app: the live log would still fill up, and
 * anyone checking would see a service doing exactly what it does when it is on.
 *
 * Disabling the component means the system unbinds the service, the Spotify
 * broadcast receiver it registers at runtime goes with it, and nothing is read
 * in the first place. "Off" means off.
 *
 * ### The permission survives
 *
 * Notification-listener access is recorded per component in
 * `Settings.Secure.enabled_notification_listeners`, and that entry is cleaned
 * up when the *package* goes away, not when a component is disabled. So
 * disabling and re-enabling does not cost the user another trip through the
 * system settings to grant access again — this is the same disable/enable pair
 * that is the standard way to force a dead notification listener to rebind.
 *
 * [isEnabled] is therefore a different question from
 * `CaptureNotificationListenerService.isAccessGranted`: that one asks whether
 * the user has granted access, this one asks whether the app is using it.
 */
object CaptureSwitch {

    private const val TAG = "CaptureSwitch"

    /** True when the listener component is allowed to run. */
    fun isEnabled(context: Context): Boolean {
        val component = CaptureNotificationListenerService.componentName(context)
        return when (context.packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            // DEFAULT means "as declared in the manifest", which is enabled.
            else -> true
        }
    }

    /**
     * Stops the listener, or starts it again.
     *
     * `DONT_KILL_APP` because the process has other work in flight — this is
     * called from the middle of a mirror import — and killing it would take
     * that with it. The service is unbound either way.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val component = CaptureNotificationListenerService.componentName(context)
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
        Log.i(TAG, "capture ${if (enabled) "enabled" else "disabled"} on this device")
    }
}
