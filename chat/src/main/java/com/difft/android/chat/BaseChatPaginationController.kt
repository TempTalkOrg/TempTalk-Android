package com.difft.android.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scope + emission-state holder shared by pagination controllers.
 *
 * Holds no query condition on purpose: building a WCDB winq `Expression` here would load the
 * native WCDB library from every subclass constructor, which is what made the controller
 * unusable in host-JVM tests. Room scoping now lives in
 * `com.difft.android.chat.pagination.WcdbChatMessageWindowSource`.
 */
abstract class BaseChatPaginationController : IChatPaginationController {
    protected lateinit var coroutineScope: CoroutineScope
    protected val _chatMessagesStateFlow = MutableStateFlow<ChatMessageListBehavior?>(null)
    override val chatMessagesStateFlow = _chatMessagesStateFlow.asStateFlow()
    override fun bindCoroutineScope(coroutineScope: CoroutineScope) {
        this.coroutineScope = coroutineScope
    }
}
