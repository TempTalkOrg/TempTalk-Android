package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.sampleAfterFirst
import com.tencent.wcdb.winq.Order
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.MessageModel

// care of  ConversationUtils.messagesUpdate.filter { it == forWhat.id }
class ChatNormalPaginationController @AssistedInject constructor(
    @Assisted
    private val forWhat: For,
    private val wcdb: WCDB,
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

    private suspend fun loadNormalChatDefaultMessages() {
        val scrollToPosition: Int
        val readPosition =
            wcdb.room.getValue(DBRoomModel.readPosition, DBRoomModel.roomId.eq(forWhat.id))?.long ?: 0L

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
        val anchorMessageBefore: MessageModel?
        val pageMessages: List<MessageModel>
        val anchorMessageAfter: MessageModel?

        val pageSizeInt = PAGE_SIZE.toInt()
        if (sortedMessages.size <= pageSizeInt) {
            // 消息不够，没有锚点
            anchorMessageBefore = null
            pageMessages = sortedMessages
            anchorMessageAfter = null
        } else if (expectedUnreadMessages.size >= pageSizeInt + 1) {
            // 未读消息够一页还多，最后一条作为后锚点
            anchorMessageBefore = null
            pageMessages = sortedMessages.take(pageSizeInt)
            anchorMessageAfter = sortedMessages.last()
        } else {
            // 已读+未读混合，第一条作为前锚点，最后一条作为后锚点（如果有的话）
            val hasAfterAnchor = sortedMessages.size > pageSizeInt + 1
            anchorMessageBefore = sortedMessages.first()
            pageMessages = if (hasAfterAnchor) {
                sortedMessages.subList(1, pageSizeInt + 1)
            } else {
                sortedMessages.subList(1, sortedMessages.size)
            }
            anchorMessageAfter = if (hasAfterAnchor) sortedMessages.last() else null
        }

        scrollToPosition = if (expectedUnreadMessages.isNotEmpty()) {
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
            pageMessages,
            scrollToPosition,
            updateTimestamp = System.currentTimeMillis(),
            anchorMessageBefore = anchorMessageBefore,
            anchorMessageAfter = anchorMessageAfter,
            readPosition = readPosition
        )
        observerMessagesChanges()
    }

    override
    suspend fun loadPreviousPage(): Boolean = withContext(Dispatchers.IO) {// true indicates done loading data, false indicates still has messages left
        val currentMessages = chatMessagesStateFlow.value.messageList
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
                newMessageList,
                -1,
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                anchorMessageAfter = anchorMessageAfter
            )
        observerMessagesChanges()
        val displayMinSystemShowTimestamp =
            chatMessagesStateFlow.value.messageList.minOfOrNull { it.systemShowTimestamp } ?: Long.MIN_VALUE
        return@withContext wcdb.message.getValue(
            DBMessageModel.id.count(),
            commonMessageQueryCondition.and(
                DBMessageModel.systemShowTimestamp.lt(displayMinSystemShowTimestamp)
            )
        )?.int != 0
    }

    override
    suspend fun loadNextPage(): Boolean = withContext(Dispatchers.IO) { // true indicates done loading data, false indicates still has messages left
        val currentMessages = chatMessagesStateFlow.value.messageList
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
                messageList,
                -1,
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                anchorMessageAfter = anchorMessageAfter
            )
        observerMessagesChanges()
        val displayMaxSystemShowTimestamp =
            chatMessagesStateFlow.value.messageList.maxOfOrNull { it.systemShowTimestamp }
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
            val anchorMessageBefore: MessageModel?
            val pageMessages: List<MessageModel>
            val anchorMessageAfter: MessageModel?

            val pageSizeInt = PAGE_SIZE.toInt()
            if (sortedMessages.size <= pageSizeInt) {
                // 消息不够，没有锚点
                anchorMessageBefore = null
                pageMessages = sortedMessages
                anchorMessageAfter = null
            } else if (afterMessages.size >= pageSizeInt + 1) {
                // 后续消息够一页还多，最后一条作为后锚点
                anchorMessageBefore = null
                pageMessages = sortedMessages.take(pageSizeInt)
                anchorMessageAfter = sortedMessages.last()
            } else {
                // 前后混合，第一条作为前锚点，最后一条作为后锚点（如果有的话）
                val hasAfterAnchor = sortedMessages.size > pageSizeInt + 1
                anchorMessageBefore = sortedMessages.first()
                pageMessages = if (hasAfterAnchor) {
                    sortedMessages.subList(1, pageSizeInt + 1)
                } else {
                    sortedMessages.subList(1, sortedMessages.size)
                }
                anchorMessageAfter = if (hasAfterAnchor) sortedMessages.last() else null
            }

            L.i { "[${forWhat.id}] jumpToMessage, after make up hot data and convert from message Model, pageMessages: ${pageMessages.size}" }

            val scrollToPosition = pageMessages.indexOfFirst { it.id == targetMessage.id }
            _chatMessagesStateFlow.value =
                ChatMessageListBehavior(
                    pageMessages,
                    scrollToPosition,
                    updateTimestamp = System.currentTimeMillis(),
                    anchorMessageBefore = anchorMessageBefore,
                    anchorMessageAfter = anchorMessageAfter
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

        val scrollToPosition = pageMessages.size - 1
        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                pageMessages,
                scrollToPosition,
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
        val existMessageIds = chatMessagesStateFlow.value.messageList.map { it.id }.toTypedArray()
        var needAutoScrollToBottom = false
        val minSystemShowTimeStamp =
            chatMessagesStateFlow.value.messageList.minOfOrNull { it.systemShowTimestamp }
                ?: Long.MIN_VALUE
        val maxSystemShowTimeStamp =
            chatMessagesStateFlow.value.messageList.maxOfOrNull { it.systemShowTimestamp }
                ?: Long.MAX_VALUE
        val queryCondition = if (lastMessageId == null || lastMessageId in existMessageIds) {
            L.i { "[${forWhat.id}] observerMessagesChanges, include new incoming messages" }
            needAutoScrollToBottom = true
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
            .filter { changes -> changes.any { it.roomId == forWhat.id } }
            .sampleAfterFirst(500)
            .onEach {
                L.d { "[${forWhat.id}] observerMessagesChanges, time : ${System.currentTimeMillis()}" }
                // 获取新消息列表
                val updatedMessages = wcdb.message.getAllObjects(
                    queryCondition,
                    DBMessageModel.systemShowTimestamp.order(Order.Asc)
                )
                val scrollToPosition = if (needAutoScrollToBottom) updatedMessages.size - 1 else -1
                L.i { "[${forWhat.id}] observerMessagesChanges, newMessageList: ${updatedMessages.size}" }
                _chatMessagesStateFlow.value = ChatMessageListBehavior(
                    messageList = updatedMessages,
                    scrollToPosition = scrollToPosition,
                    stateTriggeredByUser = false,
                    updateTimestamp = System.currentTimeMillis()
                )
            }
            .flowOn(Dispatchers.IO)
            .launchIn(coroutineScope)
    }

    override fun addOneMessage(messageModel: MessageModel) {
        val currentMessages = chatMessagesStateFlow.value.messageList
        val newMessageList = (currentMessages + messageModel)
        _chatMessagesStateFlow.value = ChatMessageListBehavior(
            newMessageList,
            newMessageList.size - 1,
            true,
            updateTimestamp = System.currentTimeMillis()
        )
    }
}