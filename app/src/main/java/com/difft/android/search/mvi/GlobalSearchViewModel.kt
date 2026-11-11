package com.difft.android.search.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.search.GlobalSearchSource
import com.difft.android.search.mvi.GlobalSearchContract.Intent
import com.difft.android.search.mvi.GlobalSearchContract.Scope
import com.difft.android.search.mvi.GlobalSearchContract.State
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject








@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val source: GlobalSearchSource,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(
        State(scope = savedStateHandle.get<String>(KEY_SCOPE)?.let { name -> Scope.entries.firstOrNull { it.name == name } } ?: Scope.ALL).withRows()
    )
    val state: StateFlow<State> = _state

    private var debounceJob: Job? = null
    private var searchJob: Job? = null

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.QueryChanged -> scheduleSearch(intent.raw)
            Intent.Clear -> scheduleSearch("")
            is Intent.SelectScope -> selectScope(intent.scope)
        }
    }

    
    private fun selectScope(scope: Scope) {
        savedStateHandle[KEY_SCOPE] = scope.name
        _state.update { it.copy(scope = scope).withRows() }
    }

    private fun scheduleSearch(raw: String) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val key = raw.trim()
            if (key == _state.value.query) return@launch
            searchJob?.cancel()
            if (key.isEmpty()) {
                _state.update { State(scope = it.scope).withRows() }
                return@launch
            }
            
            _state.update { it.copy(query = key, searching = true).withRows() }
            
            
            searchJob = viewModelScope.launch { searchAll(key) }
        }
    }

    
    private suspend fun searchAll(key: String) = coroutineScope {
        var pending = LIST_COUNT
        fun answered(mutate: State.() -> State) {
            pending--
            _state.update { it.mutate().copy(searching = pending > 0).withRows() }
        }
        launch { val contacts = source.contacts(key, RESULT_LIMIT); answered { copy(contacts = contacts) } }
        launch { val groups = source.groups(key, RESULT_LIMIT); answered { copy(groups = groups) } }
        launch { val history = source.chatHistory(key); answered { copy(history = history) } }
    }

    private fun State.withRows(): State = copy(rows = buildSearchRows(this, RESULT_LIMIT))

    companion object {
        
        const val DEBOUNCE_MS = 150L

        
        const val RESULT_LIMIT = 25

        private const val LIST_COUNT = 3
        private const val KEY_SCOPE = "global_search_scope"
    }
}
