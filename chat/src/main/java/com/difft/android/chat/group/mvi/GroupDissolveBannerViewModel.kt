package com.difft.android.chat.group.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.contacts.CountdownTicker
import com.difft.android.chat.group.GroupChatFragment
import com.difft.android.chat.group.GroupDissolveDismissals
import com.difft.android.chat.group.groupDissolveDaysLeft
import com.difft.android.chat.group.mvi.GroupDissolveBannerContract.Intent
import com.difft.android.chat.group.mvi.GroupDissolveBannerContract.State
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class GroupDissolveBannerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dismissals: GroupDissolveDismissals,
    countdownTicker: CountdownTicker,
) : ViewModel() {
    private val _state = MutableStateFlow(
        State(groupId = savedStateHandle[GroupChatFragment.ARG_GROUP_ID] ?: "").let {
            it.copy(dismissed = it.groupId in dismissals.groupIds.value)
        }
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            dismissals.groupIds.collect { change { it } }
        }
        
        viewModelScope.launch { countdownTicker.ticks.collect { dispatch(Intent.Refresh) } }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.UpdateGroup -> change { state ->
                val group = intent.group
                when {
                    group == null -> state.copy(expireTime = null)
                    group.gid != state.groupId -> state
                    else -> state.copy(expireTime = group.expireTime.takeIf { group.status == 0 })
                }
            }
            is Intent.SetJoinCallBarVisible -> change { it.copy(joinCallBarVisible = intent.visible) }
            Intent.Refresh -> change { it }
            Intent.Dismiss -> {
                dismissals.dismiss(_state.value.groupId)
                change { it }
            }
        }
    }

    private fun change(transform: (State) -> State) {
        val now = ServerTimeProvider.nowMillis()
        _state.update { current ->
            val transformed = transform(current)
            val next = transformed.copy(dismissed = transformed.groupId in dismissals.groupIds.value)
            next.copy(daysLeft = if (shouldShowGroupDissolveBanner(
                next.expireTime, now, next.dismissed, next.joinCallBarVisible,
            )) groupDissolveDaysLeft(requireNotNull(next.expireTime), now) else null)
        }
    }
}
