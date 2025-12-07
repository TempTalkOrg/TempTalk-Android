package com.difft.android.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.difft.app.database.models.MessageModel

interface IChatPaginationController {
    val chatMessagesStateFlow: StateFlow<ChatMessageListBehavior>
    fun bindCoroutineScope(coroutineScope: CoroutineScope)
    suspend fun initLoadMessage(jumpMessageTimeStamp: Long?)
    suspend fun loadPreviousPage(): Boolean
    suspend fun loadNextPage(): Boolean
    suspend fun jumpToMessage(messageTimeStamp: Long): Boolean
    suspend fun jumpToBottom()
    fun addOneMessage(messageModel: MessageModel)
}

data class ChatMessageListBehavior(
    val messageList: List<MessageModel> = emptyList(),
    val scrollToPosition: Int = -1, //-1 indicator not to scroll
    val stateTriggeredByUser: Boolean = true, //means if the state is triggered by user action
    val updateTimestamp: Long = System.currentTimeMillis(), // 添加时间戳字段强制更新
    val anchorMessageBefore: MessageModel? = null, // 用于计算第一条消息显示逻辑的锚点消息（不显示）
    val anchorMessageAfter: MessageModel? = null, // 用于计算最后一条消息显示逻辑的锚点消息（不显示）
    val readPosition: Long? = null, // 用于显示新消息分割线的已读位置，仅在初始化加载时传递
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChatMessageListBehavior

        if (messageList != other.messageList) return false
        if (scrollToPosition != other.scrollToPosition) return false
        if (stateTriggeredByUser != other.stateTriggeredByUser) return false
        if (updateTimestamp != other.updateTimestamp) return false
        if (anchorMessageBefore != other.anchorMessageBefore) return false
        if (anchorMessageAfter != other.anchorMessageAfter) return false
        if (readPosition != other.readPosition) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageList.hashCode()
        result = 31 * result + scrollToPosition
        result = 31 * result + stateTriggeredByUser.hashCode()
        result = 31 * result + updateTimestamp.hashCode()
        result = 31 * result + (anchorMessageBefore?.hashCode() ?: 0)
        result = 31 * result + (anchorMessageAfter?.hashCode() ?: 0)
        result = 31 * result + (readPosition?.hashCode() ?: 0)
        return result
    }
}