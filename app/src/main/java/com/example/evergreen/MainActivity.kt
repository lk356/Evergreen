package com.example.evergreen

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
import com.example.evergreen.capture.CaptureNotificationListenerService
import com.example.evergreen.spotify.SpotifyAuth
import com.example.evergreen.spotify.SpotifyAuthActivity
import com.example.evergreen.spotify.SpotifyConnectionState
import com.example.evergreen.spotify.SpotifySyncWorker
import com.example.evergreen.ui.DebugScreen
import com.example.evergreen.ui.theme.EvergreenTheme

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
        if (SpotifyAuth(this).connectionState() is SpotifyConnectionState.Connected) {
            SpotifySyncWorker.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EvergreenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DebugScreen(
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
        if (SpotifyAuth(this).connectionState() is SpotifyConnectionState.Connected) {
            SpotifySyncWorker.schedule(this)
        }
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
