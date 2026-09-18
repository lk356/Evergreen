package com.example.evergreen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.RankedTrackRow
import com.example.evergreen.scoring.ContributionKind
import com.example.evergreen.scoring.ContributionNote
import com.example.evergreen.scoring.ScoreBreakdown
import com.example.evergreen.scoring.ScoreService
import com.example.evergreen.scoring.ScoringParameters
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The explain-the-score view (decisions 8).
 *
 * Seven parameters cannot be tuned without seeing the working, and this tab is
 * built **before** anything can be written to Spotify, so the scoring can be
 * looked at over six years of real listening and adjusted before the app is
 * allowed to change a playlist.
 *
 * Everything shown here comes from the same [ScoreBreakdown] the scorer
 * produces, rather than being recalculated for display. The view and the score
 * therefore cannot drift apart and start telling different stories.
 */
@Composable
fun ScoresTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember(context) { EvergreenDatabase.get(context) }
    val scoringDao = remember(db) { db.scoringDao() }
    val service = remember(db) { ScoreService(db) }
    val scope = rememberCoroutineScope()

    val ranked by scoringDao.rankedTracks().collectAsState(initial = emptyList())
    val parameters by scoringDao.scoringParametersFlow()
        .collectAsState(initial = ScoringParameters())

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<RankedTrackRow?>(null) }
    var editingParameters by remember { mutableStateOf(false) }
    var parametersExpanded by rememberSaveable { mutableStateOf(false) }

    // Fill the cache on first open so the tab is never inexplicably empty.
    LaunchedEffect(Unit) {
        if (scoringDao.cachedScoreCount() == 0) {
            busy = true
            val ranking = service.ranking()
            status = "Scored ${ranking.tracks.size} liked tracks."
            busy = false
        }
    }

    val alive = ranked.count { it.score > parameters.recordFloor }

    // One LazyColumn for the whole tab, header included, rather than a fixed
    // header above a scrolling list.
    //
    // The NW-A307's screen is 339 x 602 dp, and the header -- two buttons, nine
    // parameter rows and three counts -- came to about 330 dp of it. The list
    // got whatever was left, which was roughly one row, and none of the header
    // could be scrolled away because it sat outside the scrolling area. Making
    // every part of the tab an item means the list uses the whole screen as
    // soon as the header scrolls off.
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = null
                            val started = System.currentTimeMillis()
                            val ranking = service.ranking(force = true)
                            val took = System.currentTimeMillis() - started
                            status = "Scored ${ranking.tracks.size} tracks in ${took} ms."
                            busy = false
                        }
                    },
                ) {
                    Text(if (busy) "Scoring…" else "Recompute")
                }
                OutlinedButton(onClick = { editingParameters = true }, enabled = !busy) {
                    Text("Parameters")
                }
            }
            status?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            ParameterSummary(
                parameters = parameters,
                expanded = parametersExpanded,
                onToggle = { parametersExpanded = !parametersExpanded },
            )

            SectionTitle("Ranking")
            Stat("Liked tracks scored", "${ranked.size}")
            Stat("Still alive (above ${format(parameters.recordFloor)})", "$alive")
            Stat("In the Evergreen playlist", "${ranked.count { it.inPlaylist }}")

            if (ranked.isEmpty()) {
                Text(
                    "No scores yet. Sync your likes on the Spotify tab, then tap Recompute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        items(ranked, key = { it.trackId }) { row ->
            // The two cutoffs drawn as lines, so the distance to each is
            // visible rather than having to be worked out from the numbers.
            if (row.rank == parameters.playlistJoinRank + 1) {
                CutoffLine("playlist cutoff — rank ${parameters.playlistJoinRank}")
            }
            if (row.rank == parameters.playlistLeaveRank + 1) {
                CutoffLine("drop-out cutoff — rank ${parameters.playlistLeaveRank}")
            }
            RankedRow(row, parameters) { selected = row }
        }

        // The last row would otherwise sit under the navigation bar.
        item { Spacer(modifier = Modifier.padding(bottom = 24.dp)) }
    }

    selected?.let { row ->
        BreakdownDialog(row = row, service = service, onDismiss = { selected = null })
    }

    if (editingParameters) {
        ParameterEditor(
            current = parameters,
            onDismiss = { editingParameters = false },
            onSave = { updated ->
                scope.launch {
                    scoringDao.putScoringParameters(updated, System.currentTimeMillis())
                    editingParameters = false
                    busy = true
                    // Changing a parameter invalidates the cache by design, so
                    // the recompute happens here rather than waiting for
                    // something else to notice.
                    val ranking = service.ranking(force = true)
                    status = "Parameters saved; rescored ${ranking.tracks.size} tracks."
                    busy = false
                }
            },
        )
    }
}

