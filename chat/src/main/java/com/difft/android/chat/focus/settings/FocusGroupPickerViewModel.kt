package com.difft.android.chat.focus.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.FocusGroupItem
import com.difft.android.chat.focus.GroupFocusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object FocusGroupPickerContract {
    sealed interface Intent {
        data object Load : Intent
        data class QueryChanged(val query: String) : Intent
        data class Select(val gid: String) : Intent
        data object Done : Intent
        data object Cancel : Intent
    }

    data class State(
        val loading: Boolean = true,
        /** Groups matching the search, in name order. */
        val groups: List<FocusGroupItem> = emptyList(),
        val query: String = "",
        val selectedGid: String? = null,
        /** "Done" is enabled once a group other than the current focus group is selected. */
        val canConfirm: Boolean = false,
    )

    sealed interface Effect {
        /** Close the picker; [gid] is the chosen group, null when cancelled. */
        data class Finish(val gid: String?) : Effect
    }
}

/**
 * Single-select list of the active groups (status 0) with a name search. While a valid focus runs, its
 * group starts selected (switching from Me > Notifications), and choosing it again is not a change.
 */
@HiltViewModel
class FocusGroupPickerViewModel @Inject constructor(
    private val directory: FocusGroupDirectory,
    private val repository: GroupFocusRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FocusGroupPickerContract.State())
    val state: StateFlow<FocusGroupPickerContract.State> = _state.asStateFlow()

    private val _effect = Channel<FocusGroupPickerContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    /** The full list; the state holds the filtered view of it. */
    private val allGroups = MutableStateFlow<List<FocusGroupItem>>(emptyList())

    /** The valid focus group right now (followed while the picker is open); null when focus is off or invalid. */
    private var currentGid: String? = null
    private var observingFocus = false

    fun dispatch(intent: FocusGroupPickerContract.Intent) {
        when (intent) {
            FocusGroupPickerContract.Intent.Load -> load()
            is FocusGroupPickerContract.Intent.QueryChanged -> {
                _state.update { it.copy(query = intent.query, groups = filter(allGroups.value, intent.query)) }
            }

            is FocusGroupPickerContract.Intent.Select -> _state.update { it.copy(selectedGid = intent.gid, canConfirm = canConfirm(intent.gid)) }
            FocusGroupPickerContract.Intent.Done -> _state.value.takeIf { it.canConfirm }?.selectedGid?.let {
                _effect.trySend(FocusGroupPickerContract.Effect.Finish(it))
            }

            FocusGroupPickerContract.Intent.Cancel -> _effect.trySend(FocusGroupPickerContract.Effect.Finish(null))
        }
    }

    private fun load() {
        currentGid = validFocusGid(repository.effectiveNow())
        if (_state.value.selectedGid == null) _state.update { it.copy(selectedGid = currentGid) }
        if (!observingFocus) {
            observingFocus = true
            // Focus can end or move while the picker is open: the preselected group may become choosable.
            viewModelScope.launch {
                repository.effective.collect { focus ->
                    currentGid = validFocusGid(focus)
                    _state.update { it.copy(canConfirm = canConfirm(it.selectedGid)) }
                }
            }
        }
        viewModelScope.launch {
            val groups = directory.activeGroups()
            allGroups.value = groups
            _state.update { it.copy(loading = false, groups = filter(groups, it.query)) }
        }
    }

    private fun validFocusGid(focus: EffectiveFocus): String? = focus.focusGid?.takeIf { focus.isFocusedGroup(it) }

    private fun canConfirm(selected: String?): Boolean = selected != null && selected != currentGid

    private fun filter(groups: List<FocusGroupItem>, query: String): List<FocusGroupItem> {
        val needle = query.trim()
        return if (needle.isEmpty()) groups else groups.filter { it.name.contains(needle, ignoreCase = true) }
    }
}
