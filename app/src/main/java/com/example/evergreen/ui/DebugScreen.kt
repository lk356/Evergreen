package com.example.evergreen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evergreen.capture.CaptureEvent
import com.example.evergreen.capture.CaptureLog
import com.example.evergreen.capture.CaptureNotificationListenerService
import com.example.evergreen.capture.EventSource
import com.example.evergreen.data.EndReason
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.MergedSegment
import com.example.evergreen.data.SegmentGrouping
import com.example.evergreen.data.SegmentWithTrack
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A replay threshold offered on the debug screen.
 *
 * Nothing here is stored. Capture cuts a segment at every real backwards jump;
 * these options fold those rows back together under different views of how big
 * a jump has to be before it counts as replaying the track. Switching between
 * them re-reads the same history, which is the point: the Phase 3 decision can
 * be made by looking at real listening rather than guessed at in advance.
 */
private data class ThresholdOption(val label: String, val thresholdMs: Long?)

private val THRESHOLD_OPTIONS = listOf(
    ThresholdOption("As recorded", null),
    ThresholdOption("3s", 3_000),
    ThresholdOption("10s", 10_000),
    ThresholdOption("30s", 30_000),
)

@Composable
fun DebugScreen(
    accessGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onConnectSpotify: () -> Unit,
    /** Bumped when the login flow returns, so the Spotify tab re-reads its state. */
    connectionEpoch: Int,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        AccessCard(
            accessGranted = accessGranted,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenAppInfo = onOpenAppInfo,
        )

        PrimaryTabRow(selectedTabIndex = selectedTab, modifier = Modifier.padding(top = 12.dp)) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Segments") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Live log") },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Spotify") },
            )
        }

        when (selectedTab) {
            0 -> SegmentsTab(accessGranted)
            1 -> LiveLogTab(accessGranted)
            else -> SpotifyTab(
                onConnect = onConnectSpotify,
                connectionEpoch = connectionEpoch,
            )
        }
    }
}

// --- Stored segments ---------------------------------------------------------

