package com.difft.android.chat.ui.mvi

import androidx.lifecycle.ViewModel
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.ui.mvi.ChatForwardMessageContract.Intent
import com.difft.android.chat.ui.mvi.ChatForwardMessageContract.State
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the forward viewer's navigation stack so a configuration change keeps the shown
 * content instead of restoring empty fragments (#1440).
 */
@HiltViewModel
class ChatForwardMessageViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Author names for titles; kept here so they survive a configuration change. */
    val contactorCache = MessageContactsCacheUtil()

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.SetRoot -> _state.update {
                it.copy(
                    stack = listOf(intent.context),
                    outerSourceConversation = intent.outerConversation,
                    outerSourceAuthorId = intent.outerAuthorId,
                )
            }
            is Intent.PushNested -> _state.update { it.copy(stack = it.stack + intent.context) }
            is Intent.TrimTo -> _state.update { it.copy(stack = it.stack.take(intent.size)) }
        }
    }
}
