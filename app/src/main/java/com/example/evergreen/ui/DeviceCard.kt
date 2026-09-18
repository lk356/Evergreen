package com.example.evergreen.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.example.evergreen.data.DatabaseBackup
import com.example.evergreen.data.EvergreenDatabase
import kotlinx.coroutines.launch

/**
 * The two per-device settings: whether this device writes Evergreen, and a
 * button to copy the database somewhere safe.
 *
 * Shown above the import, deliberately. The reading order on the screen is the
 * order the user should act in: decide whether this is the writer device, take
 * a backup, then import.
 */
@Composable
fun DeviceCard() {
    val context = LocalContext.current
    val importDao = remember(context) { EvergreenDatabase.get(context).importDao() }
    val scope = rememberCoroutineScope()

    val writerRow by importDao.evergreenWriterFlow().collectAsState(initial = null)
    val isWriter = writerRow?.valueText == "true"

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
        Switch(
            checked = isWriter,
            onCheckedChange = { enabled -> scope.launch { importDao.setEvergreenWriter(enabled) } },
        )
    }
    Text(
        "Turn this on for exactly one device — the one you listen on. Until the " +
            "sync backend arrives, each device has its own database, so two devices " +
            "running the Evergreen sync would each undo the other's writes every day, " +
            "against a quota neither can see. The import should also be run only here.",
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
}
