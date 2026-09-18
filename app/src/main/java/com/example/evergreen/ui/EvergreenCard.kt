package com.example.evergreen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.SyncKeys
import com.example.evergreen.spotify.EvergreenSync
import com.example.evergreen.spotify.EvergreenSyncResult
import com.example.evergreen.spotify.EvergreenSyncWorker
import com.example.evergreen.spotify.SpotifyApi
import com.example.evergreen.spotify.SpotifyAuth
import kotlinx.coroutines.launch

/**
 * The Evergreen playlist: its state, a dry run, and the one-time confirmation
 * that lets the app start writing.
 *
 * Deliberately the same shape as the same-song merge card. The first
 * irreversible action in this app is shown in full and confirmed once, and
 * after that it happens quietly on a schedule.
 */
@Composable
fun EvergreenCard() {
    val context = LocalContext.current
    val db = remember(context) { EvergreenDatabase.get(context) }
    val scoringDao = remember(db) { db.scoringDao() }
    val scope = rememberCoroutineScope()

    val memberCount by scoringDao.currentMemberCountFlow().collectAsState(initial = 0)
    val playlistIdRow by scoringDao.syncValueFlow(SyncKeys.EVERGREEN_PLAYLIST_ID)
        .collectAsState(initial = null)
    val lastWritten by scoringDao.syncValueFlow(SyncKeys.EVERGREEN_LAST_WRITTEN_AT)
        .collectAsState(initial = null)
    val confirmedAt by scoringDao.syncValueFlow(SyncKeys.EVERGREEN_WRITE_CONFIRMED_AT)
        .collectAsState(initial = null)

    var preview by remember { mutableStateOf<EvergreenSyncResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    fun newSync() = EvergreenSync(db = db, api = SpotifyApi(SpotifyAuth(context)))

    SectionTitle("Evergreen playlist")
    Stat("Tracks in the playlist", "$memberCount")
    Stat("Playlist created", if (playlistIdRow?.valueText != null) "yes" else "not yet")
    Stat("Last written", lastWritten?.valueMs?.let(::formatTimeShort) ?: "never")
    Stat("Writing enabled", if (confirmedAt?.valueMs != null) "yes" else "not confirmed")

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    preview = newSync().dryRun()
                    busy = false
                }
            },
        ) {
            Text(if (busy) "Working…" else "Preview Evergreen sync")
        }
        if (confirmedAt?.valueMs != null) {
            OutlinedButton(onClick = { EvergreenSyncWorker.syncNow(context) }) {
                Text("Sync now")
            }
        }
    }

    preview?.let { result ->
        EvergreenPreview(
            result = result,
            alreadyConfirmed = confirmedAt?.valueMs != null,
            onConfirm = { confirming = true },
            onDismiss = { preview = null },
        )
    }

    // The first write is the one irreversible step, so it asks -- once, and
    // after showing exactly what it will do.
    val pending = preview
    if (confirming && pending != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Let Evergreen write to Spotify?") },
            text = {
                Text(
                    "This will add ${pending.change.toAdd.size} and remove " +
                        "${pending.change.toRemove.size} track(s) in a private playlist " +
                        "called \"${EvergreenSync.PLAYLIST_NAME}\", and then keep it up to " +
                        "date once a day.\n\n" +
                        "Your Liked Songs are never touched — the app only reads them. " +
                        "Nothing is deleted from your library, and no other playlist is " +
                        "ever modified.\n\n" +
                        "Anything you add to this playlist by hand will be removed at the " +
                        "next sync, because the app owns its contents.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        busy = true
                        val sync = newSync()
                        sync.confirmFirstWrite()
                        preview = sync.sync()
                        busy = false
                    }
                }) {
                    Text("Write it")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Not yet") }
            },
        )
    }
}

@Composable
private fun EvergreenPreview(
    result: EvergreenSyncResult,
    alreadyConfirmed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = result.message ?: result.outcome.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = when (result.outcome) {
                EvergreenSyncResult.Outcome.FAILED,
                EvergreenSyncResult.Outcome.NEEDS_RECONNECT,
                -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )

        if (result.refused > 0) {
            Text(
                "${result.refused} track(s) were refused by Spotify, almost certainly " +
                    "unavailable in this region. They are skipped and still kept in the " +
                    "app's own record.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!result.change.isEmpty) {
            Stat("Would add", "${result.change.toAdd.size}")
            Stat("Would remove", "${result.change.toRemove.size}")

            // Capped: this is a diff summary, not a playlist browser, and the
            // whole list can be a hundred URIs on a small screen.
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(result.change.toAdd.take(PREVIEW_LIMIT)) { uri ->
                    DiffLine("+", uri, MaterialTheme.colorScheme.primary)
                }
                items(result.change.toRemove.take(PREVIEW_LIMIT)) { uri ->
                    DiffLine("−", uri, MaterialTheme.colorScheme.error)
                }
            }
            if (result.change.size > PREVIEW_LIMIT) {
                Text(
                    "Showing the first $PREVIEW_LIMIT of each. See the Scores tab for the " +
                        "full ranking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!alreadyConfirmed && !result.change.isEmpty) {
                Button(onClick = onConfirm) { Text("Confirm the first write") }
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun DiffLine(sign: String, uri: String, colour: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = sign,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = colour,
        )
        Text(
            text = uri.removePrefix("spotify:track:"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PREVIEW_LIMIT = 25
