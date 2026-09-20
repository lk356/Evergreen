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
import com.example.evergreen.data.BatchExport
import com.example.evergreen.data.BatchExportController
import com.example.evergreen.data.BatchImport
import com.example.evergreen.data.BatchImportController
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

    ChangesSection(mirror = mirror)

    MirrorSection(mirror = mirror)

    ExportSection()
}

/**
 * Sending and receiving **changes** — the small file, as opposed to the whole
 * database.
 *
 * One section with two halves rather than two sections, because which half a
 * device uses is decided by what the device is: the writer sends, a mirror
 * receives. Showing both everywhere would invite the one thing this must not
 * do, which is the mirror sending its copy of the writer's facts back.
 */
@Composable
private fun ChangesSection(mirror: MirrorState?) {
    SectionTitle("Changes since last time")
    if (mirror == null) {
        ExportChangesSection()
    } else {
        ImportChangesSection(mirror = mirror)
    }
}

/**
 * The writer's half: "Export changes since…".
 *
 * Beside the backup button and through the same create-document picker, which
 * is the whole transport. A backup is 21.0 MiB of database; this is a list of
 * what is new, and the size it reports is the point of the phase.
 */
@Composable
private fun ExportChangesSection() {
    val context = LocalContext.current
    val state by BatchExportController.state.collectAsState()

    val saver = rememberLauncherForActivityResult(
        // Plain text rather than something JSON-specific: the picker will not
        // offer a save location for a MIME type no app on the device claims,
        // which is the same trap the backup button hit.
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) BatchExportController.dismiss() else BatchExportController.writeTo(context, uri)
    }

    Text(
        "Writes a small file listing what this device has recorded since the last " +
            "time you did this — plays, likes, settings, snapshots — for the other " +
            "device to add to its copy. It is the same idea as the backup above, " +
            "minus everything the other device already has.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (val current = state) {
        is BatchExportController.State.Idle -> Unit

        is BatchExportController.State.Planning -> StatusLine("Counting what is new…")

        is BatchExportController.State.Ready -> {
            val plan = current.plan
            Text(
                text = if (plan.segments == 0) {
                    "Nothing new to send since ${plan.describeWatermark()}. The other " +
                        "types are always sent in full, so the file would still hold " +
                        "${plan.totalFacts} record(s)."
                } else {
                    "${plan.segments} new play(s) since ${plan.describeWatermark()}, and " +
                        "${plan.totalFacts} record(s) in all."
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { saver.launch(BatchExport.suggestedFileName()) }) {
                    Text("Save the file")
                }
                TextButton(onClick = { BatchExportController.dismiss() }) { Text("Cancel") }
            }
        }

        is BatchExportController.State.Writing -> StatusLine("Writing the file…")

        is BatchExportController.State.Done -> {
            val written = current.written
            Text(
                text = "Wrote ${written.plan.totalFacts} record(s), " +
                    "${BatchExport.describeSize(written.bytes)}, in " +
                    "${written.tookMs} ms.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { BatchExportController.dismiss() }) { Text("Done") }
        }

        is BatchExportController.State.Failed -> {
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { BatchExportController.dismiss() }) { Text("Dismiss") }
        }
    }

    if (state is BatchExportController.State.Idle ||
        state is BatchExportController.State.Failed
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { BatchExportController.plan(context, false) }) {
                Text("Export changes")
            }
            // Offered rather than hidden, because it is the only way to rebuild
            // a mirror from changes alone, and because a watermark that has
            // drifted is fixed by starting again rather than by explaining.
            TextButton(onClick = { BatchExportController.plan(context, true) }) {
                Text("From the beginning")
            }
        }
    }
}

/**
 * The mirror's half: "Import changes".
 *
 * It inserts; it does not replace. A delta arriving on top of a mirror extends
 * it, and the device is still a mirror afterwards — capture off, no Spotify
 * writes, and the home screen still says so.
 */
@Composable
private fun ImportChangesSection(mirror: MirrorState) {
    val context = LocalContext.current
    val state by BatchImportController.state.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { BatchImportController.pick(context, it) } }

    Text(
        "Adds what ${mirror.describeSource()} has recorded since this copy was made, " +
            "from the small file its \"Export changes\" button wrote. Nothing is " +
            "replaced and nothing is lost — and this device stays a mirror, so it " +
            "still records nothing and still never writes to Spotify.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (val current = state) {
        is BatchImportController.State.Idle -> Unit

        is BatchImportController.State.Checking -> StatusLine("Checking that file…")

        is BatchImportController.State.Applying -> StatusLine("Adding the changes…")

        is BatchImportController.State.Confirming -> ConfirmBatchImport(
            preview = current.preview,
            onConfirm = { BatchImportController.confirm(context) },
            onCancel = { BatchImportController.dismiss() },
        )

        is BatchImportController.State.Done -> {
            val applied = current.applied
            Text(
                text = if (applied.changedNothing) {
                    "Nothing to add — this copy already held all " +
                        "${applied.preview.totalFacts} record(s) in that file."
                } else {
                    "Added ${applied.segmentsAdded} play(s) and " +
                        "${applied.likeEventsAdded} like change(s); " +
                        "${applied.segmentsAlreadyHeld} play(s) were already here. " +
                        "Took ${applied.tookMs} ms."
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { BatchImportController.dismiss() }) { Text("Done") }
        }

        is BatchImportController.State.Failed -> {
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { BatchImportController.dismiss() }) { Text("Dismiss") }
        }
    }

    OutlinedButton(
        onClick = {
            picker.launch(arrayOf("text/plain", "application/json", "application/x-ndjson", "*/*"))
        },
        enabled = state is BatchImportController.State.Idle ||
            state is BatchImportController.State.Failed ||
            state is BatchImportController.State.Done,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Import changes")
    }
}

/**
 * The confirmation for a delta.
 *
 * Gentler than the mirror import's, and honestly so: this one destroys
 * nothing. What it still has to say is where the file came from and how much
 * is in it, because the user cannot see inside it.
 */
@Composable
private fun ConfirmBatchImport(
    preview: BatchImport.Preview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add these changes?") },
        text = {
            Text(
                "The file was written by ${preview.describeProducer()} on " +
                    "${MirrorImport.describeTakenAt(preview.takenAtMs)}. It holds " +
                    "${preview.incomingSegments} play(s) and " +
                    "${preview.totalFacts} record(s) in all.\n\n" +
                    "They are added to this copy; nothing here is replaced or removed, and " +
                    "anything this copy already has is skipped. The scores are worked out " +
                    "again afterwards from the facts.\n\n" +
                    "This device stays a mirror: it still records nothing and still will " +
                    "not write to Spotify.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Add them") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
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
