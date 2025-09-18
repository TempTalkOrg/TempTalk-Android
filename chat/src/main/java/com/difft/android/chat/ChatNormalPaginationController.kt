package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.sampleAfterFirst
import difft.android.messageserialization.For
import com.tencent.wcdb.winq.Order
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
        val expectedUnreadMessages =
            wcdb.message.getAllObjects(
                commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.gt(readPosition)),
                DBMessageModel.systemShowTimestamp.order(Order.Asc), PAGE_SIZE
            )
        L.i { "[${forWhat.id}] Load normal chat default messages, expectedUnreadMessages.size = ${expectedUnreadMessages.size}" }
        val pageMessages =
            if (expectedUnreadMessages.size < PAGE_SIZE) {
                val expectedMessages = wcdb.message.getAllObjects(
                    commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.le(readPosition)),
                    DBMessageModel.systemShowTimestamp.order(Order.Desc), PAGE_SIZE - expectedUnreadMessages.size
                )
                L.i { "[${forWhat.id}] Load normal chat default messages, expectedReadMessages.size = ${expectedMessages.size}" }
                expectedMessages + expectedUnreadMessages
            } else expectedUnreadMessages

        L.i { "[${forWhat.id}] Load normal chat default messages, pageMessages.size = ${pageMessages.size}" }
        val messageList = pageMessages.sortedBy { it.systemShowTimestamp }
        scrollToPosition = if (expectedUnreadMessages.isNotEmpty()) {
            messageList.indexOfFirst {
                expectedUnreadMessages.first().id == it.id
            }
        } else if (pageMessages.isNotEmpty()) {
            pageMessages.size - 1
        } else {
            -1
        }
        _chatMessagesStateFlow.value = ChatMessageListBehavior(
            pageMessages,
            scrollToPosition,
            updateTimestamp = System.currentTimeMillis()
        )
        observerMessagesChanges()
    }

    override
    suspend fun loadPreviousPage(): Boolean = withContext(Dispatchers.IO) {// true indicates done loading data, false indicates still has messages left
        val currentMessages = chatMessagesStateFlow.value.messageList
        val oldestMessageSystemShowTimeStamp: Long =
            currentMessages.minOfOrNull { it.systemShowTimestamp } ?: Long.MAX_VALUE
        val previewPageQueryCondition = commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.lt(oldestMessageSystemShowTimeStamp))
        val pageMessages = wcdb.message.getAllObjects(previewPageQueryCondition, DBMessageModel.systemShowTimestamp.order(Order.Desc), PAGE_SIZE)
        L.i { "[${forWhat.id}] loadPreviousPage, pageMessages: ${pageMessages.size}" }
        val messageList = (pageMessages + currentMessages).distinctBy { it.id }.sortedBy { it.systemShowTimestamp }
        val newMessageList =
            messageList.take(MAX_MESSAGE_COUNT)
        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(newMessageList, -1, updateTimestamp = System.currentTimeMillis())
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
        val pageMessages = wcdb.message.getAllObjects(
            commonMessageQueryCondition.and(
                DBMessageModel.systemShowTimestamp.gt(
                    latestMessageSystemShowTimeStamp
                )
            ),
            DBMessageModel.systemShowTimestamp.order(Order.Asc), PAGE_SIZE
        )
        val messageList = (currentMessages + pageMessages).distinctBy { it.id }.sortedBy { it.systemShowTimestamp }.takeLast(MAX_MESSAGE_COUNT)
        L.i { "[${forWhat.id}] loadNextPage, after mering exist messages and new messages and take max size of messages, messageList: ${messageList.size}" }
        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(messageList, -1, updateTimestamp = System.currentTimeMillis())
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
            val pageMessages = wcdb.message.getAllObjects(
                commonMessageQueryCondition.and(DBMessageModel.systemShowTimestamp.ge(targetMessage.systemShowTimestamp)),
                DBMessageModel.systemShowTimestamp.order(Order.Asc), PAGE_SIZE
            )
            L.i { "[${forWhat.id}] jumpToMessage, pageMessages behind with current message: ${pageMessages.size}" }

            if (pageMessages.size < PAGE_SIZE) { //if the pageMessages is less than pageSize, then load the previous messages to make up the page
                val expectedMessages = wcdb.message.getAllObjects(
                    commonMessageQueryCondition.and(
                        DBMessageModel.systemShowTimestamp.lt(
                            targetMessage.systemShowTimestamp
                        )
                    ),
                    DBMessageModel.systemShowTimestamp.order(Order.Desc),
                    PAGE_SIZE - pageMessages.size
                )
                pageMessages.addAll(expectedMessages)
            }
            L.i { "[${forWhat.id}] jumpToMessage, after load previous messages, pageMessages: ${pageMessages.size}" }
            val newMessageList =
                pageMessages.sortedBy { it.systemShowTimestamp }

            L.i { "[${forWhat.id}] jumpToMessage, after make up hot data and convert from message Model, newMessageList: ${newMessageList.size}" }

            val scrollToPosition = newMessageList.indexOfFirst { it.id == targetMessage.id }
            _chatMessagesStateFlow.value =
                ChatMessageListBehavior(newMessageList, scrollToPosition, updateTimestamp = System.currentTimeMillis())
            observerMessagesChanges()
        }
        return@withContext true
    }

    override
    suspend fun jumpToBottom() = withContext(Dispatchers.IO) {
        wcdb.message.getAllObjects(
            commonMessageQueryCondition,
            DBMessageModel.systemShowTimestamp.order(Order.Desc),
            PAGE_SIZE
        ).let { pageMessages ->
            val newMessageList = pageMessages.distinctBy { it.id }
                .sortedBy { it.systemShowTimestamp }
            L.i { "[${forWhat.id}] jumpToBottom, after convert from message Model, newMessageList: ${newMessageList.size}" }
            val scrollToPosition = newMessageList.size - 1
            _chatMessagesStateFlow.value =
                ChatMessageListBehavior(newMessageList, scrollToPosition, updateTimestamp = System.currentTimeMillis())
            observerMessagesChanges()
        }
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