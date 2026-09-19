package com.example.evergreen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.scoring.SnapshotResult
import com.example.evergreen.scoring.Snapshots
import com.example.evergreen.spotify.SpotifyApi
import com.example.evergreen.spotify.SpotifyAuth
import kotlinx.coroutines.launch

/**
 * Monthly snapshots: the list, a manual "take one now", and export on demand.
 *
 * Phase 4 owns searching these ("2026 August"); Phase 3's job is to store them
 * in a shape that makes it a query.
 */
@Composable
fun SnapshotsCard(onOpenSnapshot: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val db = remember(context) { EvergreenDatabase.get(context) }
    val scope = rememberCoroutineScope()

    val snapshots by db.scoringDao().snapshots().collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    SectionTitle("Monthly snapshots")
    Stat("Snapshots stored", "${snapshots.size}")

    status?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (snapshots.isEmpty()) {
        Text(
            "Taken automatically on the last day of each month. Nothing yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    snapshots.forEach { snapshot ->
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenSnapshot(snapshot.id) },
            ) {
                Text(snapshot.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${snapshot.playlistSize} in the playlist, " +
                        "${snapshot.trackCount} ranked" +
                        (snapshot.exportedAt?.let { "  ·  exported" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = null
                        val api = SpotifyApi(SpotifyAuth(context))
                        val playlistId = Snapshots(db).export(snapshot.id, api)
                        status = if (playlistId != null) {
                            "Exported \"${Snapshots.EXPORT_PREFIX}${snapshot.label}\" to Spotify."
                        } else {
                            "Export failed. Check the Spotify connection above."
                        }
                        busy = false
                    }
                },
            ) {
                Text(if (snapshot.exportedAt != null) "Export again" else "Export")
            }
        }
    }

    OutlinedButton(
        enabled = !busy,
        onClick = {
            scope.launch {
                busy = true
                status = when (val result = Snapshots(db).takeIfDue(force = true)) {
                    is SnapshotResult.Taken ->
                        "Snapshot ${result.label} taken: ${result.trackCount} tracks."
                    is SnapshotResult.AlreadyExists ->
                        "There is already a snapshot for ${result.monthKey}."
                    is SnapshotResult.Empty ->
                        "Nothing to snapshot yet — no liked tracks have been scored."
                    is SnapshotResult.NotDue -> "Not due."
                }
                busy = false
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(if (busy) "Working…" else "Take a snapshot now")
    }
    Text(
        "A snapshot stores the whole ranking — not just the hundred in the playlist — " +
            "with the scores and the settings that produced them, so it still reads " +
            "correctly after the scoring is retuned. Exporting creates a new Spotify " +
            "playlist the app never touches again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
