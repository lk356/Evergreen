package com.example.evergreen.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.TrackMonthRow
import com.example.evergreen.scoring.ContributionNote
import com.example.evergreen.scoring.ScoreBreakdown
import com.example.evergreen.scoring.ScorePoint
import com.example.evergreen.scoring.ScoreService
import java.util.Date

/**
 * One track, with the whole working shown.
 *
 * Not "this song scores 4.2" but "this song scores 4.2 because of a like eleven
 * months ago worth 0.3 today, and nine plays, two of which were skips". Every
 * number here comes from the same [ScoreBreakdown] the scorer produces rather
 * than being recalculated for display, so the view and the score cannot drift
 * apart and start telling different stories.
 *
 * A screen rather than the dialog this used to be. A track with six years of
 * history has hundreds of contributions, and a dialog on a 339 x 602 dp screen
 * put that list in a box inside a box with about 260 dp to show it in.
 */
@Composable
fun TrackDetailScreen(trackId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember(context) { EvergreenDatabase.get(context) }
    val scoringDao = remember(db) { db.scoringDao() }
    val service = remember(db) { ScoreService(db) }

    val ranked by scoringDao.rankedTracks().collectAsState(initial = emptyList())
    val row = ranked.firstOrNull { it.trackId == trackId }

    var breakdown by remember(trackId) { mutableStateOf<ScoreBreakdown?>(null) }
    var months by remember(trackId) { mutableStateOf<List<TrackMonthRow>>(emptyList()) }
    var history by remember(trackId) { mutableStateOf<List<ScorePoint>>(emptyList()) }

    LaunchedEffect(trackId) {
        // All three off the main thread: ScoreService moves its own work to
        // Dispatchers.Default, and the month read is a small indexed query.
        // The breakdown comes first because it is what the top of the screen
        // needs; the line is sixty scorings of the same track and can arrive a
        // moment later.
        breakdown = service.breakdown(trackId)
        months = scoringDao.trackMonthHistory(trackId)
        history = service.scoreHistory(trackId)
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = row?.title ?: "(unknown title)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = row?.artist ?: "(unknown artist)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val current = breakdown
            if (current == null) {
                Text(
                    "Working it out…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                SectionTitle("Where the score comes from")
                Stat("Score", format(current.score))
                row?.let { Stat("Rank", "${it.rank}") }
                row?.let {
                    Stat("In the Evergreen playlist", if (it.inPlaylist) "yes" else "no")
                }
                Stat("Plays that counted", "${current.scoredListenCount}")
                Stat("Total heard", formatDuration(current.totalListenedMs))
                Stat("Likes", "${current.likes.count { it.note == ContributionNote.LIKE }}")
            }
        }

        if (history.size >= 2) {
            item {
                SectionTitle("Score over the last six months")
                Text(
                    text = "Recomputed from the stored listening, not recorded as it went. " +
                        "The score is a pure function of what was played, what was liked " +
                        "and the settings, so any past date can be asked for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScoreChart(history)
            }
        }

        if (months.isNotEmpty()) {
            item { MonthHistory(months) }
        }

        breakdown?.let { current ->
            item {
                SectionTitle("Every event, oldest first")
                Text(
                    "'Raw' is what the event was worth when it happened; the bold number " +
                        "is what it is worth today, after decay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
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

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * This track's rank in every month that has a snapshot, oldest on the left.
 *
 * Read out of `snapshot_entries` rather than recomputed. A snapshot is the
 * record of what was actually true that month, under the settings then in
 * force; recomputing the rank for August under today's parameters would answer
 * a different question and quietly contradict the snapshot screen.
 *
 * A horizontal row rather than a chart: twelve months a year, sixty after five
 * years, and what the eye wants from it is "was it in, and roughly where" —
 * which a row of numbers answers directly at a fraction of the height on a
 * 602 dp screen.
 */
@Composable
private fun MonthHistory(months: List<TrackMonthRow>) {
    SectionTitle("Month by month")
    Text(
        text = "Its rank in each monthly snapshot. Bold means it was in the playlist that " +
            "month.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        months.forEach { month ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(52.dp),
            ) {
                Text(
                    text = "#${month.rank}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (month.inPlaylist) FontWeight.Bold else FontWeight.Normal,
                    color = if (month.inPlaylist) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    // "2026-08" trimmed to "08 '26": the year repeats down a
                    // row of months and the month is what distinguishes them.
                    text = month.monthKey.let { key ->
                        "${key.substringAfter('-')} '${key.take(4).takeLast(2)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
}
