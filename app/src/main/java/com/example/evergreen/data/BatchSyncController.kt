package com.example.evergreen.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives a delta **export**, and outlives the screen that started it.
 *
 * The same shape as the other three controllers in this package. The first
 * export on the Walkman is 77,000 segments through a text format on a
 * low-powered device, so "glancing at another app must not kill it" is not
 * theoretical here.
 */
object BatchExportController {

    private const val TAG = "BatchExport"

    sealed interface State {
        data object Idle : State

        /** Counting what there is to send. Nothing has been written. */
        data object Planning : State

        /** Counted, and waiting for the user to pick a file. */
        data class Ready(val plan: BatchExport.Plan) : State

        data object Writing : State

        data class Done(val written: BatchExport.Written) : State

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Works out what would be sent. Writes nothing. */
    fun plan(context: Context, fromTheBeginning: Boolean) {
        if (_state.value is State.Planning || _state.value is State.Writing) return
        _state.value = State.Planning
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                State.Ready(BatchExport.plan(appContext, fromTheBeginning))
            } catch (failure: Exception) {
                Log.w(TAG, "planning failed", failure)
                State.Failed("Could not work out what to send (${failure.javaClass.simpleName}).")
            }
        }
    }

    /** Writes the planned batch to the file the picker returned. */
    fun writeTo(context: Context, uri: Uri) {
        val plan = (_state.value as? State.Ready)?.plan ?: return
        _state.value = State.Writing
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                State.Done(BatchExport.writeTo(appContext, uri, plan))
            } catch (refusal: BatchExport.Refused) {
                State.Failed(refusal.message.orEmpty())
            } catch (failure: Exception) {
                Log.w(TAG, "export failed", failure)
                State.Failed("The export failed (${failure.javaClass.simpleName}).")
            }
        }
    }

    fun dismiss() {
        if (_state.value is State.Planning || _state.value is State.Writing) return
        _state.value = State.Idle
    }
}

/**
 * Drives a delta **import**, and outlives the screen that started it.
 *
 * Two steps, like every other operation here that changes the database: [pick]
 * reads the header and checks it against what this device holds, and changes
 * nothing; [confirm] applies the file. The confirmation sits between them,
 * which is what lets it name both sides.
 *
 * Unlike the mirror import, this does **not** restart the UI when it finishes.
 * Nothing here closes the database handle — a delta is a transaction against
 * the live database, not a file swap — so every screen is already reading the
 * new rows through the flows it was already collecting.
 */
object BatchImportController {

    private const val TAG = "BatchImport"

    sealed interface State {
        data object Idle : State

        data object Checking : State

        data class Confirming(val preview: BatchImport.Preview, val uri: Uri) : State

        data object Applying : State

        data class Done(val applied: BatchImport.Applied) : State

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun pick(context: Context, uri: Uri) {
        if (_state.value is State.Checking || _state.value is State.Applying) return
        _state.value = State.Checking
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                State.Confirming(BatchImport.preview(appContext, uri), uri)
            } catch (refusal: BatchImport.Refused) {
                State.Failed(refusal.message.orEmpty())
            } catch (failure: Exception) {
                Log.w(TAG, "preview failed", failure)
                State.Failed("Could not read that file (${failure.javaClass.simpleName}).")
            }
        }
    }

    fun confirm(context: Context) {
        val confirming = _state.value as? State.Confirming ?: return
        _state.value = State.Applying
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                State.Done(
                    BatchImport.apply(appContext, confirming.uri, confirming.preview),
                )
            } catch (refusal: BatchImport.Refused) {
                State.Failed(refusal.message.orEmpty())
            } catch (failure: Exception) {
                Log.w(TAG, "import failed", failure)
                State.Failed("The import failed (${failure.javaClass.simpleName}).")
            }
        }
    }

    fun dismiss() {
        if (_state.value is State.Checking || _state.value is State.Applying) return
        _state.value = State.Idle
    }
}
