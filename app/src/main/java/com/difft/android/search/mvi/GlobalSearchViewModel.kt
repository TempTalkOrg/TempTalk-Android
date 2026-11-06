package com.difft.android.search.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.search.GlobalSearchSource
import com.difft.android.search.mvi.GlobalSearchContract.Intent
import com.difft.android.search.mvi.GlobalSearchContract.State
import com.difft.android.search.mvi.GlobalSearchContract.Tips
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debounces the typed text, drops a query equal to the current one and runs the four searches
 * concurrently, publishing each list as it answers. A newer query cancels the running searches,
 * so a late answer never overwrites a newer query's lists. Query and results live here, so the
 * page keeps them across a recreation.
 */
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val source: GlobalSearchSource,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var debounceJob: Job? = null
    private var searchJob: Job? = null

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.QueryChanged -> scheduleSearch(intent.raw)
            Intent.Clear -> scheduleSearch("")
        }
    }

    private fun scheduleSearch(raw: String) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val key = raw.trim()
            if (key == _state.value.query) return@launch
            searchJob?.cancel()
            if (key.isEmpty()) {
                _state.value = State()
                return@launch
            }
            // The previous lists stay until each new answer replaces its own.
            _state.update { it.copy(query = key, searching = true) }
            // Its own job, not a child of the debounce: a keystroke that collapses to the same
            // query must not cancel the search already running for it.
            searchJob = viewModelScope.launch { searchAll(key) }
        }
    }

    /** Four concurrent lookups; every answer lands on the ViewModel's main dispatcher, so the countdown needs no lock. */
    private suspend fun searchAll(key: String) = coroutineScope {
        var pending = LIST_COUNT
        fun answered(mutate: State.() -> State) {
            pending--
            _state.update { it.mutate().copy(searching = pending > 0).withTips() }
        }
        launch { val recent = source.recentChats(key); answered { copy(recent = recent) } }
        launch { val contacts = source.contacts(key); answered { copy(contacts = contacts) } }
        launch { val groups = source.groups(key); answered { copy(groups = groups) } }
        launch { val history = source.chatHistory(key, maxRooms = HISTORY_ROOMS); answered { copy(history = history) } }
    }

    private fun State.withTips(): State = copy(
        tips = when {
            query.isEmpty() -> Tips.DEFAULT
            recent.isEmpty() && contacts.isEmpty() && groups.isEmpty() && history.isEmpty() -> Tips.NO_RESULTS
            else -> Tips.NONE
        }
    )

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val LIST_COUNT = 4
        const val HISTORY_ROOMS = 5
    }
}