@Composable
private fun CutoffLine(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RankedRow(row: RankedTrackRow, parameters: ScoringParameters, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${row.rank}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title ?: "(unknown title)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.inPlaylist) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = buildString {
                    append(row.artist ?: "(unknown artist)")
                    if (row.inPlaylist) append("  ·  in playlist")
                    if (row.score <= parameters.recordFloor) append("  ·  below the floor")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = format(row.score),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
    HorizontalDivider()
}

/**
 * Every event behind one track's score, in date order.
 *
 * This is the whole point of the tab: not "this song scores 4.2" but "this
 * song scores 4.2 because of a like eleven months ago worth 0.3 today, and
 * nine plays, two of which were skips".
 */
@Composable
private fun BreakdownDialog(
    row: RankedTrackRow,
    service: ScoreService,
    onDismiss: () -> Unit,
) {
    var breakdown by remember(row.trackId) { mutableStateOf<ScoreBreakdown?>(null) }
    var uri by remember(row.trackId) { mutableStateOf<String?>(null) }
    LaunchedEffect(row.trackId) {
        breakdown = service.breakdown(row.trackId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Column {
                Text(row.title ?: "(unknown title)", style = MaterialTheme.typography.titleMedium)
                Text(
                    row.artist ?: "(unknown artist)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            val current = breakdown
            if (current == null) {
                Text("Working it out…")
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Stat("Score", format(current.score))
                    Stat("Rank", "${row.rank}")
                    Stat("Plays that counted", "${current.scoredListenCount}")
                    Stat("Total heard", formatDuration(current.totalListenedMs))
                    Stat("Likes", "${current.likes.count { it.note == ContributionNote.LIKE }}")

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Every event, oldest first. 'Raw' is what the event was " +
                            "worth when it happened; 'now' is what it is worth today " +
                            "after decay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Bounded, so the list of events cannot push the totals
                    // above it off a 602 dp screen. A track with six years of
                    // history can have hundreds of contributions.
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .padding(top = 6.dp),
                    ) {
                        items(current.contributions) { contribution ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = DATE_FORMAT.format(Date(contribution.atMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                    )
                                    Text(
                                        text = describe(contribution.kind, contribution.note) +
                                            (contribution.fractionHeard
                                                ?.let { "  ${(it * 100).toInt()}% heard" }
                                                ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = noteColour(contribution.note),
                                    )
                                }
                                Text(
                                    text = "raw ${format(contribution.rawPlayEquivalents)}\n" +
                                        "×${format(contribution.decayWeight)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = format(contribution.decayedValue),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun noteColour(note: ContributionNote) = when (note) {
    ContributionNote.SCORED, ContributionNote.LIKE -> MaterialTheme.colorScheme.onSurface
    ContributionNote.DAILY_CAP -> MaterialTheme.colorScheme.tertiary
    ContributionNote.DJ_SUPPRESSED -> MaterialTheme.colorScheme.primary
    ContributionNote.COMMENTARY,
    ContributionNote.UNKNOWN_DURATION,
    ContributionNote.UNLIKE_IGNORED,
    -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun describe(kind: ContributionKind, note: ContributionNote): String = when (note) {
    ContributionNote.LIKE -> "liked"
    ContributionNote.UNLIKE_IGNORED -> "unliked (does not affect the score)"
    ContributionNote.COMMENTARY -> "DJ commentary — not a song"
    ContributionNote.UNKNOWN_DURATION -> "played, but no track length known"
    ContributionNote.DJ_SUPPRESSED -> "skipped during a DJ session — not counted against it"
    ContributionNote.DAILY_CAP -> "played, trimmed by the daily cap"
    ContributionNote.SCORED -> if (kind == ContributionKind.LISTEN) "played" else "like"
}

/**
 * The settings in force: one line, expanding to all nine.
 *
 * Collapsed by default because this is reference material and the ranking is
 * what the tab is for. Nine rows is most of a 602 dp screen, and on the Walkman
 * that meant scrolling past the parameters every time to reach the list.
 */
@Composable
private fun ParameterSummary(
    parameters: ScoringParameters,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SectionTitle("Parameters in force")
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${format(parameters.halfLifeDays)}d half-life · " +
                "${format(parameters.lowerBreakpoint)}–${format(parameters.upperBreakpoint)} · " +
                "like ${format(parameters.likeWeight)} · cap ${format(parameters.dailyCapPlays)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (expanded) "less" else "more",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (expanded) {
        Stat("Half-life", "${format(parameters.halfLifeDays)} days")
        Stat(
            "Fraction heard breakpoints",
            "${format(parameters.lowerBreakpoint)} / ${format(parameters.upperBreakpoint)}",
        )
        Stat("Rejection weight", format(parameters.negativeWeight))
        Stat("A like is worth", "${format(parameters.likeWeight)} plays")
        Stat("Replay jump threshold", "${parameters.replayJumpThresholdMs / 1000}s")
        Stat("Daily cap per track", "${format(parameters.dailyCapPlays)} plays")
        Stat("Record floor", format(parameters.recordFloor))
        Stat(
            "Playlist ranks",
            "join ${parameters.playlistJoinRank}, leave ${parameters.playlistLeaveRank}",
        )
    }
}

/**
 * The danger zone: the parameters as raw JSON.
 *
 * Phase 4 owns the real settings screen with a slider per value. Until then
 * this is deliberately awkward, because it is the one control here that
 * changes what the app will write to Spotify.
 */
@Composable
private fun ParameterEditor(
    current: ScoringParameters,
    onDismiss: () -> Unit,
    onSave: (ScoringParameters) -> Unit,
) {
    var text by remember { mutableStateOf(current.toJson()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scoring parameters") },
        text = {
            Column {
                Text(
                    "Editing these rescores everything. It changes no stored " +
                        "listening and runs no migration — scores are computed from " +
                        "the segments every time, so any value can be changed back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    isError = error != null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { text = ScoringParameters().toJson(); error = null }) {
                    Text("Reset to the calibrated defaults")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Validated here rather than on save, so a typo is reported
                // where it can be corrected instead of silently falling back to
                // the defaults.
                runCatching { ScoringParameters.fromJson(text) }
                    .onSuccess(onSave)
                    .onFailure { error = it.message ?: "That is not valid JSON." }
            }) {
                Text("Save and rescore")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun format(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
