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
 * Drives a restore, and outlives the screen that started it.
 *
 * The same shape as [MirrorImportController] and for the same reasons: glancing
 * at another app half way through must not kill the step that replaces the
 * database file, and the two halves are separate so that the confirmation can
 * sit between them and name both sides.
 *
 * A separate object rather than a mode on the mirror controller, because the
 * two hold different staged files and mean opposite things, and one state
 * machine answering to both would make "yes" ambiguous at exactly the moment it
 * must not be.
 */
object DatabaseRestoreController {

    private const val TAG = "DatabaseRestore"

    sealed interface State {
        data object Idle : State

        /** Copying and checking the chosen file. Nothing has been replaced. */
        data object Checking : State

        /** Checked, and waiting for the user to say yes. */
        data class Confirming(val preview: DatabaseRestore.Preview) : State

        /** Replacing the database. */
        data object Restoring : State

        /** Done. The UI restarts itself when it sees this. */
        data class Done(val preview: DatabaseRestore.Preview) : State

        /** A reason the user can act on, never a stack trace or a row. */
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Copies and checks the file. Replaces nothing. */
    fun pick(context: Context, uri: Uri) {
        if (_state.value is State.Checking || _state.value is State.Restoring) return
        _state.value = State.Checking
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                State.Confirming(DatabaseRestore.stage(appContext, uri))
            } catch (refusal: DatabaseRestore.Refused) {
                State.Failed(refusal.message.orEmpty())
            } catch (failure: Exception) {
                Log.w(TAG, "staging failed", failure)
                State.Failed("Could not read that file (${failure.javaClass.simpleName}).")
            }
        }
    }

    /** The destructive half. Only reachable from [State.Confirming]. */
    fun confirm(context: Context) {
        val preview = (_state.value as? State.Confirming)?.preview ?: return
        _state.value = State.Restoring
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                DatabaseRestore.apply(appContext, System.currentTimeMillis(), preview)
                State.Done(preview)
            } catch (refusal: DatabaseRestore.Refused) {
                State.Failed(refusal.message.orEmpty())
            } catch (failure: Exception) {
                Log.w(TAG, "restore failed", failure)
                State.Failed("The restore failed (${failure.javaClass.simpleName}).")
            }
        }
    }

    /** Says no to a staged copy, or clears a finished or failed one. */
    fun dismiss(context: Context) {
        if (_state.value is State.Checking || _state.value is State.Restoring) return
        DatabaseRestore.discard(context)
        _state.value = State.Idle
    }
}
