package com.example.evergreen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evergreen.capture.CaptureWatchWorker
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ParameterHistoryEntity
import com.example.evergreen.data.SyncKeys
import com.example.evergreen.scoring.ScoreService
import com.example.evergreen.scoring.ScoringParameters
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Settings, with the scoring at the top.
 *
 * This replaces the JSON text box that used to sit behind a "Parameters"
 * button on the Scores tab. That editor was right for Phase 3, where the point
 * was to see the working; it is wrong for something that gets retuned, because
 * `negativeWeight: -0.5` states the value exactly and explains nothing about
 * what it does.
 *
 * Changes are edited as a draft and applied together. Every save triggers a
 * full recompute -- seconds of CPU on the NW-A307 with six years of history --
 * so applying each tap immediately would make a screen of steppers unusable.
 *
 * **A parameter change never runs a migration** (invariant 1d). It writes one
 * `sync_state` row and one line in the parameter log, and invalidates the score
 * cache by leaving its rows under a hash that no longer matches. Every value
 * here can be changed back and the old ranking returns exactly, because a score
 * is computed from the stored segments every time rather than accumulated.
 */
@Composable
fun SettingsScreen(
    onConnectSpotify: () -> Unit,
    connectionEpoch: Int,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SpotifyTab(
        onConnect = onConnectSpotify,
        connectionEpoch = connectionEpoch,
        modifier = modifier,
        header = {
            ScoringSection()
            NotificationsSection()
        },
    ) {
        SectionTitle("Diagnostics")
        Text(
            text = "The raw capture views: every stored segment, and the live log of what " +
                "the media session is reporting right now. Nothing here is needed in " +
                "ordinary use — it is how anything gets diagnosed when it is not.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenDebug, modifier = Modifier.padding(top = 4.dp)) {
            Text("Open the debug screens")
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ScoringSection() {
    val context = LocalContext.current
    val db = remember(context) { EvergreenDatabase.get(context) }
    val scoringDao = remember(db) { db.scoringDao() }
    val service = remember(db) { ScoreService(db) }
    val scope = rememberCoroutineScope()

    val saved by scoringDao.scoringParametersFlow().collectAsState(initial = ScoringParameters())
    val history by scoringDao.parameterHistory().collectAsState(initial = emptyList())

    // The draft starts from whatever is stored and follows it whenever the
    // stored value changes underneath -- which happens when the JSON editor or
    // "reset to defaults" saves, so the steppers do not keep showing what was
    // on screen before.
    var draft by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { draft = saved }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showJson by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    val dirty = draft != saved
    val problems = draft.problems()

    fun save(next: ScoringParameters, note: String) {
        scope.launch {
            busy = true
            status = null
            val changed = scoringDao.putScoringParameters(next, System.currentTimeMillis())
            if (changed) {
                // Tell the sync worker to keep quiet about the movement this
                // causes. Forty joins and leaves after nudging a number is the
                // arithmetic doing as it was told, not news about listening.
                scoringDao.putSyncMs(
                    SyncKeys.PARAMETERS_CHANGED_AT,
                    System.currentTimeMillis(),
                )
            }
            val ranking = service.ranking(force = true)
            status = if (changed) {
                "$note Rescored ${ranking.tracks.size} tracks."
            } else {
                "Nothing changed — those are the settings already in force."
            }
            busy = false
        }
    }

    SectionTitle("Scoring")
    Text(
        text = "These decide what the Evergreen playlist contains. Changing one rescores " +
            "everything from the listening already stored; it deletes nothing, and any " +
            "value can be set back to get the old ranking exactly.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    status?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    SCORING_SETTINGS.forEach { setting ->
        SettingRow(
            setting = setting,
            parameters = draft,
            isDefault = setting.display(draft) == setting.display(ScoringParameters()),
            enabled = !busy,
            onChange = { draft = it },
        )
    }

    if (problems.isNotEmpty()) {
        Text(
            text = problems.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { save(draft, "Saved.") },
            enabled = dirty && !busy && problems.isEmpty(),
        ) {
            Text(if (busy) "Rescoring…" else "Save and rescore")
        }
        if (dirty) {
            OutlinedButton(onClick = { draft = saved }, enabled = !busy) {
                Text("Discard")
            }
        }
    }
    if (dirty) {
        Text(
            text = "Not saved yet. Nothing is rescored until you press save — a recompute " +
                "is seconds of work on this device, so it does not run on every tap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (draft != ScoringParameters()) {
        TextButton(
            onClick = { draft = ScoringParameters() },
            enabled = !busy,
        ) {
            Text("Back to the calibrated defaults")
        }
    }

    // --- The record of what has been changed ---------------------------------

    SectionTitle("Changes to the scoring")
    if (history.isEmpty()) {
        Text(
            text = "Nothing has been changed yet, so every score you have ever seen was " +
                "produced by the calibrated defaults.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text = "${history.size} change${if (history.size == 1) "" else "s"}, newest " +
                "first. Kept because a score means nothing without the settings that " +
                "produced it, and this is the only record of what earlier scores meant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "Hide the list" else "Show the list")
        }
        if (showHistory) {
            history.forEach { row -> ParameterChangeRow(row) }
        }
    }

    // --- The escape hatch ----------------------------------------------------

    SectionTitle("Advanced")
    Text(
        text = "The same settings as raw JSON, including values the controls above will " +
            "not reach. Kept for emergencies and for reading a value back exactly.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { showJson = true }, enabled = !busy) {
        Text("Edit the scoring parameters as JSON")
    }

    if (showJson) {
        JsonEditor(
            current = saved,
            onDismiss = { showJson = false },
            onSave = { parsed ->
                showJson = false
                save(parsed, "Saved from JSON.")
            },
        )
    }
}

/**
 * What the app will interrupt for.
 *
 * Only the threshold lives here. Whether each channel makes a sound, and
 * whether it appears at all, is Android's own setting -- duplicating it in the
 * app would give the user two switches for one thing and no way to tell which
 * one is winning.
 */
@Composable
private fun NotificationsSection() {
    val context = LocalContext.current
    val scoringDao = remember(context) { EvergreenDatabase.get(context).scoringDao() }
    val scope = rememberCoroutineScope()

    val row by scoringDao.syncValueFlow(SyncKeys.CAPTURE_SILENT_DAYS).collectAsState(initial = null)
    val days = row?.valueMs ?: CaptureWatchWorker.DEFAULT_SILENT_DAYS

    fun set(value: Long) {
        scope.launch { scoringDao.putSyncMs(SyncKeys.CAPTURE_SILENT_DAYS, value) }
    }

    SectionTitle("Telling you things")
    Text(
        text = "Two notifications are on: when songs join or leave Evergreen, and when " +
            "nothing has been recorded for a while. Whether each one makes a sound is " +
            "Android's setting rather than this app's — hold the notification, or find " +
            "Evergreen in the system notification settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
    Text(
        text = "Warn me if nothing is recorded",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        text = if (days <= 0L) {
            "Off. Nothing will tell you if capture stops, and listening that is never " +
                "recorded cannot be recovered afterwards."
        } else {
            "After $days day${if (days == 1L) "" else "s"} of silence. Once per quiet " +
                "stretch, not once a day, and it re-arms itself as soon as listening " +
                "resumes."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = { set(days - 1) }, enabled = days > 0L) { Text("−") }
        Text(
            text = if (days <= 0L) "off" else "$days days",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).widthIn(min = 64.dp),
        )
        OutlinedButton(onClick = { set(days + 1) }, enabled = days < MAX_SILENT_DAYS) { Text("+") }
    }
}

/**
 * One setting: what it is, what the current value means, and two buttons.
 *
 * Steppers rather than sliders. A slider on a 339 dp screen cannot reliably
 * pick 45 out of 5..365, and every one of these values is a number the user
 * will want to set exactly and read back.
 */
@Composable
private fun SettingRow(
    setting: ScoringSetting,
    parameters: ScoringParameters,
    isDefault: Boolean,
    enabled: Boolean,
    onChange: (ScoringParameters) -> Unit,
) {
    val lower = setting.decrease(parameters)
    val higher = setting.increase(parameters)

    HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
    Text(
        text = setting.title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        text = setting.explain(parameters),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(
            onClick = { lower?.let(onChange) },
            enabled = enabled && lower != null,
        ) {
            Text("−")
        }
        Text(
            text = setting.display(parameters),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).widthIn(min = 64.dp),
        )
        OutlinedButton(
            onClick = { higher?.let(onChange) },
            enabled = enabled && higher != null,
        ) {
            Text("+")
        }
    }
    if (!isDefault) {
        Text(
            text = "Default: ${setting.defaultNote}",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ParameterChangeRow(row: ParameterHistoryEntity) {
    val before = remember(row.previousJson) {
        row.previousJson?.let { runCatching { ScoringParameters.fromJson(it) }.getOrNull() }
    }
    val after = remember(row.parametersJson) {
        runCatching { ScoringParameters.fromJson(row.parametersJson) }.getOrNull()
    }
    val differences = remember(before, after) { describeDifferences(before, after) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = DATE_FORMAT.format(Date(row.changedAt)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = differences.ifEmpty { "(nothing the controls above can show)" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    HorizontalDivider()
}

/**
 * What actually changed between two sets of settings, in the screen's own
 * words.
 *
 * Reusing [SCORING_SETTINGS] rather than comparing fields by name means the log
 * reads in the same language as the controls, and a setting added later appears
 * here without anything being remembered.
 */
internal fun describeDifferences(
    before: ScoringParameters?,
    after: ScoringParameters?,
): String {
    if (before == null || after == null) return ""
    return SCORING_SETTINGS
        .filter { it.display(before) != it.display(after) }
        .joinToString("\n") { "${it.title}: ${it.display(before)} → ${it.display(after)}" }
}

/**
 * The parameters as raw JSON.
 *
 * Kept, not deleted. The controls above deliberately have tighter bounds than
 * the scorer allows, and there is no way to read a value back byte-exactly from
 * a stepper — both of which matter when something has gone wrong and the
 * question is what the settings actually are.
 */
@Composable
private fun JsonEditor(
    current: ScoringParameters,
    onDismiss: () -> Unit,
    onSave: (ScoringParameters) -> Unit,
) {
    var text by remember { mutableStateOf(current.toJson()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scoring parameters as JSON") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Editing these rescores everything. It changes no stored listening " +
                        "and runs no migration — scores are computed from the segments " +
                        "every time, so any value can be changed back.",
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

/** Beyond about two months, the alert has stopped being an alert. */
private const val MAX_SILENT_DAYS = 60L
