package com.difft.android.chat.focus.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.chat.focus.GroupNotifyPolicy.MuteOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

object MuteExceptionsContract {
    sealed interface Intent {
        /** Fed by the host whenever the conversation config changes. */
        data class ConversationChanged(val options: MuteOptions) : Intent
        data class Toggle(val item: StillNotifyItem, val on: Boolean) : Intent

        /** The host's conversation write for [item] ended (accepted or not). */
        data class WriteFinished(val item: StillNotifyItem) : Intent
    }

    data class State(val items: StillNotifyState = StillNotifyState())

    sealed interface Effect {
        /** Write [item] through the conversation config path, then report [Intent.WriteFinished]. */
        data class Write(val item: StillNotifyItem, val on: Boolean) : Effect
    }
}

/**
 * "Still Notify" (a muted group's exceptions): three switches writing the conversation config at
 * once. A switch shows its requested value while the write is in flight and falls back to the
 * stored value when the write did not change it (failure).
 */
@HiltViewModel
class MuteExceptionsViewModel @Inject constructor() : ViewModel() {

    private val options = MutableStateFlow(MuteOptions())
    private val pending = MutableStateFlow<Map<StillNotifyItem, Boolean>>(emptyMap())

    val state: StateFlow<MuteExceptionsContract.State> = combine(options, pending) { stored, inFlight ->
        val server = StillNotifyState(stored.mentionMe, stored.mentionAll, stored.groupCall)
        MuteExceptionsContract.State(inFlight.entries.fold(server) { acc, (item, on) -> acc.with(item, on) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MuteExceptionsContract.State())

    private val _effect = Channel<MuteExceptionsContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    fun dispatch(intent: MuteExceptionsContract.Intent) {
        when (intent) {
            is MuteExceptionsContract.Intent.ConversationChanged -> options.value = intent.options
            is MuteExceptionsContract.Intent.Toggle -> {
                if (intent.item in pending.value) return
                pending.update { it + (intent.item to intent.on) }
                _effect.trySend(MuteExceptionsContract.Effect.Write(intent.item, intent.on))
            }

            is MuteExceptionsContract.Intent.WriteFinished -> pending.update { it - intent.item }
        }
    }
}
