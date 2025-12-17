package com.difft.android.chat

import difft.android.messageserialization.For
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.difft.app.database.models.DBMessageModel

abstract class BaseChatPaginationController(
    forWhat: For,
) :
    IChatPaginationController {
    protected lateinit var coroutineScope: CoroutineScope
    protected val _chatMessagesStateFlow = MutableStateFlow<ChatMessageListBehavior?>(null)
    override val chatMessagesStateFlow = _chatMessagesStateFlow.asStateFlow()
    override fun bindCoroutineScope(coroutineScope: CoroutineScope) {
        this.coroutineScope = coroutineScope
    }
    protected val commonMessageQueryCondition =
        DBMessageModel.roomType.eq(forWhat.typeValue).and(DBMessageModel.roomId.eq(forWhat.id))
}