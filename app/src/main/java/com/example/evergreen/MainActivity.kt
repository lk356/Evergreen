package com.example.evergreen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.example.evergreen.spike.SpikeNotificationListenerService
import com.example.evergreen.spike.SpikeScreen
import com.example.evergreen.ui.theme.EvergreenTheme

class MainActivity : ComponentActivity() {

    // Re-read on every resume: the user grants access in Settings and comes
    // back, so the value is stale the moment we leave the activity.
    private val accessGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EvergreenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SpikeScreen(
                        accessGranted = accessGranted.value,
                        onOpenNotificationSettings = ::openNotificationListenerSettings,
                        onOpenAppInfo = ::openAppInfo,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessGranted.value = SpikeNotificationListenerService.isAccessGranted(this)
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
