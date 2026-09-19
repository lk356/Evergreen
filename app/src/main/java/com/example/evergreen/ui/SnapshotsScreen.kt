package com.example.evergreen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.example.evergreen.data.SnapshotSummary
import com.example.evergreen.scoring.SnapshotResult
import com.example.evergreen.scoring.Snapshots
import kotlinx.coroutines.launch

/**
 * The months, grouped by year.
 *
 * **No search box.** Twelve snapshots a year and sixty after five years is a
 * list, and a list grouped by year beats a text field and a keyboard on a
 * 339 dp screen — you can see the whole of 2026 at once, which no search
 * result can do. Searching *across* snapshots for an artist is a different
 * feature and means little until there are enough months for it to return
 * anything; it is deliberately not here.
 *
 * Exporting lives on the month itself rather than on this list. The natural
 * order is to open a month, look at what was in it, and then decide — and the
 * row here is already carrying a label and two counts.
 */
@Composable
fun SnapshotsScreen(onOpenSnapshot: (Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember(context) { EvergreenDatabase.get(context) }
    val scope = rememberCoroutineScope()

    val snapshots by db.scoringDao().snapshots().collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // snapshots() already orders by monthKey descending, and the key sorts
    // lexicographically because it is `yyyy-MM`. Grouping preserves that, so
    // both the years and the months inside them come out newest first.
    val byYear = remember(snapshots) { snapshots.groupBy { it.monthKey.take(4) } }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            if (snapshots.isEmpty()) {
                Text(
                    text = "No months yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "A snapshot is taken automatically on the last day of each " +
                        "month. It stores the whole ranking — not just the hundred in the " +
                        "playlist — with the settings that produced it, so it still reads " +
                        "correctly after the scoring is retuned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "${snapshots.size} month${if (snapshots.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Each one holds the whole ranking as it stood, and the settings " +
                        "that produced it. Open one to see what changed since the month " +
                        "before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        byYear.forEach { (year, months) ->
            item(key = "year-$year") {
                SectionTitle(year)
            }
            items(months, key = { it.id }) { month ->
                MonthRow(month = month, onClick = { onOpenSnapshot(month.id) })
            }
        }

        item {
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
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(if (busy) "Working…" else "Take a snapshot now")
            }
            Text(
                text = "One snapshot per month, so taking one by hand on a day that already " +
                    "has one does nothing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MonthRow(month: SnapshotSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // "2026 August" with the year already given by the heading
                // above it.
                text = month.label.substringAfter(' '),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${month.playlistSize} in the playlist  ·  ${month.trackCount} ranked" +
                    (month.exportedAt?.let { "  ·  exported" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}
