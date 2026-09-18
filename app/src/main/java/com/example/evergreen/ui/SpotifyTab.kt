package com.example.evergreen.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.MergePlanGroup
import com.example.evergreen.data.SameSongMerge
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.SyncKeys
import com.example.evergreen.history.HistoryImportController
import com.example.evergreen.spotify.SpotifyApi.Companion.TRACK_URI_PREFIX
import com.example.evergreen.spotify.SpotifyAuth
import com.example.evergreen.spotify.SpotifyConnectionState
import com.example.evergreen.spotify.SpotifySyncWorker
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Connection state, sync status and ISRC coverage.
 *
 * A separate file from [DebugScreen] so the Phase 1 segment view is left exactly
 * as it was.
 */
@Composable
fun SpotifyTab(
    onConnect: () -> Unit,
    connectionEpoch: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dao = remember(context) { EvergreenDatabase.get(context).spotifyDao() }
    val scope = rememberCoroutineScope()

    // Re-read whenever the connect flow returns; the state is not a Flow because
    // it lives in the Keystore rather than the database.
    var state by remember { mutableStateOf<SpotifyConnectionState?>(null) }
    var configured by remember { mutableStateOf(true) }
    LaunchedEffect(connectionEpoch) {
        val auth = SpotifyAuth(context)
        configured = auth.isConfigured
        state = auth.connectionState()
    }

    val likedNow by dao.likedNowCount().collectAsState(initial = 0)
    val archived by dao.archivedLikeCount().collectAsState(initial = 0)
    val events by dao.likeEventCount().collectAsState(initial = 0)
    val serviceTracks by dao.serviceTrackCount(SERVICE_SPOTIFY, TRACK_URI_PREFIX)
        .collectAsState(initial = 0)
    val withIsrc by dao.serviceTracksWithIsrcCount(SERVICE_SPOTIFY, TRACK_URI_PREFIX)
        .collectAsState(initial = 0)
    val nonTrackRows by dao.nonTrackServiceRowCount(SERVICE_SPOTIFY, TRACK_URI_PREFIX)
        .collectAsState(initial = 0)
    val failures by dao.failedIsrcLookupBreakdown(SERVICE_SPOTIFY, TRACK_URI_PREFIX)
        .collectAsState(initial = emptyList())
    val lastSync by dao.syncStateFlow(SyncKeys.LIKES_LAST_SYNCED_AT).collectAsState(initial = null)
    val backoffUntil by dao.syncStateFlow(SyncKeys.API_BACKOFF_UNTIL).collectAsState(initial = null)
    val backoffReason by dao.syncStateFlow(SyncKeys.API_BACKOFF_REASON).collectAsState(initial = null)

    var mergePlan by remember { mutableStateOf<List<MergePlanGroup>?>(null) }
    var confirmingMerge by remember { mutableStateOf(false) }
    var mergeSummary by remember { mutableStateOf<String?>(null) }
    val merges by dao.mergeCount().collectAsState(initial = 0)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        ConnectionCard(
            state = state,
            configured = configured,
            onConnect = onConnect,
            onDisconnect = {
                SpotifyAuth(context).disconnect()
                SpotifySyncWorker.cancel(context)
                state = SpotifyConnectionState.NotConnected
            },
        )

        SectionTitle("Likes")
        Stat("Liked now", "$likedNow")
        Stat("In the archive (ever liked)", "$archived")
        Stat("Recorded like/unlike events", "$events")
        Stat("Last like sync", lastSync?.valueMs?.let(::formatTime) ?: "never")

        if (backoffUntil?.valueMs != null && backoffUntil!!.valueMs!! > System.currentTimeMillis()) {
            Text(
                text = "Backing off until ${formatTime(backoffUntil!!.valueMs!!)}" +
                    (backoffReason?.valueText?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionTitle("ISRC coverage")
        Stat("Spotify tracks known", "$serviceTracks")
        Stat(
            "With an ISRC",
            if (serviceTracks == 0) "0" else "$withIsrc (${withIsrc * 100 / serviceTracks}%)",
        )
        if (failures.isEmpty()) {
            Stat("Failed lookups", "none")
        } else {
            failures.forEach { Stat("Failed: ${it.reason}", "${it.count}") }
        }
        if (nonTrackRows > 0) {
            Stat("Non-track rows (DJ commentary)", "$nonTrackRows")
            Text(
                "Spotify DJ commentary is stored as `spotify:media:…` rather than " +
                    "`spotify:track:…`. It is kept, not discarded, and excluded from " +
                    "the coverage figures above because it can never have an ISRC.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { SpotifySyncWorker.syncNow(context) },
                enabled = state is SpotifyConnectionState.Connected ||
                    state is SpotifyConnectionState.ExpiringSoon,
            ) {
                Text("Sync now")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    mergePlan = SameSongMerge.plan(dao.sameIsrcMergeCandidates())
                    mergeSummary = null
                }
            }) {
                Text("Merge dry run")
            }
        }

        // Debug affordance, and the only practical way to exercise the
        // reconnect path.
        //
        // Revoking access at spotify.com/account/apps kills the *refresh* token
        // but leaves the existing access token working until it expires about an
        // hour later, so a sync straight afterwards simply succeeds. This
        // discards the access token locally, forcing the very next request to
        // refresh -- which is when a revoked or six-month-expired grant actually
        // comes back as invalid_grant and flips the app to "reconnect".
        //
        // It fakes nothing: the real refresh happens, against the real endpoint.
        if (state is SpotifyConnectionState.Connected ||
            state is SpotifyConnectionState.ExpiringSoon
        ) {
            OutlinedButton(
                onClick = {
                    SpotifyAuth(context).invalidateAccessToken()
                    SpotifySyncWorker.syncNow(context)
                },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Force token refresh (test reconnect)")
            }
            Text(
                "Discards the access token so the next sync must refresh. " +
                    "Revoke access at spotify.com/account/apps first to see the " +
                    "reconnect state; otherwise the refresh just succeeds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DeviceCard()

        HistoryImportCard()

        EvergreenCard()

        SnapshotsCard()

        MergeDryRun(
            plan = mergePlan,
            mergesRun = merges,
            summary = mergeSummary,
            onMerge = { confirmingMerge = true },
        )

        // Merging is the only operation in this app that deletes a row, so it
        // asks first -- every time, and after showing exactly what it will do.
        val plan = mergePlan
        if (confirmingMerge && plan != null) {
            val tracks = plan.sumOf { it.losers.size }
            val segments = plan.sumOf { it.segmentsToMove }
            AlertDialog(
                onDismissRequest = { confirmingMerge = false },
                title = { Text("Merge $tracks track(s)?") },
                text = {
                    Text(
                        "This folds $tracks canonical track(s) into their " +
                            "counterparts and re-points $segments segment(s).\n\n" +
                            "No listening history is deleted \u2014 segments move to " +
                            "the surviving track. Your likes are unaffected: both " +
                            "releases stay in the archive.\n\n" +
                            "Every merge is recorded so it stays explainable, but " +
                            "there is no undo button."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingMerge = false
                        scope.launch {
                            var moved = 0
                            var merged = 0
                            plan.forEach { group ->
                                group.losers.forEach { loser ->
                                    val record = dao.mergeTracks(
                                        survivorId = group.survivor.trackId,
                                        loserId = loser.trackId,
                                        isrc = group.isrc,
                                        now = System.currentTimeMillis(),
                                    )
                                    moved += record.segmentsMoved
                                    merged++
                                }
                            }
                            mergeSummary =
                                "Merged $merged track(s); $moved segment(s) re-pointed, none deleted."
                            mergePlan = SameSongMerge.plan(dao.sameIsrcMergeCandidates())
                        }
                    }) { Text("Merge") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingMerge = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * The connection, in the four states it can actually be in.
 *
 * "Not connected" and "connection expired" say different things deliberately:
 * one is an invitation, the other is a six-monthly chore, and showing the wrong
 * one leaves the user with no idea why syncing stopped.
 */
@Composable
private fun ConnectionCard(
    state: SpotifyConnectionState?,
    configured: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (headline, detail, isProblem) = when {
        !configured -> Triple(
            "Not configured",
            "No client ID. Add spotify.clientId to local.properties and rebuild.",
            true,
        )
        state == null -> Triple("Checking…", "", false)
        state is SpotifyConnectionState.NotConnected -> Triple(
            "Not connected",
            "Evergreen will ask Spotify only to read your saved songs.",
            false,
        )
        state is SpotifyConnectionState.Expired -> Triple(
            "Connection expired — reconnect Spotify",
            "Spotify refresh tokens last six months. Syncing has stopped until " +
                "you sign in again. Nothing in the archive was lost.",
            true,
        )
        state is SpotifyConnectionState.ExpiringSoon -> Triple(
            "Reconnect soon",
            "This connection expires ${formatTime(state.refreshTokenExpiresAtMs)} " +
                "(${daysUntil(state.refreshTokenExpiresAtMs)} days). Sync keeps " +
                "working until then.",
            true,
        )
        state is SpotifyConnectionState.Connected -> Triple(
            "Connected",
            "Expires ${formatTime(state.refreshTokenExpiresAtMs)} " +
                "(${daysUntil(state.refreshTokenExpiresAtMs)} days). " +
                "Scope: ${state.grantedScopes}",
            false,
        )
        else -> Triple("", "", false)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isProblem) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (configured && state !is SpotifyConnectionState.Connected) {
                    Button(onClick = onConnect) {
                        Text(
                            if (state is SpotifyConnectionState.Expired) {
                                "Reconnect Spotify"
                            } else {
                                "Connect Spotify"
                            },
                        )
                    }
                }
                if (state is SpotifyConnectionState.Connected ||
                    state is SpotifyConnectionState.ExpiringSoon
                ) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
        }
    }
}

/**
 * What an ISRC-based merge *would* do. It changes nothing.
 *
 * `trackKey` includes the album, so the single and the album release of a song
 * are two canonical tracks today. An ISRC identifies the recording, which makes
 * it the obvious candidate for merging them — but whether it is the right rule
 * is a judgement about real data, so this reports and stops.
 */
@Composable
private fun MergeDryRun(
    plan: List<MergePlanGroup>?,
    mergesRun: Int,
    summary: String?,
    onMerge: () -> Unit,
) {
    if (plan == null) {
        if (mergesRun > 0) {
            SectionTitle("Merges")
            Stat("Merges performed", "$mergesRun")
        }
        return
    }

    SectionTitle("Merge dry run — nothing has been changed yet")

    summary?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }

    if (plan.isEmpty()) {
        Text(
            "No two canonical tracks share an ISRC. Nothing would merge.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (mergesRun > 0) Stat("Merges performed so far", "$mergesRun")
        return
    }

    Text(
        "${plan.size} recording(s) appear as more than one canonical track. " +
            "Merging would fold ${plan.sumOf { it.losers.size }} track(s) in and " +
            "re-point ${plan.sumOf { it.segmentsToMove }} segment(s).",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 6.dp),
    )

    plan.forEach { group ->
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    group.isrc,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "KEEP  ${group.survivor.title ?: "?"} — ${group.survivor.album ?: "?"} " +
                        "(${group.survivor.segmentCount} segments)",
                    style = MaterialTheme.typography.bodySmall,
                )
                group.losers.forEach { loser ->
                    Text(
                        "FOLD IN  ${loser.title ?: "?"} — ${loser.album ?: "?"} " +
                            "(${loser.segmentCount} segments move)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Button(onClick = onMerge, modifier = Modifier.padding(top = 8.dp)) {
        Text("Run this merge")
    }
}

/**
 * "Import streaming history": the Spotify export, read into the segment table.
 *
 * The picker is the system "Open document" screen, so the app needs no storage
 * permission at all — it is handed a one-off read grant for the single file the
 * user chose, and has no ability to look at anything else.
 *
 * Progress and results are counts only. Never a row of the export, which
 * contains an IP address on every line.
 */
@Composable
private fun HistoryImportCard() {
    val context = LocalContext.current
    val importDao = remember(context) { EvergreenDatabase.get(context).importDao() }
    val state by HistoryImportController.state.collectAsState()
    val imported by importDao.importedSegmentCount().collectAsState(initial = 0)
    val filesImported by importDao.importedFileCount().collectAsState(initial = 0)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { HistoryImportController.start(context, it) } }

    SectionTitle("Streaming history import")
    Stat("Imported plays", "$imported")
    Stat("Files imported", "$filesImported")

    when (val current = state) {
        is HistoryImportController.State.Idle -> Unit

        is HistoryImportController.State.Running -> {
            Text(
                text = "Importing… ${current.progress.rowsRead} rows read, " +
                    "${current.progress.segmentsWritten} written",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Leave this screen open. Six years of history takes a few minutes " +
                    "on the Walkman.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is HistoryImportController.State.Done -> {
            val r = current.result
            Text(
                text = "Import finished",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Stat("Files read", "${r.filesRead}")
            if (r.filesSkippedAlreadyImported > 0) {
                Stat("Files already imported", "${r.filesSkippedAlreadyImported}")
            }
            Stat("Rows read", "${r.rowsRead}")
            Stat("Plays written", "${r.segmentsWritten}")
            Stat("Not tracks (podcasts etc.)", "${r.rowsWithoutTrackUri}")
            if (r.rowsWithoutTimestamp > 0) Stat("Unreadable timestamps", "${r.rowsWithoutTimestamp}")
            if (r.rowsAlreadyImported > 0) Stat("Already imported", "${r.rowsAlreadyImported}")
            if (r.rowsCoveredByCapture > 0) Stat("Covered by capture", "${r.rowsCoveredByCapture}")
            TextButton(onClick = { HistoryImportController.dismiss() }) { Text("Dismiss") }
        }

        is HistoryImportController.State.Failed -> {
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { HistoryImportController.dismiss() }) { Text("Dismiss") }
        }
    }

    OutlinedButton(
        onClick = {
            // No MIME filter beyond these: Android reports the export zip as
            // application/zip, application/x-zip-compressed or
            // application/octet-stream depending on the file manager, and a
            // filter that misses would leave the file greyed out with no
            // explanation. The content is sniffed from its first four bytes
            // instead.
            picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream"))
        },
        enabled = state !is HistoryImportController.State.Running,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Import streaming history")
    }
    Text(
        "Pick the Spotify export zip, or a single Streaming_History_Audio_*.json " +
            "from inside it. Running it twice imports nothing twice. Only the seven " +
            "fields the app needs are read; the rest of each row, including your IP " +
            "address, is skipped by name and never stored.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SectionTitle(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
internal fun Stat(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

internal fun formatTimeShort(epochMs: Long): String = formatTime(epochMs)

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun daysUntil(epochMs: Long): Long =
    TimeUnit.MILLISECONDS.toDays((epochMs - System.currentTimeMillis()).coerceAtLeast(0))
