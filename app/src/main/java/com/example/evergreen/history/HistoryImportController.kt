package com.example.evergreen.history

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.evergreen.data.EvergreenDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream

/**
 * Runs the import outside the UI's lifetime and reports progress.
 *
 * A singleton with its own scope rather than a coroutine launched from the
 * composable: importing six years of listening takes a while on the NW-A307,
 * and tying it to the screen would mean a rotation, or the user glancing at
 * another app, silently killing it half way. The import is safe to interrupt —
 * the file hash is only recorded at the end — but it should not be interrupted
 * by accident.
 *
 * Not a `WorkManager` job, because this is a deliberate one-off the user
 * watches happen, rather than something to schedule and forget.
 */
object HistoryImportController {

    private const val TAG = "HistoryImport"

    sealed interface State {
        data object Idle : State
        data class Running(val progress: ImportResult) : State
        data class Done(val result: ImportResult) : State

        /** A reason the user can act on, never a stack trace or a row of the file. */
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Starts an import, unless one is already running. */
    fun start(context: Context, uri: Uri) {
        if (_state.value is State.Running) return
        _state.value = State.Running(ImportResult())

        val appContext = context.applicationContext
        scope.launch {
            try {
                val importer = HistoryImporter(EvergreenDatabase.get(appContext))
                val openStream: () -> InputStream = {
                    appContext.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open the file you picked.")
                }

                // Sniffed from the bytes, not from the name or the MIME type:
                // pickers report both inconsistently across Android versions
                // and file managers.
                val header = openStream().use { stream ->
                    ByteArray(4).also { buffer -> stream.read(buffer) }
                }

                val result = if (HistoryFiles.looksLikeZip(header)) {
                    importer.importZip(openStream) { progress ->
                        _state.value = State.Running(progress)
                    }
                } else {
                    importer.importJsonFile(openStream) { progress ->
                        _state.value = State.Running(progress)
                    }
                }
                _state.value = State.Done(result)
                // Counts only. Never a row of the export.
                Log.i(TAG, "import finished: ${result.summaryLine()}")
            } catch (failure: HistoryImporter.NoHistoryFilesException) {
                _state.value = State.Failed(failure.message.orEmpty())
            } catch (failure: IOException) {
                // The message may name the file, which is the user's own
                // filename and is safe to show. It never contains file content.
                _state.value = State.Failed(
                    "Could not read that file. It should be the Spotify export zip, or one of " +
                        "the Streaming_History_Audio_*.json files inside it. (${failure.message})",
                )
            } catch (failure: Exception) {
                Log.w(TAG, "import failed", failure)
                _state.value = State.Failed("Import failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Clears a finished or failed result so the card goes back to its resting state. */
    fun dismiss() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }
}
