package com.difft.android.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow









abstract class BaseChatPaginationController : IChatPaginationController {
    protected lateinit var coroutineScope: CoroutineScope
    protected val _chatMessagesStateFlow = MutableStateFlow<ChatMessageListBehavior?>(null)
    override val chatMessagesStateFlow = _chatMessagesStateFlow.asStateFlow()
    override fun bindCoroutineScope(coroutineScope: CoroutineScope) {
        this.coroutineScope = coroutineScope
    }
}
