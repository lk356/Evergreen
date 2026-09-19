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
 * Drives a mirror import, and outlives the screen that started it.
 *
 * The same shape as [com.example.evergreen.history.HistoryImportController],
 * and for the same reason: a deliberate one-off the user watches happen, not
 * something to schedule and forget. It is a singleton with its own scope so
 * that glancing at another app half way through cannot kill it — and here that
 * matters more than it does for the streaming-history import, because the step
 * that matters replaces the database file.
 *
 * The two steps are separate on purpose. [pick] copies and checks the file and
 * changes nothing; [confirm] is the destructive half. The confirmation dialog
 * sits between them, which is what lets it name both what is arriving and what
 * it is about to replace.
 */
object MirrorImportController {

    private const val TAG = "MirrorImport"

    sealed interface State {
        data object Idle : State

        /** Copying and checking the chosen file. Nothing has been replaced. */
        data object Checking : State

        /** Checked, and waiting for the user to say yes. */
        data class Confirming(val preview: MirrorImport.Preview) : State

        /** Replacing the database. */
        data object Importing : State

        /**
         * Done. The UI restarts itself when it sees this.
         *
         * Everything holding a handle to the old database — every composable
         * that captured it, every flow collecting from it — is now holding a
         * closed one. Recreating the activity is the honest way to get them
         * all back on the new file at once, and it takes a moment on a device
         * that has just replaced its whole database anyway.
         */
        data class Done(val preview: MirrorImport.Preview) : State

        /** A reason the user can act on, never a stack trace or a row. */
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Copies and checks the file. Replaces nothing. */
    fun pick(context: Context, uri: Uri) {
        if (_state.value is State.Checking || _state.value is State.Importing) return
        _state.value = State.Checking
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                State.Confirming(MirrorImport.stage(appContext, uri))
            } catch (refusal: MirrorImport.Refused) {
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
        _state.value = State.Importing
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                MirrorImport.apply(appContext, System.currentTimeMillis(), preview)
                State.Done(preview)
            } catch (refusal: MirrorImport.Refused) {
                State.Failed(refusal.message.orEmpty())
            } catch (failure: Exception) {
                Log.w(TAG, "import failed", failure)
                State.Failed("The import failed (${failure.javaClass.simpleName}).")
            }
        }
    }

    /** Says no to a staged copy, or clears a finished or failed one. */
    fun dismiss(context: Context) {
        if (_state.value is State.Checking || _state.value is State.Importing) return
        MirrorImport.discard(context)
        _state.value = State.Idle
    }
}
