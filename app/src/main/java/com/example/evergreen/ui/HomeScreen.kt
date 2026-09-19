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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.MembershipChangeRow
import com.example.evergreen.data.RankedTrackRow
import com.example.evergreen.data.SyncKeys
import com.example.evergreen.scoring.ScoringParameters
import com.example.evergreen.scoring.Snapshots
import com.example.evergreen.spotify.SpotifyAuth
import com.example.evergreen.spotify.SpotifyConnectionState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The front door.
 *
 * Two things, in this order: whether the app is working, and what moved.
 *
 * It is deliberately **not** a copy of the Evergreen playlist. This app never
 * plays music — Spotify does — so a browsable playlist here would be a worse
 * version of something one tap away, on a 339 dp screen, with no play button.
 * What only this app knows is whether the pipeline ran and what it changed, and
 * that is all that is here.
 *
 * The status line is quiet when everything is fine and loud only when something
 * is broken, because a warning that is always on screen stops being a warning.
 * The changes list below it does double duty: today it is how the scoring gets
 * checked day to day, and later it is the only genuinely interesting thing the
 * app has to say.
 */
@Composable
fun HomeScreen(
    accessGranted: Boolean,
    connectionEpoch: Int,
    onOpenScores: () -> Unit,
    onOpenTrack: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onFixNotificationAccess: () -> Unit,
    onReconnectSpotify: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    val context = LocalContext.current
    val db = remember(context) { EvergreenDatabase.get(context) }
    val dao = remember(db) { db.dao() }
    val scoringDao = remember(db) { db.scoringDao() }

    val lastCaptured by dao.lastCapturedAtMs().collectAsState(initial = null)
    val lastLikeSync by scoringDao.syncValueFlow(SyncKeys.LIKES_LAST_SYNCED_AT)
        .collectAsState(initial = null)
    val lastWritten by scoringDao.syncValueFlow(SyncKeys.EVERGREEN_LAST_WRITTEN_AT)
        .collectAsState(initial = null)
    val writeConfirmed by scoringDao.syncValueFlow(SyncKeys.EVERGREEN_WRITE_CONFIRMED_AT)
        .collectAsState(initial = null)
    val isWriterRow by scoringDao.syncValueFlow(SyncKeys.EVERGREEN_WRITER_DEVICE)
        .collectAsState(initial = null)
    val backoffUntil by scoringDao.syncValueFlow(SyncKeys.API_BACKOFF_UNTIL)
        .collectAsState(initial = null)
    val snapshots by scoringDao.snapshots().collectAsState(initial = emptyList())
    val memberCount by scoringDao.currentMemberCountFlow().collectAsState(initial = 0)
    val ranked by scoringDao.rankedTracks().collectAsState(initial = emptyList())
    val parameters by scoringDao.scoringParametersFlow()
        .collectAsState(initial = ScoringParameters())

    val changes by scoringDao
        .membershipChangesSince(nowMs - CHANGES_WINDOW_MS, CHANGES_LIMIT)
        .collectAsState(initial = emptyList())

    // The connection lives in the Keystore rather than the database, so there
    // is no Flow for it. connectionEpoch is bumped on every resume and after a
    // login, which is what stops this showing a stale "Connected".
    var connection by remember { mutableStateOf<SpotifyConnectionState?>(null) }
    var configured by remember { mutableStateOf(true) }
    LaunchedEffect(connectionEpoch) {
        val auth = SpotifyAuth(context)
        configured = auth.isConfigured
        connection = auth.connectionState()
    }

    val isWriter = isWriterRow?.valueText == "true"
    val problems = remember(
        accessGranted, connection, configured, lastCaptured, lastLikeSync,
        lastWritten, writeConfirmed, isWriter, backoffUntil, snapshots, nowMs,
    ) {
        buildStatus(
            nowMs = nowMs,
            accessGranted = accessGranted,
            configured = configured,
            connection = connection,
            lastCapturedAtMs = lastCaptured,
            lastLikeSyncAtMs = lastLikeSync?.valueMs,
            lastWrittenAtMs = lastWritten?.valueMs,
            writeConfirmed = writeConfirmed?.valueMs != null,
            isWriter = isWriter,
            backoffUntilMs = backoffUntil?.valueMs,
            latestSnapshotMonthKey = snapshots.firstOrNull()?.monthKey,
        )
    }

    val nearTheLine = remember(ranked, parameters) { nearTheLine(ranked, parameters) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            StatusSection(
                problems = problems,
                memberCount = memberCount,
                lastCapturedAtMs = lastCaptured,
                lastWrittenAtMs = lastWritten?.valueMs,
                nowMs = nowMs,
                onFixNotificationAccess = onFixNotificationAccess,
                onReconnectSpotify = onReconnectSpotify,
                onOpenSettings = onOpenSettings,
            )
        }

        item { ChangesHeading(changes = changes, nowMs = nowMs) }

        items(changes, key = { "${it.trackId}-${it.joinedAt}-${it.leftAt}" }) { row ->
            ChangeRow(row = row, nowMs = nowMs, onClick = { onOpenTrack(row.trackId) })
        }

        if (nearTheLine.isNotEmpty()) {
            item {
                SectionTitle("Near the line")
                Text(
                    text = "Where the next change is coming from. A track joins at rank " +
                        "${parameters.playlistJoinRank} and does not leave until it falls " +
                        "below ${parameters.playlistLeaveRank}; the gap is what stops songs " +
                        "bouncing in and out on alternate days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(nearTheLine, key = { "near-${it.row.trackId}" }) { near ->
                NearTheLineRow(near = near, onClick = { onOpenTrack(near.row.trackId) })
            }
        }

        item {
            if (ranked.isNotEmpty()) {
                TextButton(
                    onClick = onOpenScores,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("See the whole ranking (${ranked.size} tracks)")
                }
            }
            // The last row would otherwise sit under the navigation bar.
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// --- The status line ---------------------------------------------------------

/**
 * Something that is wrong, in the words the user needs rather than the app's.
 *
 * [action] is offered only where there is something to press. "Capture has gone
 * quiet" with notification access still granted has no button, because the fix
 * is in Spotify's own settings and pretending otherwise would send the user in
 * a circle.
 */
internal data class StatusProblem(
    val headline: String,
    val detail: String,
    val severity: Severity,
    val actionLabel: String? = null,
    val action: Action? = null,
) {
    enum class Severity { BROKEN, WARNING }

    enum class Action { NOTIFICATION_ACCESS, RECONNECT, SETTINGS }
}

/**
 * Everything that is wrong, worst first, and an empty list when all is well.
 *
 * Pure, and separated from the composable that draws it, so the rules about
 * what counts as broken are testable without a device.
 */
internal fun buildStatus(
    nowMs: Long,
    accessGranted: Boolean,
    configured: Boolean,
    connection: SpotifyConnectionState?,
    lastCapturedAtMs: Long?,
    lastLikeSyncAtMs: Long?,
    lastWrittenAtMs: Long?,
    writeConfirmed: Boolean,
    isWriter: Boolean,
    backoffUntilMs: Long?,
    latestSnapshotMonthKey: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): List<StatusProblem> = buildList {
    if (!accessGranted) {
        add(
            StatusProblem(
                headline = "Nothing is being recorded",
                detail = "Notification access is off, so no listening is being captured. " +
                    "Plays that are never recorded cannot be recovered later.",
                severity = StatusProblem.Severity.BROKEN,
                actionLabel = "Grant access",
                action = StatusProblem.Action.NOTIFICATION_ACCESS,
            ),
        )
    } else if (lastCapturedAtMs == null) {
        add(
            StatusProblem(
                headline = "Nothing captured yet",
                detail = "Access is granted but no listening has been recorded. " +
                    "Play something in Spotify — a segment is written when a listen ends.",
                severity = StatusProblem.Severity.WARNING,
            ),
        )
    } else if (nowMs - lastCapturedAtMs >= CAPTURE_SILENT_MS) {
        add(
            StatusProblem(
                headline = "Capture has gone quiet",
                detail = "Nothing recorded for ${describeAge(nowMs - lastCapturedAtMs)}. " +
                    "If you have been listening, check that Spotify's \"Device Broadcast " +
                    "Status\" is still on and that notification access survived the last " +
                    "restart.",
                severity = StatusProblem.Severity.BROKEN,
                actionLabel = "Check access",
                action = StatusProblem.Action.NOTIFICATION_ACCESS,
            ),
        )
    }

    when {
        !configured -> add(
            StatusProblem(
                headline = "Spotify is not configured",
                detail = "This build has no client ID, so it cannot talk to Spotify at all. " +
                    "It was probably built from a git worktree, where local.properties is " +
                    "absent.",
                severity = StatusProblem.Severity.BROKEN,
            ),
        )

        connection is SpotifyConnectionState.Expired -> add(
            StatusProblem(
                headline = "Reconnect Spotify",
                detail = "The six-month login has expired. Likes are not syncing and " +
                    "Evergreen is not being updated. Nothing in the archive was lost.",
                severity = StatusProblem.Severity.BROKEN,
                actionLabel = "Reconnect",
                action = StatusProblem.Action.RECONNECT,
            ),
        )

        connection is SpotifyConnectionState.NotConnected -> add(
            StatusProblem(
                headline = "Spotify is not connected",
                detail = "Connect it to sync your liked songs and keep the Evergreen " +
                    "playlist up to date.",
                severity = StatusProblem.Severity.WARNING,
                actionLabel = "Connect",
                action = StatusProblem.Action.RECONNECT,
            ),
        )

        connection is SpotifyConnectionState.ExpiringSoon -> add(
            StatusProblem(
                headline = "Reconnect soon",
                detail = "The Spotify login expires in " +
                    "${daysBetween(nowMs, connection.refreshTokenExpiresAtMs)} days. " +
                    "Syncing keeps working until then.",
                severity = StatusProblem.Severity.WARNING,
                actionLabel = "Reconnect",
                action = StatusProblem.Action.RECONNECT,
            ),
        )
    }

    if (backoffUntilMs != null && backoffUntilMs > nowMs) {
        add(
            StatusProblem(
                headline = "Waiting on Spotify's rate limit",
                detail = "Requests are paused for another " +
                    "${describeAge(backoffUntilMs - nowMs)}. Nothing is lost; the next " +
                    "run picks up where this one stopped.",
                severity = StatusProblem.Severity.WARNING,
            ),
        )
    }

    // Only the writer device is supposed to update the playlist, so a stale
    // write is only news there. On the other device it is the correct state.
    val connectedEnough = connection is SpotifyConnectionState.Connected ||
        connection is SpotifyConnectionState.ExpiringSoon
    if (isWriter && connectedEnough && configured) {
        when {
            !writeConfirmed -> add(
                StatusProblem(
                    headline = "Evergreen has never been written",
                    detail = "The first write needs confirming once, after you have seen " +
                        "exactly what it would do.",
                    severity = StatusProblem.Severity.WARNING,
                    actionLabel = "Settings",
                    action = StatusProblem.Action.SETTINGS,
                ),
            )

            lastWrittenAtMs == null || nowMs - lastWrittenAtMs >= SYNC_STALE_MS -> add(
                StatusProblem(
                    headline = "Evergreen has not been updated",
                    detail = if (lastWrittenAtMs == null) {
                        "It has never been written, though writing is enabled."
                    } else {
                        "Last written ${describeAge(nowMs - lastWrittenAtMs)} ago. " +
                            "It is meant to run daily."
                    },
                    severity = StatusProblem.Severity.WARNING,
                ),
            )
        }
    }

    if (connectedEnough && configured &&
        (lastLikeSyncAtMs == null || nowMs - lastLikeSyncAtMs >= SYNC_STALE_MS)
    ) {
        add(
            StatusProblem(
                headline = "Likes have not synced",
                detail = if (lastLikeSyncAtMs == null) {
                    "No like sync has completed yet."
                } else {
                    "Last synced ${describeAge(nowMs - lastLikeSyncAtMs)} ago."
                },
                severity = StatusProblem.Severity.WARNING,
            ),
        )
    }

    // A snapshot is due once a month has ended without one being recorded for
    // it. Checked against last month rather than this one, because this month's
    // is taken on its last day and is not late until the month is over.
    val lastMonth = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        .withDayOfMonth(1)
        .minusDays(1)
    val lastMonthKey = Snapshots.monthKeyOf(lastMonth)
    if (latestSnapshotMonthKey != null && latestSnapshotMonthKey < lastMonthKey) {
        add(
            StatusProblem(
                headline = "A monthly snapshot was missed",
                detail = "The most recent is $latestSnapshotMonthKey, but $lastMonthKey " +
                    "has ended. Snapshots are taken on the last day of the month; a flat " +
                    "battery that day costs one.",
                severity = StatusProblem.Severity.WARNING,
            ),
        )
    }
}.sortedBy { it.severity.ordinal }

@Composable
private fun StatusSection(
    problems: List<StatusProblem>,
    memberCount: Int,
    lastCapturedAtMs: Long?,
    lastWrittenAtMs: Long?,
    nowMs: Long,
    onFixNotificationAccess: () -> Unit,
    onReconnectSpotify: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Spacer(modifier = Modifier.height(12.dp))

    if (problems.isEmpty()) {
        // One line, and nothing else. When everything is working there is
        // nothing to say, and saying it anyway is how a status line teaches
        // people to stop reading it.
        Text(
            text = "Everything is running.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = buildString {
                append("$memberCount in the playlist")
                lastCapturedAtMs?.let {
                    append("  ·  last heard ").append(describeAge(nowMs - it)).append(" ago")
                }
                lastWrittenAtMs?.let {
                    append("  ·  written ").append(describeAge(nowMs - it)).append(" ago")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    problems.forEach { problem ->
        val broken = problem.severity == StatusProblem.Severity.BROKEN
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (broken) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (broken) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = problem.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = problem.detail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val label = problem.actionLabel
                val action = problem.action
                if (label != null && action != null) {
                    TextButton(
                        onClick = {
                            when (action) {
                                StatusProblem.Action.NOTIFICATION_ACCESS -> onFixNotificationAccess()
                                StatusProblem.Action.RECONNECT -> onReconnectSpotify()
                                StatusProblem.Action.SETTINGS -> onOpenSettings()
                            }
                        },
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

// --- What changed ------------------------------------------------------------

@Composable
private fun ChangesHeading(changes: List<MembershipChangeRow>, nowMs: Long) {
    SectionTitle("What changed")
    if (changes.isEmpty()) {
        Text(
            text = "Nothing has joined or left in the last " +
                "${TimeUnit.MILLISECONDS.toDays(CHANGES_WINDOW_MS)} days. " +
                "That is the ordinary state — scores move over weeks, not hours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val joined = changes.count { it.isJoin }
    val left = changes.size - joined
    Text(
        text = buildString {
            if (joined > 0) append("$joined joined")
            if (joined > 0 && left > 0) append(", ")
            if (left > 0) append("$left left")
            append(" in the last ")
            append(TimeUnit.MILLISECONDS.toDays(CHANGES_WINDOW_MS))
            append(" days")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ChangeRow(row: MembershipChangeRow, nowMs: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (row.isJoin) "+" else "−",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (row.isJoin) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title ?: "(unknown title)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = buildString {
                    append(row.artist ?: "(unknown artist)")
                    append("  ·  ")
                    append(describeAge(nowMs - row.atMs))
                    append(" ago")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (row.isJoin) "#${row.rankAtJoin}" else row.rankAtLeave?.let { "#$it" } ?: "",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

// --- Near the line -----------------------------------------------------------

/** A track close enough to a cutoff that it is the likely next change. */
internal data class NearTheLine(
    val row: RankedTrackRow,
    /** True when it would join next; false when it would leave. */
    val approachingJoin: Boolean,
    /** How many ranks from the cutoff it is about to cross. */
    val distance: Int,
)

/**
 * The tracks nearest to changing state, closest first.
 *
 * Hysteresis means the two groups are not symmetric around one number. A track
 * outside the playlist is waiting to reach the join rank; a track inside it is
 * safe until it falls past the *leave* rank, thirty places further down. Showing
 * both as "near rank 100" would put members in the list who are in no danger at
 * all, and leave out the ones actually about to drop.
 */
internal fun nearTheLine(
    ranked: List<RankedTrackRow>,
    parameters: ScoringParameters,
    margin: Int = NEAR_MARGIN,
): List<NearTheLine> = ranked.mapNotNull { row ->
    when {
        !row.inPlaylist && row.rank > parameters.playlistJoinRank &&
            row.rank - parameters.playlistJoinRank <= margin ->
            NearTheLine(row, approachingJoin = true, distance = row.rank - parameters.playlistJoinRank)

        row.inPlaylist && parameters.playlistLeaveRank - row.rank in 0 until margin ->
            NearTheLine(row, approachingJoin = false, distance = parameters.playlistLeaveRank - row.rank)

        else -> null
    }
}.sortedBy { it.distance }.take(NEAR_LIMIT)

@Composable
private fun NearTheLineRow(near: NearTheLine, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${near.row.rank}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = near.row.title ?: "(unknown title)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (near.row.inPlaylist) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = near.row.artist ?: "(unknown artist)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (near.approachingJoin) {
                "${near.distance} off joining"
            } else {
                "${near.distance} off dropping"
            },
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = if (near.approachingJoin) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    HorizontalDivider()
}

// --- Shared ------------------------------------------------------------------

/** The window "what changed" covers, and the wording that goes with it. */
private val CHANGES_WINDOW_MS = TimeUnit.DAYS.toMillis(14)
private const val CHANGES_LIMIT = 40

/**
 * How quiet capture has to go before the status line says so.
 *
 * A week, matching the notification. Generous on purpose: a quiet stretch --
 * a holiday, a week of podcasts -- is not a fault, and a status line that cries
 * wolf after two days is one nobody reads.
 */
private val CAPTURE_SILENT_MS = TimeUnit.DAYS.toMillis(7)

/** Two days, so one missed daily run is not yet news. */
private val SYNC_STALE_MS = TimeUnit.DAYS.toMillis(2)

private const val NEAR_MARGIN = 8
private const val NEAR_LIMIT = 12

private fun daysBetween(fromMs: Long, toMs: Long): Long =
    TimeUnit.MILLISECONDS.toDays((toMs - fromMs).coerceAtLeast(0))

/**
 * "3 days", "20 minutes" — an age in the largest unit that still reads as a
 * number rather than a timestamp.
 *
 * The home screen says how long ago rather than when, deliberately: "last heard
 * 2 days ago" answers the question being asked, where "2026-09-16 21:40" has to
 * be subtracted from today first.
 */
internal fun describeAge(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val days = TimeUnit.MILLISECONDS.toDays(safe)
    return when {
        minutes < 1 -> "moments"
        minutes < 60 -> "$minutes minute${plural(minutes)}"
        hours < 24 -> "$hours hour${plural(hours)}"
        days < 60 -> "$days day${plural(days)}"
        else -> "${days / 30} months"
    }
}

private fun plural(value: Long): String = if (value == 1L) "" else "s"
