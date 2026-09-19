package com.example.evergreen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The four top-level places the app can be.
 *
 * Home is first because it is the front door: after the validation phase ends,
 * the app's whole job is to run unattended and be checkable, and "did it run,
 * what moved" is the only thing it knows that Spotify does not. Scores and
 * Snapshots are the validation and archive surfaces, deliberately one tap away
 * rather than on the home screen.
 */
private enum class Section(val label: String) {
    HOME("Home"),
    SCORES("Scores"),
    SNAPSHOTS("Snapshots"),
    SETTINGS("Settings"),
}

/**
 * Where the app is, beyond which tab is selected.
 *
 * Hand-rolled rather than androidx.navigation. Four top-level destinations and
 * two detail screens do not need a navigation graph, a back stack entry type
 * and a saved-state handle -- and the NW-A307 has limited RAM, so a dependency
 * that buys nothing here is a dependency that costs something. A `when` over
 * three saved values is the whole router.
 */
@Composable
fun EvergreenApp(
    accessGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onConnectSpotify: () -> Unit,
    /** Bumped when the login flow returns, so connection state is re-read. */
    connectionEpoch: Int,
    modifier: Modifier = Modifier,
) {
    var section by rememberSaveable { mutableIntStateOf(Section.HOME.ordinal) }
    var openTrackId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openSnapshotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDebug by rememberSaveable { mutableStateOf(false) }

    val onDetail = openTrackId != null || openSnapshotId != null || showDebug

    // System back closes a detail screen before it closes the app. Without
    // this, opening a track and pressing back would quit, which on a device
    // with a hardware back button is the first thing anyone tries.
    BackHandler(enabled = onDetail) {
        when {
            openTrackId != null -> openTrackId = null
            openSnapshotId != null -> openSnapshotId = null
            else -> showDebug = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            showDebug -> {
                DetailBar(title = "Debug") { showDebug = false }
                DebugScreen(
                    accessGranted = accessGranted,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenAppInfo = onOpenAppInfo,
                )
            }

            openTrackId != null -> {
                DetailBar(title = "Track") { openTrackId = null }
                TrackDetailScreen(trackId = openTrackId!!)
            }

            openSnapshotId != null -> {
                DetailBar(title = "Snapshot") { openSnapshotId = null }
                SnapshotMonthScreen(
                    snapshotId = openSnapshotId!!,
                    onOpenTrack = { trackId ->
                        openSnapshotId = null
                        openTrackId = trackId
                    },
                )
            }

            else -> {
                // The tab row is the header. A Material3 TopAppBar would cost
                // another 64 dp of the Walkman's 602, to show a title the tab
                // row already states.
                PrimaryTabRow(selectedTabIndex = section) {
                    Section.entries.forEach { entry ->
                        Tab(
                            selected = section == entry.ordinal,
                            onClick = { section = entry.ordinal },
                            text = { Text(entry.label, fontSize = 13.sp, maxLines = 1) },
                        )
                    }
                }

                when (Section.entries[section]) {
                    Section.HOME -> HomeScreen(
                        accessGranted = accessGranted,
                        connectionEpoch = connectionEpoch,
                        onOpenScores = { section = Section.SCORES.ordinal },
                        onOpenTrack = { openTrackId = it },
                        onOpenSettings = { section = Section.SETTINGS.ordinal },
                        onFixNotificationAccess = onOpenNotificationSettings,
                        onReconnectSpotify = onConnectSpotify,
                    )

                    Section.SCORES -> ScoresTab(
                        onOpenTrack = { openTrackId = it },
                        onOpenSettings = { section = Section.SETTINGS.ordinal },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )

                    Section.SNAPSHOTS -> SnapshotsScreen(
                        onOpenSnapshot = { openSnapshotId = it },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )

                    Section.SETTINGS -> SettingsScreen(
                        onConnectSpotify = onConnectSpotify,
                        connectionEpoch = connectionEpoch,
                        onOpenDebug = { showDebug = true },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * A one-line header for a detail screen, with the way back.
 *
 * Deliberately not a TopAppBar: this is about 36 dp against its 64, and on the
 * Walkman that difference is a row of the list underneath.
 */
@Composable
private fun DetailBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider()
}

/**
 * The month list. Filled in by step 6; a plain list of what exists until then.
 */
@Composable
private fun SnapshotsScreen(onOpenSnapshot: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SnapshotsCard(onOpenSnapshot = onOpenSnapshot)
    }
}
