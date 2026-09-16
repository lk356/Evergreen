package com.example.evergreen.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SpikeScreen(
    accessGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val events by SpikeLog.events.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        AccessCard(
            accessGranted = accessGranted,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenAppInfo = onOpenAppInfo,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${events.size} event(s), newest first",
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedButton(onClick = { SpikeLog.clear() }, enabled = events.isNotEmpty()) {
                Text("Clear")
            }
        }

        HorizontalDivider()

        if (events.isEmpty()) {
            Text(
                text = if (accessGranted) {
                    "Waiting for Spotify. Start playing something."
                } else {
                    "Grant notification access to start capturing."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { event ->
                    EventRow(event)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AccessCard(
    accessGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                        SpikeNotificationListenerService.ALLOWED_PACKAGES.joinToString()
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

@Composable
private fun EventRow(event: SpikeEvent) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = event.formattedTime(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = event.source.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = sourceColor(event.source),
            )
        }
        Text(
            text = event.summary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
        event.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun sourceColor(source: EventSource): Color = when (source) {
    EventSource.VERDICT -> MaterialTheme.colorScheme.primary
    EventSource.BROADCAST -> MaterialTheme.colorScheme.tertiary
    EventSource.SYSTEM -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
