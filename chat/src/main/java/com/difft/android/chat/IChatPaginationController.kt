package com.difft.android.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.difft.app.database.models.MessageModel

interface IChatPaginationController {
    val chatMessagesStateFlow: StateFlow<ChatMessageListBehavior?>
    fun bindCoroutineScope(coroutineScope: CoroutineScope)
    suspend fun initLoadMessage(jumpMessageTimeStamp: Long?)
    suspend fun loadPreviousPage(): Boolean
    suspend fun loadNextPage(): Boolean
    suspend fun jumpToMessage(messageTimeStamp: Long): Boolean
    suspend fun jumpToBottom()
    fun addOneMessage(messageModel: MessageModel)
}

/**
 * 滚动动作 - 与数据绑定，确保滚动时数据已就绪
 * null 表示不强制滚动，由 Fragment 根据 isAtBottom 状态自行判断
 */
sealed class ScrollAction {
    /** 滚动到指定位置（用于初始化加载时滚动到 readPosition） */
    data class ToPosition(val position: Int) : ScrollAction()

    /** 滚动到指定消息（用于搜索结果跳转、点击引用消息跳转） */
    data class ToMessage(val messageTimeStamp: Long) : ScrollAction()

    /** 滚动到底部（用于 jumpToBottom、发送消息后） */
    data object ToBottom : ScrollAction()
}

data class ChatMessageListBehavior(
    val messageList: List<MessageModel> = emptyList(),
    val scrollAction: ScrollAction? = null, // null 表示不强制滚动
    val updateTimestamp: Long = System.currentTimeMillis(),
    val anchorMessageBefore: MessageModel? = null, // 用于计算第一条消息显示逻辑的锚点消息（不显示）
    val anchorMessageAfter: MessageModel? = null, // 用于计算最后一条消息显示逻辑的锚点消息（不显示）
    val readPosition: Long? = null, // 用于显示新消息分割线的已读位置，仅在初始化加载时传递
)