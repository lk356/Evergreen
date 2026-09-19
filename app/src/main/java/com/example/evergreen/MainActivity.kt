package com.example.evergreen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.evergreen.capture.CaptureNotificationListenerService
import com.example.evergreen.capture.CaptureWatchWorker
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.spotify.SpotifyAuth
import com.example.evergreen.spotify.SpotifyAuthActivity
import com.example.evergreen.spotify.SpotifyConnectionState
import com.example.evergreen.scoring.SnapshotWorker
import com.example.evergreen.spotify.EvergreenSyncWorker
import com.example.evergreen.spotify.SpotifySyncWorker
import com.example.evergreen.ui.EvergreenApp
import com.example.evergreen.ui.theme.EvergreenTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Re-read on every resume: the user grants access in Settings and comes
    // back, so the value is stale the moment we leave the activity.
    private val accessGranted = mutableStateOf(false)

    // Bumped whenever the login flow returns, so the Spotify tab re-reads the
    // connection. It lives in the Keystore rather than the database, so there is
    // no Flow to observe.
    private val connectionEpoch = mutableIntStateOf(0)

    private val connectSpotify = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        connectionEpoch.intValue++
        // Scheduling only after a successful connection means a fresh install
        // never wakes the device for a sync it cannot perform.
        scheduleSpotifyWorkIfAppropriate()
    }

    /**
     * Arms the two Spotify schedules, unless this database is a mirror.
     *
     * The mirror check is the reason this is a coroutine rather than the two
     * straight-line calls it used to be. Importing a mirror cancels both
     * schedules; without this, the next time the app was opened would quietly
     * put them back, and the whole guard would last exactly until the user
     * looked at the app. Reading `sync_state` is one indexed lookup, but it is
     * a database read and so does not belong on the main thread.
     *
     * The workers refuse on a mirror as well — this is about not waking the
     * device, not about correctness, which is theirs.
     */
    private fun scheduleSpotifyWorkIfAppropriate() {
        if (SpotifyAuth(this).connectionState() !is SpotifyConnectionState.Connected) return
        lifecycleScope.launch {
            if (EvergreenDatabase.get(this@MainActivity).importDao().isMirror()) return@launch
            SpotifySyncWorker.schedule(this@MainActivity)
            // The Evergreen sync schedules itself alongside the like sync, and
            // cancels itself on its first run if this device is not the writer.
            EvergreenSyncWorker.schedule(this@MainActivity)
        }
    }

    /**
     * The only runtime permission this app asks for.
     *
     * Asked once, on first launch, and never again -- Android itself stops
     * showing the dialog after a refusal, and nagging through a rationale
     * screen would be asking for something the app works perfectly well
     * without. Nothing is gated on the answer; a refusal costs the warnings and
     * nothing else.
     */
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Granted or not, the app behaves the same. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Notifications.createChannels(this)
        if (!Notifications.canPost(this)) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            EvergreenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EvergreenApp(
                        accessGranted = accessGranted.value,
                        onOpenNotificationSettings = ::openNotificationListenerSettings,
                        onOpenAppInfo = ::openAppInfo,
                        onConnectSpotify = { connectSpotify.launch(SpotifyAuthActivity.intent(this)) },
                        connectionEpoch = connectionEpoch.intValue,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessGranted.value = CaptureNotificationListenerService.isAccessGranted(this)

        // The connection lives in the Keystore, not the database, so there is no
        // Flow to observe and a background sync that expires it cannot notify
        // the UI. Re-reading on resume is what stops the card showing a stale
        // "Connected" after the refresh token has died.
        connectionEpoch.intValue++

        // Keeps the schedule alive across reinstalls and reboots, and re-arms it
        // after a reconnect. KEEP means an existing schedule is not restarted,
        // so opening the app does not reset the interval.
        scheduleSpotifyWorkIfAppropriate()
        // The snapshot worker needs no connection and no network: it reads the
        // app's own database. So it is scheduled unconditionally, and keeps
        // recording months even while Spotify is disconnected.
        SnapshotWorker.schedule(this)

        // Likewise the capture watchdog, and for a stronger reason: capture is
        // the one thing here that keeps working while Spotify is disconnected,
        // and the one whose failure loses data rather than delaying it.
        CaptureWatchWorker.schedule(this)
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Where "Allow restricted settings" lives for sideloaded installs. */
    private fun openAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
