package com.example.evergreen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.evergreen.capture.CaptureNotificationListenerService

/**
 * Notification access: shouted about when it is missing, silent when it is not.
 *
 * The full card costs about 100 dp -- a sixth of the NW-A307's 602 dp screen --
 * and it sat above the tab row, so every tab paid for it on every draw. Once
 * access is granted it has nothing left to say: "GRANTED" is not news, and the
 * two buttons behind it are settings shortcuts rather than anything the user
 * needs in front of them daily.
 *
 * So when access is granted this renders nothing at all, and the full card
 * ([AccessCard]) stays on the debug screen, where someone goes precisely when
 * capture has stopped working. Losing access is not silent: capture going quiet
 * raises a notification of its own, and the home screen's status line says so.
 */
@Composable
fun AccessBanner(
    accessGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (accessGranted) return
    AccessCard(
        accessGranted = false,
        onOpenNotificationSettings = onOpenNotificationSettings,
        onOpenAppInfo = onOpenAppInfo,
        modifier = modifier,
    )
}

/**
 * The full card, in both states. Used where the state itself is the point --
 * the debug screen -- rather than as a permanent banner.
 */
@Composable
fun AccessCard(
    accessGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        // Theme container colours rather than fixed ones, so the card stays
        // readable in dark mode.
        colors = CardDefaults.cardColors(
            containerColor = if (accessGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (accessGranted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (accessGranted) {
                    "Notification access: GRANTED"
                } else {
                    "Notification access: NOT GRANTED"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (accessGranted) {
                    "Capturing media sessions from ${
                        CaptureNotificationListenerService.ALLOWED_PACKAGES.joinToString()
                    }."
                } else {
                    "Needed to read media sessions. Notification contents are never read."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onOpenNotificationSettings) {
                    Text(if (accessGranted) "Settings" else "Grant access")
                }
                // Android blocks sideloaded apps from taking notification access
                // until "Allow restricted settings" is used, which lives on the
                // app info screen.
                OutlinedButton(onClick = onOpenAppInfo) {
                    Text("App info")
                }
            }
        }
    }
}
