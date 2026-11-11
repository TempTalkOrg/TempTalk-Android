package com.difft.android.chat.contacts.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.contacts.CountdownTicker
import com.difft.android.chat.contacts.data.ContactsRepository
import com.difft.android.chat.contacts.data.ContactsTabPreferences
import com.difft.android.chat.contacts.data.assembleContactsState
import com.difft.android.chat.contacts.data.initialDirectoryTab
import com.difft.android.chat.contacts.data.selectDirectoryTab
import com.difft.android.chat.contacts.mvi.ContactsContract.Effect
import com.difft.android.chat.contacts.mvi.ContactsContract.Intent
import com.difft.android.chat.contacts.mvi.ContactsContract.State
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
class ContactsViewModel @Inject constructor(
    private val repository: ContactsRepository,
    private val tabPreferences: ContactsTabPreferences,
    private val countdownTicker: CountdownTicker,
) : ViewModel() {
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()
    private val loads = Mutex()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { repository.changes().collect { load() } }
        viewModelScope.launch { countdownTicker.dayChanges.collect { load() } }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.Refresh -> viewModelScope.launch { load() }
            Intent.PullRefresh -> pullRefresh()
            is Intent.SelectTab -> {
                _state.update { selectDirectoryTab(it, intent.tab).copy(hasUserSelectedTab = true) }
                viewModelScope.launch { loads.withLock {
                    try {
                        tabPreferences.save(intent.tab)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.e { "[Contacts] tab_save_failed error=${e.stackTraceToString()}" }
                    }
                } }
            }
            is Intent.SelectId -> _state.update { it.copy(selectedId = intent.id) }
            is Intent.OpenContact -> emit(Effect.OpenContact(intent.id))
            is Intent.OpenGroup -> emit(Effect.OpenGroup(intent.id))
            Intent.AddContact -> emit(Effect.AddContact)
        }
    }

    
    private fun pullRefresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                repository.refreshAll()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[Contacts] pull_refresh_failed error=${e.stackTraceToString()}" }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun emit(effect: Effect) {
        viewModelScope.launch { _effect.send(effect) }
    }

    private suspend fun load(): Unit = loads.withLock {
        _state.update { it.copy(loading = true) }
        try {
            val snapshot = repository.snapshot()
            val saved = if (_state.value.initialized || _state.value.hasUserSelectedTab) _state.value.selectedTab else tabPreferences.read()
            val tab = initialDirectoryTab(saved, snapshot.groups.any { it.status == 0 && !it.gid.isNullOrEmpty() })
            
            _state.update { assembleContactsState(snapshot, it, if (it.hasUserSelectedTab) it.selectedTab else tab, ServerTimeProvider.nowMillis()) }
            
            if (snapshot.unresolvedWeakIds.isNotEmpty()) viewModelScope.launch {
                if (repository.backfillWeakContacts(snapshot.unresolvedWeakIds)) load()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[Contacts] load_failed error=${e.stackTraceToString()}" }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }
}
