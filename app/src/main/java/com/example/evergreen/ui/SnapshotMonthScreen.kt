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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.SnapshotEntity
import com.example.evergreen.data.SnapshotEntryEntity
import com.example.evergreen.scoring.ScoringParameters
import com.example.evergreen.scoring.SnapshotExport
import com.example.evergreen.scoring.Snapshots
import com.example.evergreen.spotify.SpotifyApi
import com.example.evergreen.spotify.SpotifyAuth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One month, read back.
 *
 * It leads with the **diff against the previous month** rather than the list,
 * because the list is three hundred and eighty rows and the diff is the part
 * that says something. Consecutive snapshots subtract: what is new, what is
 * gone, and what sat at the top are all set operations over rows already
 * stored, so none of this needed a new table.
 *
 * The list below defaults to the hundred that were in the playlist, with the
 * whole ranking behind a chip. The whole ranking is stored — that is what makes
 * "what was still alive in August" answerable — but it is not what someone
 * opening a month wants to scroll through first on a 339 dp screen.
 */
@Composable
fun SnapshotMonthScreen(
    snapshotId: Long,
    onOpenTrack: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val db = remember(context) { EvergreenDatabase.get(context) }
    val scoringDao = remember(db) { db.scoringDao() }

    var snapshot by remember(snapshotId) { mutableStateOf<SnapshotEntity?>(null) }
    var entries by remember(snapshotId) { mutableStateOf<List<SnapshotEntryEntity>>(emptyList()) }
    var previous by remember(snapshotId) { mutableStateOf<SnapshotEntity?>(null) }
    var previousEntries by remember(snapshotId) {
        mutableStateOf<List<SnapshotEntryEntity>>(emptyList())
    }
    var playlistOnly by rememberSaveable(snapshotId) { mutableStateOf(true) }
    var exporting by remember(snapshotId) { mutableStateOf(false) }
    var exportStatus by remember(snapshotId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(snapshotId) {
        val loaded = scoringDao.snapshotById(snapshotId)
        snapshot = loaded
        entries = scoringDao.snapshotEntries(snapshotId)
        val before = loaded?.let { scoringDao.snapshotBefore(it.monthKey) }
        previous = before
        previousEntries = before?.let { scoringDao.snapshotEntries(it.id) }.orEmpty()
    }

    val diff = remember(entries, previousEntries) {
        monthDiff(current = entries, previous = previousEntries)
    }
    val shown = remember(entries, playlistOnly) {
        if (playlistOnly) entries.filter { it.inPlaylist } else entries
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            val current = snapshot
            if (current == null) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall)
                return@item
            }

            Text(
                text = current.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Taken ${TAKEN_FORMAT.format(Date(current.takenAt))}  ·  " +
                    "${current.playlistSize} in the playlist, ${current.trackCount} ranked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The settings the month was scored under. Without them the scores
            // below are a column of numbers on a scale nobody can reconstruct,
            // which is why every snapshot stores its own copy.
            val params = remember(current.parametersJson) {
                runCatching { ScoringParameters.fromJson(current.parametersJson) }.getOrNull()
            }
            params?.let {
                Text(
                    text = "Scored with ${format(it.halfLifeDays)}d half-life, " +
                        "like ${format(it.likeWeight)}, join at rank ${it.playlistJoinRank}" +
                        if (it != ScoringParameters()) "  ·  not today's settings" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            ExportRow(
                snapshot = current,
                busy = exporting,
                status = exportStatus,
                onExport = {
                    scope.launch {
                        exporting = true
                        exportStatus = null
                        val api = SpotifyApi(SpotifyAuth(context))
                        exportStatus = when (val result = Snapshots(db).export(current.id, api)) {
                            is SnapshotExport.Created ->
                                "Exported \"${Snapshots.EXPORT_PREFIX}${current.label}\" to Spotify."
                            // A refusal is not a failure, and saying "check the
                            // connection" about a mirror would send the user to
                            // fix something that is not broken.
                            is SnapshotExport.Refused -> result.reason
                            SnapshotExport.Failed ->
                                "Export failed. Check the Spotify connection in Settings."
                        }
                        // Re-read so the "exported" marker appears without
                        // leaving the screen.
                        snapshot = scoringDao.snapshotById(snapshotId)
                        exporting = false
                    }
                },
            )

            MonthDiffSection(diff = diff, previous = previous, onOpenTrack = onOpenTrack)

            SectionTitle("The ranking")
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = playlistOnly,
                    onClick = { playlistOnly = true },
                    label = { Text("In the playlist (${entries.count { it.inPlaylist }})", fontSize = 12.sp) },
                )
                FilterChip(
                    selected = !playlistOnly,
                    onClick = { playlistOnly = false },
                    label = { Text("Everything (${entries.size})", fontSize = 12.sp) },
                )
            }
        }

        items(shown, key = { it.id }) { entry ->
            EntryRow(entry = entry, onClick = { onOpenTrack(entry.trackId) })
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * Turning a month back into a real playlist.
 *
 * A **new** playlist every time, never touched again by the app. That is what
 * makes exporting safe: it cannot overwrite anything, and the resulting
 * playlist can be renamed, edited or deleted freely without the app disagreeing.
 * Exporting twice makes two playlists, which is why the button says so.
 */
@Composable
private fun ExportRow(
    snapshot: SnapshotEntity,
    busy: Boolean,
    status: String?,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onExport, enabled = !busy) {
            Text(
                when {
                    busy -> "Exporting…"
                    snapshot.exportedAt != null -> "Export again"
                    else -> "Export to Spotify"
                },
            )
        }
    }
    Text(
        text = status ?: "Creates a new private playlist, \"${Snapshots.EXPORT_PREFIX}" +
            "${snapshot.label}\", holding the ${snapshot.playlistSize} songs that were in " +
            "Evergreen that month. The app never changes it again, so you can rename or " +
            "delete it freely — and exporting twice makes two playlists.",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (status != null) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What changed between two consecutive snapshots. */
internal data class MonthDiff(
    val joined: List<SnapshotEntryEntity>,
    val left: List<SnapshotEntryEntity>,
    val top: List<SnapshotEntryEntity>,
)

/**
 * The set difference between two months' playlists.
 *
 * Compared on `trackId` rather than on the service URI, because a track can be
 * re-pressed or merged and its URI change without it being a different song.
 * The entries carry their own titles, copied in at snapshot time, so a track
 * that has since vanished from Spotify still reads correctly here.
 */
internal fun monthDiff(
    current: List<SnapshotEntryEntity>,
    previous: List<SnapshotEntryEntity>,
    topCount: Int = TOP_COUNT,
): MonthDiff {
    val nowIn = current.filter { it.inPlaylist }
    val thenIn = previous.filter { it.inPlaylist }
    val nowIds = nowIn.mapTo(mutableSetOf()) { it.trackId }
    val thenIds = thenIn.mapTo(mutableSetOf()) { it.trackId }
    return MonthDiff(
        joined = nowIn.filter { it.trackId !in thenIds }.sortedBy { it.rank },
        left = thenIn.filter { it.trackId !in nowIds }.sortedBy { it.rank },
        top = nowIn.sortedBy { it.rank }.take(topCount),
    )
}

@Composable
private fun MonthDiffSection(
    diff: MonthDiff,
    previous: SnapshotEntity?,
    onOpenTrack: (Long) -> Unit,
) {
    SectionTitle("What the top of the month looked like")
    diff.top.forEach { entry ->
        EntryRow(entry = entry, onClick = { onOpenTrack(entry.trackId) })
    }

    if (previous == null) {
        Text(
            text = "This is the first snapshot, so there is nothing to compare it with. " +
                "From next month there will be.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }

    SectionTitle("Against ${previous.label}")
    if (diff.joined.isEmpty() && diff.left.isEmpty()) {
        Text(
            text = "Exactly the same hundred songs. The scores moved underneath, but " +
                "nothing crossed either cutoff.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (diff.joined.isNotEmpty()) {
        Text(
            text = "New this month (${diff.joined.size})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        diff.joined.forEach { entry ->
            EntryRow(entry = entry, onClick = { onOpenTrack(entry.trackId) })
        }
    }
    if (diff.left.isNotEmpty()) {
        Text(
            text = "Gone since ${previous.label} (${diff.left.size})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
        diff.left.forEach { entry ->
            // Ranked by where it stood *last* month; it may not be ranked at
            // all this month, which is exactly what leaving can mean.
            EntryRow(entry = entry, onClick = { onOpenTrack(entry.trackId) })
        }
    }
}

@Composable
private fun EntryRow(entry: SnapshotEntryEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${entry.rank}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title ?: "(unknown title)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.inPlaylist) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = entry.artist ?: "(unknown artist)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = format(entry.score),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    HorizontalDivider()
}

private const val TOP_COUNT = 5

private val TAKEN_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
