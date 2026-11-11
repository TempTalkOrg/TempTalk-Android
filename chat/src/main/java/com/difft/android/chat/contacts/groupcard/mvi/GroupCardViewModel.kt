package com.difft.android.chat.contacts.groupcard.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.chat.contacts.CardPreloads
import com.difft.android.chat.contacts.CountdownTicker
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Action
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Effect
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Intent
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.State
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class GroupCardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dataSource: GroupCardDataSource,
    private val countdownTicker: CountdownTicker,
    preloads: CardPreloads,
) : ViewModel() {
    private val _state = MutableStateFlow(savedStateHandle.get<String>(ARG_GROUP_ID).orEmpty().let { gid ->
        
        preloads.take<GroupCardDataSource.Snapshot>(savedStateHandle.remove<String>(ARG_PRELOAD_KEY))
            ?.let { mapGroupCardState(gid, it, ServerTimeProvider.nowMillis()) } ?: State(groupId = gid)
    })
    val state = _state.asStateFlow()
    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()
    private val loads = Mutex()

    init {
        viewModelScope.launch { load(refresh = false) }
        viewModelScope.launch { dataSource.changes(_state.value.groupId).collect { load(refresh = false) } }
        
        viewModelScope.launch { countdownTicker.dayChanges.collect { load(refresh = false) } }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.Refresh -> viewModelScope.launch { load(refresh = true) }
            is Intent.Open -> viewModelScope.launch {
                
                loads.withLock {
                    read(refresh = false)
                    val state = _state.value
                    if (!state.actionsEnabled) return@withLock
                    
                    val effect = when {
                        intent.action == Action.INVITE && !state.inviteEnabled -> Effect.CannotInvite
                        intent.action != Action.CALL -> Effect.Navigate(state.groupId, intent.action)
                        !state.callAllowed -> Effect.CannotSpeak
                        else -> Effect.Call(state.groupId, state.name)
                    }
                    _effect.send(effect)
                }
            }
        }
    }

    private suspend fun load(refresh: Boolean) = loads.withLock { read(refresh) }

    private suspend fun read(refresh: Boolean) {
        try {
            val gid = _state.value.groupId
            val snapshot = dataSource.snapshot(gid, refresh)
            _state.update { mapGroupCardState(gid, snapshot, ServerTimeProvider.nowMillis()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[GroupCard] load_failed gid=${_state.value.groupId} error=${e.stackTraceToString()}" }
            _state.update { it.copy(actionsEnabled = false, inviteEnabled = false, callAllowed = false, loading = false) }
        }
    }

    companion object {
        const val ARG_GROUP_ID = "group_card_gid"
        const val ARG_PRELOAD_KEY = "group_card_preload"
    }
}