@Composable
private fun SegmentsTab(accessGranted: Boolean) {
    val context = LocalContext.current
    val dao = remember(context) { EvergreenDatabase.get(context).dao() }
    val scope = rememberCoroutineScope()

    val rows by dao.recentSegments(limit = 200).collectAsState(initial = emptyList())
    val trackCount by dao.trackCount().collectAsState(initial = 0)
    val segmentCount by dao.segmentCount().collectAsState(initial = 0)
    val mismatchCount by dao.mismatchedTrackIdCount().collectAsState(initial = 0)

    var thresholdIndex by rememberSaveable { mutableIntStateOf(0) }
    var confirmingWipe by rememberSaveable { mutableStateOf(false) }
    val option = THRESHOLD_OPTIONS[thresholdIndex]

    // Merging is derived on every read, never stored.
    val merged = remember(rows, option.thresholdMs) {
        SegmentGrouping.merge(rows.map { it.segment }, option.thresholdMs)
    }
    val titles = remember(rows) { rows.associate { it.segment.id to it } }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Count a jump back as a replay when it is at least:",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            THRESHOLD_OPTIONS.forEachIndexed { index, item ->
                FilterChip(
                    selected = thresholdIndex == index,
                    onClick = { thresholdIndex = index },
                    label = { Text(item.label, fontSize = 12.sp) },
                )
            }
        }
        Text(
            text = "Derived on the fly. The stored rows never change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // weight() on the text, so the button keeps its natural width
            // instead of being squeezed to a sliver on the NW-A307's 720px
            // screen.
            Text(
                text = "$segmentCount seg · $trackCount track · ${merged.size} listen",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { confirmingWipe = true },
                enabled = segmentCount > 0,
            ) {
                Text("Wipe")
            }
        }

        // This is real listening history, it is not backed up anywhere by
        // design, and there is no undo — so deleting it takes a deliberate
        // second action rather than one stray tap.
        if (confirmingWipe) {
            AlertDialog(
                onDismissRequest = { confirmingWipe = false },
                title = { Text("Delete all listening history?") },
                text = {
                    Text(
                        "This permanently deletes $segmentCount segment(s) across " +
                            "$trackCount track(s).\n\nIt cannot be undone. Cloud backup " +
                            "and device transfer are deliberately switched off for this " +
                            "app, so there is no copy to restore from.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmingWipe = false
                            scope.launch { dao.deleteEverything() }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete everything")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingWipe = false }) { Text("Cancel") }
                },
            )
        }

        // Diagnostic, normally invisible. Counts segments whose canonical track
        // disagrees with the canonical track of the service row they point at —
        // possible only for rows written before the lookup order was fixed.
        // Reported, never repaired: choosing which of the two tracks is the real
        // one is a merge question for Phase 2.
        if (mismatchCount > 0) {
            Text(
                text = "⚠ $mismatchCount segment(s) disagree with their service track. " +
                    "Recorded before the identity fix; left alone for Phase 2 to merge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        HorizontalDivider()

        if (merged.isEmpty()) {
            Text(
                text = if (accessGranted) {
                    "Nothing captured yet. Play something in Spotify — a segment " +
                        "is written when a listen ends."
                } else {
                    "Grant notification access to start capturing."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(merged, key = { it.rows.first().id }) { listen ->
                    SegmentRow(listen, titles)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SegmentRow(listen: MergedSegment, titles: Map<Long, SegmentWithTrack>) {
    val first = listen.rows.first()
    val withTrack = titles[first.id]
    val reached = listen.positionReachedFraction
    val heard = listen.listenedFraction

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = TIME_FORMAT.format(Date(listen.startedAtMs)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = listen.endReason.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = endReasonColor(listen.endReason),
            )
            if (listen.isMerged) {
                Text(
                    text = "${listen.rows.size} rows merged",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        Text(
            text = buildString {
                append(withTrack?.title ?: "(unknown title)")
                append(" — ")
                append(withTrack?.artist ?: "(unknown artist)")
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )

        // Heard and reached are shown side by side on purpose. Seeking to the
        // end of a track makes "reached" ~100% while "heard" stays tiny, which
        // is exactly the false completion Phase 0 used to record.
        Text(
            text = buildString {
                append("heard ").append(formatMs(listen.listenedMs))
                heard?.let { append(" (").append((it * 100).toInt()).append("%)") }
                // An imported row has no position at all, so it says so
                // rather than showing a zero it never observed.
                append("   reached ")
                append(listen.endPositionMs?.let { formatMs(it) } ?: "—")
                reached?.let { append(" (").append((it * 100).toInt()).append("%)") }
                append(" of ").append(formatMs(listen.trackDurationMs))
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val notes = buildList {
            val seeks = listen.forwardSeekCount ?: 0
            if (seeks > 0) {
                add(
                    "$seeks forward seek(s), " +
                        "${formatMs(listen.seekedForwardMs ?: 0)} skipped",
                )
            }
            first.endJumpFromMs?.let { from ->
                val to = first.endJumpToMs ?: 0
                add("jumped back ${formatMs(from)} -> ${formatMs(to)}")
            }
            if ((first.lastSampleAgeMs ?: 0) > 30_000) {
                add("last reading was ${formatMs(first.lastSampleAgeMs ?: 0)} old")
            }
            if (listen.isImported) {
                // Worth saying on the row itself: an imported listen has no
                // position, no seeks and no jumps, so its blanks are the
                // export's silence rather than a capture bug.
                add("imported" + first.sourceEndReason?.let { " ($it)" }.orEmpty())
            }
        }
        if (notes.isNotEmpty()) {
            Text(
                text = notes.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // A derived hint, not a stored conclusion: a jump back from very near
        // the end of the track is what a repeat-one loop looks like.
        if (reached != null && reached >= LOOP_HINT_FRACTION &&
            listen.endReason == EndReason.POSITION_JUMP_BACK
        ) {
            AssistChip(
                onClick = {},
                label = { Text("looks like a loop", fontSize = 11.sp) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// --- Live log ----------------------------------------------------------------

@Composable
private fun LiveLogTab(accessGranted: Boolean) {
    val events by CaptureLog.events.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${events.size} event(s), newest first — not stored",
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedButton(onClick = { CaptureLog.clear() }, enabled = events.isNotEmpty()) {
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
private fun EventRow(event: CaptureEvent) {
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

// --- Shared ------------------------------------------------------------------

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

@Composable
private fun endReasonColor(reason: EndReason): Color = when (reason) {
    EndReason.POSITION_JUMP_BACK -> MaterialTheme.colorScheme.primary
    EndReason.TRACK_CHANGE -> MaterialTheme.colorScheme.onSurfaceVariant
    EndReason.SESSION_END -> MaterialTheme.colorScheme.tertiary
    // Not an error, just not observed by this app: the row was imported.
    EndReason.IMPORTED -> MaterialTheme.colorScheme.secondary
    EndReason.STALE, EndReason.UNKNOWN -> MaterialTheme.colorScheme.error
}

@Composable
private fun sourceColor(source: EventSource): Color = when (source) {
    EventSource.SEGMENT -> MaterialTheme.colorScheme.primary
    EventSource.BROADCAST -> MaterialTheme.colorScheme.tertiary
    EventSource.SYSTEM -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private const val LOOP_HINT_FRACTION = 0.97

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
