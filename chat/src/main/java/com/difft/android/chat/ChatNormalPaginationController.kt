package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.qualifier.User
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.sampleAfterFirst
import com.tencent.wcdb.winq.Order
import com.tencent.wcdb.winq.ResultColumnConvertible
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.earliestFailedOutgoingMessage
import org.difft.app.database.firstUnreadFromOthersMessage
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.MessageModel

// care of  ConversationUtils.messagesUpdate.filter { it == forWhat.id }
// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
class ChatNormalPaginationController @AssistedInject constructor(
    @Assisted
    private val forWhat: For,
    private val wcdb: WCDB,
    // Comparison base for "from others". Same source as `isMine` (`Record2MessageFactory` compares
    // `globalServices.myId` with `record.fromWho`) so "from others" has ONE meaning. `UserInfoModule`
    // falls back to "" when no user data is loaded; a blank id only degrades the anchor decision
    // towards today's behavior, it cannot crash or mis-place the divider.
    @param:User.Uid private val myId: String,
) : BaseChatPaginationController(forWhat) {
    companion object {
        const val PAGE_SIZE: Long = 20L
        const val MAX_MESSAGE_COUNT = (3 * PAGE_SIZE).toInt()
    }

    override suspend fun initLoadMessage(jumpMessageTimeStamp: Long?) = withContext(Dispatchers.IO) {
        if (jumpMessageTimeStamp != null && jumpMessageTimeStamp > 0L) {
            jumpToMessage(jumpMessageTimeStamp)
            Unit
        } else {
            loadNormalChatDefaultMessages()
        }
    }

    /**
     * Entry point for opening a conversation without a jump target. Owns the anchoring decision:
     * the gate lives here and NOT in [initLoadMessage], so the `jumpToMessage` entry keeps its
     * behavior untouched.
     */
    private suspend fun loadNormalChatDefaultMessages() {
        // One row, two columns — replaces a single-column readPosition read, so the gate below adds
        // ZERO queries for the overwhelming majority of rooms (sendStatus == NONE).
        val roomRow = wcdb.room.getOneRow(
            arrayOf<ResultColumnConvertible>(DBRoomModel.readPosition, DBRoomModel.sendStatus),
            DBRoomModel.roomId.eq(forWhat.id)
        )
        val readPosition = roomRow?.getOrNull(0)?.long ?: 0L
        val roomSendStatus = roomRow?.getOrNull(1)?.int ?: ROOM_SEND_STATUS_NONE

        // `== FAILED`, not `!= NONE`: enabling the future SENDING aggregate must not start moving
        // the first screen.
        if (roomSendStatus == ROOM_SEND_STATUS_FAILED) {
            val failed = wcdb.earliestFailedOutgoingMessage(forWhat.id)
            if (failed != null) {
                // Must be this dedicated query, not expectedUnreadMessages — see
                // decideFirstScreenAnchor's KDoc for why the latter disables the anchoring.
                val firstUnreadOthersTs = wcdb.firstUnreadFromOthersMessage(
                    forWhat.id, forWhat.typeValue, readPosition, myId
                )?.systemShowTimestamp
                val anchor = decideFirstScreenAnchor(
                    firstFailedTs = failed.systemShowTimestamp,
                    firstUnreadOthersTs = firstUnreadOthersTs,
                )
                L.i {
                    "[${forWhat.id}] first screen anchor decision: failedTs=${failed.systemShowTimestamp} " +
                        "firstUnreadOthersTs=$firstUnreadOthersTs -> $anchor"
                }
                if (anchor is FirstScreenAnchor.AtFailedMessage) {
                    loadFirstScreenAnchoredAtFailure(failed, readPosition)
                    return
                }
            }
        }
        loadFirstScreenFromReadPosition(readPosition)
    }

    /** Default first screen: window built around [readPosition], divider rule untouched. */
    private suspend fun loadFirstScreenFromReadPosition(readPosition: Long) {
        // 多查询一条未读消息用作后锚点
        val expectedUnreadMessages =
            wcdb.message.getAllObjects(
                commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.gt(readPosition)),
                DBMessageModel.systemShowTimestamp.order(Order.Asc), PAGE_SIZE + 1
            )
        L.i { "[${forWhat.id}] Load normal chat default messages, expectedUnreadMessages.size = ${expectedUnreadMessages.size}" }

        val allMessages = if (expectedUnreadMessages.size < PAGE_SIZE) {
            // 如果未读消息不够一页，补充已读消息，多查一条用作前锚点
            val expectedMessages = wcdb.message.getAllObjects(
                commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.le(readPosition)),
                DBMessageModel.systemShowTimestamp.order(Order.Desc), PAGE_SIZE - expectedUnreadMessages.size + 1
            )
            L.i { "[${forWhat.id}] Load normal chat default messages, expectedReadMessages.size = ${expectedMessages.size}" }
            expectedMessages + expectedUnreadMessages
        } else {
            expectedUnreadMessages
        }

        val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }
        L.i { "[${forWhat.id}] Load normal chat default messages, sortedMessages.size = ${sortedMessages.size}" }

        // 拆分锚点消息和显示消息
        val window = splitMessageWindow(sortedMessages, expectedUnreadMessages.size, PAGE_SIZE.toInt())
        val pageMessages = window.pageMessages

        // 计算初始滚动位置
        val scrollToPosition = if (expectedUnreadMessages.isNotEmpty()) {
            val firstUnreadInPage = expectedUnreadMessages.firstOrNull { it in pageMessages }
            if (firstUnreadInPage != null) {
                pageMessages.indexOfFirst { it.id == firstUnreadInPage.id }
            } else if (pageMessages.isNotEmpty()) {
                pageMessages.size - 1
            } else {
                -1
            }
        } else if (pageMessages.isNotEmpty()) {
            pageMessages.size - 1
        } else {
            -1
        }

        _chatMessagesStateFlow.value = ChatMessageListBehavior(
            messageList = pageMessages,
            scrollAction = if (scrollToPosition >= 0) ScrollAction.ToPosition(scrollToPosition) else null,
            updateTimestamp = System.currentTimeMillis(),
            anchorMessageBefore = window.anchorBefore,
            anchorMessageAfter = window.anchorAfter,
            readPosition = readPosition
        )
        observerMessagesChanges()
    }

    /**
     * First screen anchored at [failed] — the earliest thing the user has not dealt with.
     *
     * [readPosition] is passed through unchanged: the divider is a session-scoped anchor in
     * `ChatMessageViewModel`, so it renders if and only if its boundary message is in the loaded
     * window, and it survives later pages. No suppression is needed here — the window only ever
     * moves EARLIER than the default one, so the real first unread is either inside it (correct
     * divider) or past its end (no candidate, nothing drawn).
     *
     * Window shape mirrors [jumpToMessage] but is keyed on `systemShowTimestamp` — the display-order
     * column the whole controller pages on. `timeStamp` (the local clock at compose time) is used
     * ONLY as the `ScrollAction.ToMessage` key and MUST NOT be used to build the window: on a failed
     * message the two can disagree, which would desync the window from its own ordering.
     */
    private suspend fun loadFirstScreenAnchoredAtFailure(
        failed: MessageModel,
        readPosition: Long,
    ) {
        val anchorTs = failed.systemShowTimestamp
        // Query one extra message to use as the after-anchor.
        val afterMessages = wcdb.message.getAllObjects(
            commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.ge(anchorTs)),
            DBMessageModel.systemShowTimestamp.order(Order.Asc), PAGE_SIZE + 1
        )
        val allMessages = if (afterMessages.size < PAGE_SIZE) {
            // Less than a full page after the anchor: back-fill earlier messages, one extra for the before-anchor.
            val earlierMessages = wcdb.message.getAllObjects(
                commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.lt(anchorTs)),
                DBMessageModel.systemShowTimestamp.order(Order.Desc), PAGE_SIZE - afterMessages.size + 1
            )
            earlierMessages + afterMessages
        } else {
            afterMessages
        }

        val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }

        // Split into anchor messages and the page to display.
        val window = splitMessageWindow(sortedMessages, afterMessages.size, PAGE_SIZE.toInt())

        L.i {
            "[${forWhat.id}] first screen anchored at failed message: anchorTs=$anchorTs " +
                "page=${window.pageMessages.size}"
        }

        _chatMessagesStateFlow.value = ChatMessageListBehavior(
            messageList = window.pageMessages,
            // ToMessage, not ToPosition: index-free (the Fragment resolves it against the
            // transformed list, so it is immune to the mapNotNull/filterNot drift a raw page index
            // suffers) and exempt from the call-header scroll compensation, which only skips
            // ToMessage and would otherwise yank the anchored view back to the bottom.
            scrollAction = ScrollAction.ToMessage(failed.timeStamp),
            updateTimestamp = System.currentTimeMillis(),
            anchorMessageBefore = window.anchorBefore,
            anchorMessageAfter = window.anchorAfter,
            readPosition = readPosition,
        )
        observerMessagesChanges()
    }

    override
    suspend fun loadPreviousPage(): Boolean = withContext(Dispatchers.IO) {// true indicates done loading data, false indicates still has messages left
        val currentMessages = chatMessagesStateFlow.value?.messageList ?: emptyList()
        val oldestMessageSystemShowTimeStamp: Long =
            currentMessages.minOfOrNull { it.systemShowTimestamp } ?: Long.MAX_VALUE
        val previewPageQueryCondition = commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.lt(oldestMessageSystemShowTimeStamp))

        // 多查询一条用作前锚点
        val allPageMessages = wcdb.message.getAllObjects(previewPageQueryCondition, DBMessageModel.systemShowTimestamp.order(Order.Desc), PAGE_SIZE + 1)
        L.i { "[${forWhat.id}] loadPreviousPage, allPageMessages: ${allPageMessages.size}" }

        // 拆分锚点消息和要显示的消息
        val anchorMessageBefore = if (allPageMessages.size > PAGE_SIZE.toInt()) allPageMessages.last() else null
        val pageMessages = if (allPageMessages.size > PAGE_SIZE.toInt()) {
            allPageMessages.dropLast(1)
        } else {
            allPageMessages
        }

        val messageList = (pageMessages + currentMessages).distinctBy { it.id }.sortedBy { it.systemShowTimestamp }
        val newMessageList = messageList.take(MAX_MESSAGE_COUNT)

        // 如果消息列表被截断，使用被截断的第一条作为后锚点
        val anchorMessageAfter = if (messageList.size > MAX_MESSAGE_COUNT) {
            messageList[MAX_MESSAGE_COUNT]
        } else null

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = newMessageList,
                scrollAction = ScrollAction.PreservePosition, // 加载上一页不滚动，保持当前位置
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                anchorMessageAfter = anchorMessageAfter
            )
        observerMessagesChanges()
        val displayMinSystemShowTimestamp =
            chatMessagesStateFlow.value?.messageList?.minOfOrNull { it.systemShowTimestamp } ?: Long.MIN_VALUE
        return@withContext wcdb.message.getValue(
            DBMessageModel.id.count(),
            commonMessageQueryCondition.and(
                DBMessageModel.systemShowTimestamp.lt(displayMinSystemShowTimestamp)
            )
        )?.int != 0
    }

    override
    suspend fun loadNextPage(): Boolean = withContext(Dispatchers.IO) { // true indicates done loading data, false indicates still has messages left
        val currentMessages = chatMessagesStateFlow.value?.messageList ?: emptyList()
        val latestMessageSystemShowTimeStamp: Long =
            currentMessages.maxOfOrNull { it.systemShowTimestamp }
                ?: Long.MIN_VALUE

        // 多查询一条用作后锚点
        val allPageMessages = wcdb.message.getAllObjects(
            commonMessageQueryCondition.and(
                DBMessageModel.systemShowTimestamp.gt(
                    latestMessageSystemShowTimeStamp
                )
            ),
            DBMessageModel.systemShowTimestamp.order(Order.Asc), PAGE_SIZE + 1
        )

        // 拆分锚点消息和要显示的消息
        val anchorMessageAfter = if (allPageMessages.size > PAGE_SIZE.toInt()) allPageMessages.last() else null
        val pageMessages = if (allPageMessages.size > PAGE_SIZE.toInt()) {
            allPageMessages.dropLast(1)
        } else {
            allPageMessages
        }

        val allMessages = (currentMessages + pageMessages).distinctBy { it.id }.sortedBy { it.systemShowTimestamp }
        val messageList = allMessages.takeLast(MAX_MESSAGE_COUNT)
        L.i { "[${forWhat.id}] loadNextPage, after mering exist messages and new messages and take max size of messages, messageList: ${messageList.size}" }

        // 如果消息列表被截断，使用被截断的最后一条作为前锚点
        val anchorMessageBefore = if (allMessages.size > MAX_MESSAGE_COUNT) {
            allMessages[allMessages.size - MAX_MESSAGE_COUNT - 1]
        } else null

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = messageList,
                scrollAction = ScrollAction.PreservePosition, // 加载下一页不滚动，保持当前位置
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                anchorMessageAfter = anchorMessageAfter
            )
        observerMessagesChanges()
        val displayMaxSystemShowTimestamp =
            chatMessagesStateFlow.value?.messageList?.maxOfOrNull { it.systemShowTimestamp }
                ?: return@withContext false
        return@withContext wcdb.message.getValue(
            DBMessageModel.id.count(),
            commonMessageQueryCondition.and(
                DBMessageModel.systemShowTimestamp.gt(displayMaxSystemShowTimestamp)
            )
        )?.int != 0
    }

    override
    suspend fun jumpToMessage(messageTimeStamp: Long): Boolean = withContext(Dispatchers.IO) {
        //start from the message with the given id, and load the next 40 messages include it
        val targetMessage = wcdb.message.getFirstObject(commonMessageQueryCondition.and(DBMessageModel.timeStamp.eq(messageTimeStamp)))
        if (targetMessage == null) {
            return@withContext false
        } else {
            // 多查询一条用作后锚点
            val afterMessages = wcdb.message.getAllObjects(
                commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.ge(targetMessage.systemShowTimestamp)),
                DBMessageModel.systemShowTimestamp.order(Order.Asc), PAGE_SIZE + 1
            )
            L.i { "[${forWhat.id}] jumpToMessage, afterMessages behind with current message: ${afterMessages.size}" }

            val allMessages = if (afterMessages.size < PAGE_SIZE) {
                //if the afterMessages is less than pageSize, then load the previous messages to make up the page
                // 多查询一条用作前锚点
                val expectedMessages = wcdb.message.getAllObjects(
                    commonMessageQueryCondition.and(
                        DBMessageModel.systemShowTimestamp.lt(
                            targetMessage.systemShowTimestamp
                        )
                    ),
                    DBMessageModel.systemShowTimestamp.order(Order.Desc),
                    PAGE_SIZE - afterMessages.size + 1
                )
                expectedMessages + afterMessages
            } else {
                afterMessages
            }
            L.i { "[${forWhat.id}] jumpToMessage, after load previous messages, allMessages: ${allMessages.size}" }

            val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }

            // 拆分锚点消息和显示消息
            val window = splitMessageWindow(sortedMessages, afterMessages.size, PAGE_SIZE.toInt())

            L.i { "[${forWhat.id}] jumpToMessage, after make up hot data and convert from message Model, pageMessages: ${window.pageMessages.size}" }

            _chatMessagesStateFlow.value =
                ChatMessageListBehavior(
                    messageList = window.pageMessages,
                    scrollAction = ScrollAction.ToMessage(messageTimeStamp), // 滚动到目标消息
                    updateTimestamp = System.currentTimeMillis(),
                    anchorMessageBefore = window.anchorBefore,
                    anchorMessageAfter = window.anchorAfter
                )
            observerMessagesChanges()
        }
        return@withContext true
    }

    override
    suspend fun jumpToBottom() = withContext(Dispatchers.IO) {
        // 多查询一条用作前锚点
        val allMessages = wcdb.message.getAllObjects(
            commonMessageQueryCondition,
            DBMessageModel.systemShowTimestamp.order(Order.Desc),
            PAGE_SIZE + 1
        )

        val sortedMessages = allMessages.distinctBy { it.id }
            .sortedBy { it.systemShowTimestamp }
        L.i { "[${forWhat.id}] jumpToBottom, after convert from message Model, sortedMessages: ${sortedMessages.size}" }

        // 拆分锚点消息和显示消息（跳到底部不需要后锚点）
        val anchorMessageBefore = if (sortedMessages.size > PAGE_SIZE.toInt()) sortedMessages.first() else null
        val pageMessages = if (sortedMessages.size > PAGE_SIZE.toInt()) {
            sortedMessages.drop(1)
        } else {
            sortedMessages
        }

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = pageMessages,
                scrollAction = ScrollAction.ToBottom, // 滚动到底部
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore
            )
        observerMessagesChanges()
    }


    private var observeMessageChangesJob: Job? = null

    private suspend fun observerMessagesChanges() {
        observeMessageChangesJob?.cancelAndJoin()
        val lastMessageId = wcdb.message.getValue(
            DBMessageModel.id,
            commonMessageQueryCondition,
            // Order by descending systemShowTimestamp to get the most recent entry
            DBMessageModel.systemShowTimestamp.order(Order.Desc)
        )?.text
        val currentMessageList = chatMessagesStateFlow.value?.messageList ?: emptyList()
        val existMessageIds = currentMessageList.map { it.id }.toTypedArray()
        val minSystemShowTimeStamp =
            currentMessageList.minOfOrNull { it.systemShowTimestamp }
                ?: Long.MIN_VALUE
        val maxSystemShowTimeStamp =
            currentMessageList.maxOfOrNull { it.systemShowTimestamp }
                ?: Long.MAX_VALUE
        val queryCondition = if (lastMessageId == null || lastMessageId in existMessageIds) {
            L.i { "[${forWhat.id}] observerMessagesChanges, include new incoming messages" }
            commonMessageQueryCondition.and(
                DBMessageModel.systemShowTimestamp.ge(
                    minSystemShowTimeStamp
                )
            )
        } else {
            L.i { "[${forWhat.id}] observerMessagesChanges, not include new incoming messages" }
            commonMessageQueryCondition.and(
                DBMessageModel.systemShowTimestamp.between(
                    minSystemShowTimeStamp,
                    maxSystemShowTimeStamp
                )
            )
        }
        observeMessageChangesJob = RoomChangeTracker.roomChanges
            .filter { changes -> changes.any { it.roomId == forWhat.id && it.type == RoomChangeType.MESSAGE } }
            .sampleAfterFirst(500)
            .onEach {
                L.d { "[${forWhat.id}] observerMessagesChanges, time : ${System.currentTimeMillis()}" }
                // 获取新消息列表
                val updatedMessages = wcdb.message.getAllObjects(
                    queryCondition,
                    DBMessageModel.systemShowTimestamp.order(Order.Asc)
                )
                L.i { "[${forWhat.id}] observerMessagesChanges, newMessageList: ${updatedMessages.size}" }
                // scrollAction = null，让 Fragment 根据 isAtBottom 自己判断是否滚动
                _chatMessagesStateFlow.value = ChatMessageListBehavior(
                    messageList = updatedMessages,
                    scrollAction = null,
                    updateTimestamp = System.currentTimeMillis()
                )
            }
            .flowOn(Dispatchers.IO)
            .launchIn(coroutineScope)
    }

    override fun addOneMessage(messageModel: MessageModel) {
        // Atomic read-modify-write: this runs on Dispatchers.IO and shares the flow with the
        // observer's writer, so update{} avoids the lost-update window a .value read-then-set has.
        _chatMessagesStateFlow.update { current ->
            val currentMessages = current?.messageList ?: emptyList()
            // Skip if the observer re-query already surfaced it, to avoid a duplicate list bubble.
            if (currentMessages.any { it.id == messageModel.id }) return@update current
            ChatMessageListBehavior(
                messageList = currentMessages + messageModel,
                scrollAction = ScrollAction.ToBottom, // 发送消息后滚动到底部
                updateTimestamp = System.currentTimeMillis()
            )
        }
    }
}
