package com.example.evergreen.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.evergreen.capture.CaptureSwitch
import com.example.evergreen.data.DataExport
import com.example.evergreen.data.DatabaseBackup
import com.example.evergreen.data.DatabaseRestore
import com.example.evergreen.data.DatabaseRestoreController
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.MirrorImport
import com.example.evergreen.data.MirrorImportController
import com.example.evergreen.data.MirrorState
import kotlinx.coroutines.launch

/**
 * The per-device settings: whether this device writes Evergreen, a button to
 * copy the database somewhere safe, and — the other end of that button — the
 * mirror import.
 *
 * Shown above the streaming-history import, deliberately. The reading order on
 * the screen is the order the user should act in: decide whether this is the
 * writer device, take a backup, then import.
 */
@Composable
fun DeviceCard() {
    val context = LocalContext.current
    val importDao = remember(context) { EvergreenDatabase.get(context).importDao() }
    val scope = rememberCoroutineScope()

    val writerRow by importDao.evergreenWriterFlow().collectAsState(initial = null)
    val isWriter = writerRow?.valueText == "true"
    val mirror by importDao.mirrorStateFlow().collectAsState(initial = null)

    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backingUp by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(
        // A generic type rather than something SQLite-specific: Android's file
        // picker will not offer a save location for a MIME type no app claims.
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backingUp = true
        backupMessage = null
        scope.launch {
            backupMessage = try {
                val bytes = DatabaseBackup.writeTo(context, uri)
                "Backed up ${bytes / 1024} KB to the file you chose."
            } catch (failure: Exception) {
                "Backup failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
            backingUp = false
        }
    }

    SectionTitle("This device")

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "This device writes Evergreen",
            style = MaterialTheme.typography.bodyMedium,
        )
        // Switched off and unusable on a mirror rather than hidden. The flag
        // is really there — it came in with the copy — and a control that
        // vanished would leave the user wondering where it went instead of
        // reading why it does nothing.
        Switch(
            checked = isWriter && mirror == null,
            enabled = mirror == null,
            onCheckedChange = { enabled -> scope.launch { importDao.setEvergreenWriter(enabled) } },
        )
    }
    Text(
        if (mirror != null) {
            "Unavailable: this device holds a mirror of ${mirror!!.describeSource()}, and a " +
                "mirror never writes to Spotify. It arrived carrying that device's own " +
                "settings, including this one — which is exactly why the app checks for a " +
                "mirror before it checks this switch."
        } else {
            "Turn this on for exactly one device — the one you listen on. Until the " +
                "sync backend arrives, each device has its own database, so two devices " +
                "running the Evergreen sync would each undo the other's writes every day, " +
                "against a quota neither can see. The import should also be run only here."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(
        onClick = { saver.launch(DatabaseBackup.suggestedFileName()) },
        enabled = !backingUp,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(if (backingUp) "Backing up…" else "Back up database")
    }
    backupMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        "Writes one complete copy of the listening history, likes and settings to a " +
            "file you pick. Take one before importing, and before anyone runs the " +
            "instrumented tests — those uninstall the app and destroy its database. " +
            "The backup holds no passwords or tokens: those are kept encrypted " +
            "outside the database entirely, so a copy of it cannot contain one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    RestoreSection()

    RecordingSection(mirror = mirror)

    MirrorSection(mirror = mirror)

    ExportSection()
}

/**
 * Putting a backup back on the device that took it.
 *
 * The other half of the button above, and missing until Phase 5b. It sits here
 * rather than under "Mirror of another device" on purpose: this is about this
 * device's own record, and the mirror import — which looks almost identical and
 * means the opposite — is a screen-length away under its own heading.
 */
@Composable
private fun RestoreSection() {
    val context = LocalContext.current
    val state by DatabaseRestoreController.state.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { DatabaseRestoreController.pick(context, it) } }

    // Same reasoning as the mirror import: the database handle every composable
    // is reading through has just been closed and replaced, and recreating the
    // activity is how they all get back onto the new file at once.
    val activity = context as? Activity
    LaunchedEffect(state) {
        if (state is DatabaseRestoreController.State.Done) {
            DatabaseRestoreController.dismiss(context)
            activity?.recreate()
        }
    }

    when (val current = state) {
        is DatabaseRestoreController.State.Idle,
        is DatabaseRestoreController.State.Done,
        -> Unit

        is DatabaseRestoreController.State.Checking -> StatusLine("Checking that file…")

        is DatabaseRestoreController.State.Restoring ->
            StatusLine("Putting the backup back. Do not close the app.")

        is DatabaseRestoreController.State.Failed -> {
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { DatabaseRestoreController.dismiss(context) }) { Text("Dismiss") }
        }

        is DatabaseRestoreController.State.Confirming -> ConfirmRestore(
            preview = current.preview,
            onConfirm = { DatabaseRestoreController.confirm(context) },
            onCancel = { DatabaseRestoreController.dismiss(context) },
        )
    }

    OutlinedButton(
        onClick = {
            // The same generous MIME list as everywhere else a .db is picked:
            // file managers report one as application/octet-stream,
            // application/x-sqlite3 or nothing at all, and a filter that misses
            // leaves the file greyed out with no explanation.
            picker.launch(
                arrayOf("application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3", "*/*"),
            )
        },
        enabled = state is DatabaseRestoreController.State.Idle ||
            state is DatabaseRestoreController.State.Failed,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Restore from backup")
    }
    Text(
        "Puts a backup of *this* device back, replacing what is here now. It changes " +
            "the listening history and nothing else: whatever this device is — the one " +
            "that writes Evergreen, or a mirror, recording or not — it still is " +
            "afterwards. A backup taken on a different device is refused, because " +
            "showing another device's listening is what \"Import a mirror\" below is for.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Whether this device is recording, and a way back if it is not.
 *
 * Shown **only when recording is off and this is not a mirror**, which is a
 * state that should not happen and has to be recoverable when it does. Capture
 * is switched by disabling the listener component, and the two operations that
 * replace the database both turn it off for the moment of the swap. If the
 * process dies inside that moment, or the swap fails after it, recording stays
 * off on a device that was recording — and until Phase 5b there was no control
 * anywhere that turned it back on outside mirror mode, so the only cure was to
 * reinstall.
 *
 * It is not shown in the ordinary case, because a permanent card saying
 * everything is fine is the mistake Phase 4's first step undid.
 */
@Composable
private fun RecordingSection(mirror: MirrorState?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Read once per composition rather than observed: the component enabled
    // setting is not a flow, and the only things that change it are on this
    // screen, which recomposes after each of them.
    var enabled by remember { mutableStateOf(CaptureSwitch.isEnabled(context)) }
    if (enabled || mirror != null) return

    Text(
        text = "Recording is off on this device.",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "This device is not a mirror, so it should be recording. The likely cause is a " +
            "restore or an import that was interrupted while the database was being " +
            "replaced — both switch recording off for that moment. Nothing that happened " +
            "while it was off was recorded, and that cannot be recovered.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            scope.launch {
                CaptureSwitch.setEnabled(context, true)
                enabled = CaptureSwitch.isEnabled(context)
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Start recording again")
    }
}

/**
 * The confirmation for the one operation that could change what a device is.
 *
 * It does not change it — that is the whole design — so the dialog's job is to
 * say so in as many words, alongside the counts on both sides. "Are you sure"
 * without the numbers would be a question nobody can answer, and without the
 * sentence about the role it would be the one question the user actually has.
 */
@Composable
private fun ConfirmRestore(
    preview: DatabaseRestore.Preview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Put this backup back?") },
        text = {
            Text(
                (
                    if (preview.sourceIsUnknown) {
                        "The file does not say which device took it, which means it was " +
                            "taken before September 2026. Be sure it is this device's. "
                    } else {
                        "The file was taken on ${preview.sourceDevice}, which is this " +
                            "device. "
                    }
                    ) +
                    "It was taken ${DatabaseRestore.describeTakenAt(preview.takenAtMs)} and " +
                    "holds ${preview.incomingSegments} play(s) and " +
                    "${preview.incomingLikes} liked song(s).\n\n" +
                    "It will replace this device's database, which holds " +
                    "${preview.existingSegments} play(s) and ${preview.existingLikes} " +
                    "liked song(s). That cannot be undone, and cloud backup is switched " +
                    "off for this app, so take a backup here first if any of it matters.\n\n" +
                    preview.describeRoleAfterwards() +
                    " The scores are recomputed here from the facts in the file.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Put it back")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Getting the data out in a form something else can read.
 *
 * Separate from the backup above, and the difference is worth stating on screen
 * because the two buttons look alike: the backup is for putting the data *back*
 * and is a SQLite file nothing else will open, while these are for using it
 * somewhere else and are plain CSV.
 */
@Composable
private fun ExportSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    // Which file the picker is about to create. Set before it is launched,
    // because the callback has only the URI to go on.
    var pending by remember { mutableStateOf(DataExport.Kind.LISTENING) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val kind = pending
        exporting = true
        message = null
        scope.launch {
            message = try {
                val rows = DataExport.writeTo(context, uri, kind)
                // Counts only, never a row: this is the user's listening
                // history and it belongs in the file, not on a status line.
                "Wrote $rows row(s) to the file you chose."
            } catch (failure: Exception) {
                "Export failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
            exporting = false
        }
    }

    fun export(kind: DataExport.Kind) {
        pending = kind
        saver.launch(DataExport.suggestedFileName(kind))
    }

    SectionTitle("Export readable data")
    Text(
        "CSV, for a spreadsheet or a script — as opposed to the backup above, which is " +
            "a database file meant for putting the data back. Two flat tables with fixed " +
            "columns, so a CSV opens them anywhere with no tooling at all.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { export(DataExport.Kind.LISTENING) },
            enabled = !exporting,
        ) {
            Text("Listening")
        }
        OutlinedButton(
            onClick = { export(DataExport.Kind.RANKINGS) },
            enabled = !exporting,
        ) {
            Text("Rankings")
        }
    }
    message?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        "\"Listening\" is one row per play, including the imported history, with what " +
            "the export could not say left blank rather than filled with a zero. " +
            "\"Rankings\" is one row per liked track in score order, as the app last " +
            "computed it. Every time is written twice, readable and as epoch " +
            "milliseconds, because neither can be derived from the other without " +
            "knowing an offset that changes twice a year.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Importing a mirror, and living with one.
 *
 * Deliberately next to the backup button, because the two are the two ends of
 * one thing: the Walkman writes the file, this reads it. There is no second
 * export path to keep in step, no new format, and nothing new that can leave
 * the device.
 *
 * The confirmation is the same pattern as wiping listening history — it says
 * exactly what will be destroyed, in counts, and asks once. It is a harder
 * question than the wipe, because what it destroys is *this* device's record
 * and the file that replaces it is not in view; so the dialog names both sides.
 */
@Composable
private fun MirrorSection(mirror: MirrorState?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importDao = remember(context) { EvergreenDatabase.get(context).importDao() }
    val state by MirrorImportController.state.collectAsState()

    var confirmingCapture by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { MirrorImportController.pick(context, it) } }

    // When the import finishes, start the app's UI over.
    //
    // Everything on screen is reading through a database handle that has just
    // been closed and replaced. Recreating the activity is how they all get
    // back onto the new file at once, and it lands the user on Home, which is
    // where the mirror now announces itself — so the restart doubles as the
    // receipt.
    val activity = context as? Activity
    LaunchedEffect(state) {
        if (state is MirrorImportController.State.Done) {
            MirrorImportController.dismiss(context)
            activity?.recreate()
        }
    }

    SectionTitle("Mirror of another device")

    if (mirror != null) {
        Text(
            text = "This is a mirror of ${mirror.describeSource()}, taken " +
                "${MirrorImport.describeTakenAt(mirror.takenAtMs)}.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Everything here was recorded on that device. Nothing on this one is being " +
                "recorded, nothing is written to Spotify, and importing again replaces " +
                "all of it. The scores are recomputed here from the facts in the copy, " +
                "which is why they should match what that device shows.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { confirmingCapture = true },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Start recording on this device")
        }
        Text(
            "Turns capture back on and stops this being a mirror. Do it only if you want " +
                "this device to keep its own record from now on — the next import would " +
                "discard it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Replaces everything on this device with a copy of another device's database, " +
                "taken with the \"Back up database\" button there. This device then shows " +
                "that device's listening on this screen, recomputed here — and never " +
                "writes to Spotify.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (val current = state) {
        is MirrorImportController.State.Idle,
        is MirrorImportController.State.Done,
        -> Unit

        is MirrorImportController.State.Checking -> StatusLine("Checking that file…")

        is MirrorImportController.State.Importing ->
            StatusLine("Replacing the database. Do not close the app.")

        is MirrorImportController.State.Failed -> {
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { MirrorImportController.dismiss(context) }) { Text("Dismiss") }
        }

        is MirrorImportController.State.Confirming -> ConfirmMirrorImport(
            preview = current.preview,
            onConfirm = { MirrorImportController.confirm(context) },
            onCancel = { MirrorImportController.dismiss(context) },
        )
    }

    OutlinedButton(
        onClick = {
            // The same generous MIME list as the streaming-history import, and
            // for the same reason: file managers report a .db file as
            // application/octet-stream, application/x-sqlite3 or nothing at
            // all, and a filter that misses leaves the file greyed out with no
            // explanation. The content is checked after it is copied.
            picker.launch(
                arrayOf("application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3", "*/*"),
            )
        },
        enabled = state is MirrorImportController.State.Idle ||
            state is MirrorImportController.State.Failed,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(if (mirror == null) "Import a mirror" else "Import a newer mirror")
    }

    if (confirmingCapture) {
        AlertDialog(
            onDismissRequest = { confirmingCapture = false },
            title = { Text("Start recording on this device?") },
            text = {
                Text(
                    "This stops being a mirror. Capture starts again here, and this " +
                        "device's database becomes its own record rather than a copy of " +
                        "${mirror?.describeSource() ?: "another device"}'s.\n\n" +
                        "The copied history stays — nothing is deleted. But importing a " +
                        "mirror again later would replace all of it, including whatever " +
                        "is recorded here in the meantime.\n\n" +
                        "It still will not write to Spotify unless you also turn on " +
                        "\"This device writes Evergreen\", which should be on for exactly " +
                        "one device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingCapture = false
                    scope.launch {
                        importDao.clearMirror()
                        CaptureSwitch.setEnabled(context, true)
                    }
                }) {
                    Text("Start recording")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCapture = false }) { Text("Stay a mirror") }
            },
        )
    }
}

/**
 * The one destructive confirmation in this phase.
 *
 * It names both sides in counts, because the user cannot see either: what is
 * arriving is inside a file, and what is leaving is this device's own record,
 * which is about to stop existing. "Are you sure" without numbers would be a
 * question nobody can answer.
 */
@Composable
private fun ConfirmMirrorImport(
    preview: MirrorImport.Preview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Replace everything on this device?") },
        text = {
            Text(
                "The file is a copy of ${preview.describeSource()}, taken " +
                    "${MirrorImport.describeTakenAt(preview.takenAtMs)}. It holds " +
                    "${preview.incomingSegments} play(s) and ${preview.incomingLikes} " +
                    "liked song(s).\n\n" +
                    "It will replace this device's database, which holds " +
                    "${preview.existingSegments} play(s) and ${preview.existingLikes} " +
                    "liked song(s). That cannot be undone, and cloud backup is switched " +
                    "off for this app, so take a backup here first if any of it matters.\n\n" +
                    "Afterwards this device stops recording, stops syncing likes, and " +
                    "will not write to Spotify. Evergreen keeps being written by " +
                    "${preview.describeSource()}.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Replace it")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}
