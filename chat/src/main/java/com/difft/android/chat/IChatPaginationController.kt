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

    /**
     * Drops the oldest rows so the loaded window is back at its cap. Pure in-memory re-slice, zero
     * queries; a no-op when the window is already at or under the cap.
     *
     * The caller MUST have established that the viewport sits at the bottom — this function has no
     * viewport knowledge of its own. See the implementation's KDoc for the invariant that makes
     * that check meaningful.
     */
    suspend fun trimToLatest()
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

    /** Keep current viewport for user-driven pagination; never auto-snap. */
    data object PreservePosition : ScrollAction()
}

data class ChatMessageListBehavior(
    val messageList: List<MessageModel> = emptyList(),
    val scrollAction: ScrollAction? = null, // null 表示不强制滚动
    val updateTimestamp: Long = System.currentTimeMillis(),
    val anchorMessageBefore: MessageModel? = null, // 用于计算第一条消息显示逻辑的锚点消息（不显示）
    val anchorMessageAfter: MessageModel? = null, // 用于计算最后一条消息显示逻辑的锚点消息（不显示）
    // Divider anchor, carried ONLY by the first-screen loads. Later emissions leave it null on
    // purpose: ChatMessageViewModel captures the first value for the whole page session, so a null
    // here means "no new anchor", NOT "hide the divider".
    val readPosition: Long? = null,
    /** True when [messageList]'s oldest loaded message is the conversation's actual first
     * message. Default false is a safe fallback: any emission site that forgets to set it
     * simply never shows the header, it cannot show a wrong header. */
    val hasReachedHistoryStart: Boolean = false,
    /**
     * True when [messageList]'s newest loaded message is the conversation's newest message, i.e.
     * there is nothing left to load at the bottom edge.
     *
     * Symmetric counterpart of [hasReachedHistoryStart], and derived at every emission site with
     * ZERO extra queries. Default false is the safe fallback in the same sense: a site that forgets
     * to set it merely fails to suppress a redundant page load (today's behaviour), it cannot
     * suppress a needed one. The one site where a naive carry-forward WOULD wrongly suppress is
     * `loadPreviousPage` — see its comment.
     */
    val hasReachedLatest: Boolean = false,
)

/**
 * The window plus its two invisible layout anchors — the single definition of "which messages this
 * emission needs child-table data for".
 *
 * Sole owner of that set: the ViewModel derives the hydration id set AND its three
 * `generateMessageTwo` call groups from this one list, so an anchor can never be decorated from
 * empty sub-data while the window rows around it are hydrated. Anchors are never rendered, but they
 * drive the first/last row's day-header, name and time decisions, which read their sub-data.
 */
fun ChatMessageListBehavior.messagesToConvert(): List<MessageModel> =
    listOfNotNull(anchorMessageBefore) + messageList + listOfNotNull(anchorMessageAfter)