package com.difft.android.chat.focus.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.focus.FocusResult
import com.difft.android.chat.focus.GroupFocusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object FocusOthersNotifyContract {
    sealed interface Intent {
        data class Toggle(val item: StillNotifyItem, val on: Boolean) : Intent
    }

    data class State(val items: StillNotifyState = StillNotifyState())

    sealed interface Effect {
        data object ShowError : Effect
    }
}





@HiltViewModel
class FocusOthersNotifyViewModel @Inject constructor(
    private val repository: GroupFocusRepository,
) : ViewModel() {

    
    private val pending = MutableStateFlow<Map<StillNotifyItem, Boolean>>(emptyMap())

    val state: StateFlow<FocusOthersNotifyContract.State> = combine(repository.effective, pending) { focus, inFlight ->
        val server = StillNotifyState(focus.othersAllowMentionMe, focus.othersAllowMentionAll, focus.othersAllowGroupCall)
        FocusOthersNotifyContract.State(inFlight.entries.fold(server) { acc, (item, on) -> acc.with(item, on) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FocusOthersNotifyContract.State())

    private val _effect = Channel<FocusOthersNotifyContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    fun dispatch(intent: FocusOthersNotifyContract.Intent) {
        when (intent) {
            is FocusOthersNotifyContract.Intent.Toggle -> toggle(intent.item, intent.on)
        }
    }

    private fun toggle(item: StillNotifyItem, on: Boolean) {
        if (item in pending.value) return
        pending.update { it + (item to on) }
        viewModelScope.launch {
            try {
                val result = repository.updateOptions(
                    othersAllowMentionMe = on.takeIf { item == StillNotifyItem.MENTION_ME },
                    othersAllowMentionAll = on.takeIf { item == StillNotifyItem.MENTION_ALL },
                    othersAllowGroupCall = on.takeIf { item == StillNotifyItem.GROUP_CALL },
                )
                if (result is FocusResult.Failure) {
                    L.w { "[FocusSettings] others option update failed item=$item error=${result.error}" }
                    _effect.trySend(FocusOthersNotifyContract.Effect.ShowError)
                }
            } finally {
                pending.update { it - item }
            }
        }
    }
}
