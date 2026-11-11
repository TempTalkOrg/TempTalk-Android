package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.qualifier.User
import com.difft.android.chat.pagination.ChatMessageWindowSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.difft.app.database.models.MessageModel


class ChatNormalPaginationController @AssistedInject constructor(
    @Assisted
    private val forWhat: For,
    
    @Assisted
    private val source: ChatMessageWindowSource,
    
    
    
    
    @param:User.Uid private val myId: String,
) : BaseChatPaginationController() {
    companion object {
        
        const val INITIAL_PAGE_SIZE: Long = 20L

        



        const val SCROLL_PAGE_SIZE: Long = 50L

        




        const val JUMP_PAGE_SIZE: Long = 20L

        



        const val MAX_MESSAGE_COUNT = 180

        



        const val TRIM_SLACK = MAX_MESSAGE_COUNT / 2

        





        const val TRIM_HIGH_WATER = MAX_MESSAGE_COUNT + TRIM_SLACK

        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private suspend fun hasOlderMessages(beforeTimestamp: Long): Boolean =
        source.countOlderThan(beforeTimestamp) != 0

    






    private suspend fun computeHasReachedHistoryStart(messageList: List<MessageModel>): Boolean {
        val oldestTimestamp = messageList.minOfOrNull { it.systemShowTimestamp } ?: return true
        return !hasOlderMessages(oldestTimestamp)
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
        
        
        val roomAnchors = source.roomAnchors()
        val readPosition = roomAnchors?.readPosition ?: 0L
        val roomSendStatus = roomAnchors?.sendStatus ?: ROOM_SEND_STATUS_NONE

        
        
        if (roomSendStatus == ROOM_SEND_STATUS_FAILED) {
            val failed = source.earliestFailedOutgoing()
            if (failed != null) {
                
                
                val firstUnreadOthersTs = source.firstUnreadFromOthers(readPosition, myId)
                    ?.systemShowTimestamp
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

    
    private suspend fun loadFirstScreenFromReadPosition(readPosition: Long) {
        
        val expectedUnreadMessages = source.newerThan(readPosition, INITIAL_PAGE_SIZE + 1)
        L.i { "[${forWhat.id}] Load normal chat default messages, expectedUnreadMessages.size = ${expectedUnreadMessages.size}" }

        val allMessages = if (expectedUnreadMessages.size < INITIAL_PAGE_SIZE) {
            
            val expectedMessages = source.atOrOlderThan(
                readPosition, INITIAL_PAGE_SIZE - expectedUnreadMessages.size + 1
            )
            L.i { "[${forWhat.id}] Load normal chat default messages, expectedReadMessages.size = ${expectedMessages.size}" }
            expectedMessages + expectedUnreadMessages
        } else {
            expectedUnreadMessages
        }

        val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }
        L.i { "[${forWhat.id}] Load normal chat default messages, sortedMessages.size = ${sortedMessages.size}" }

        val window = splitMessageWindow(sortedMessages, expectedUnreadMessages.size, INITIAL_PAGE_SIZE.toInt())
        val pageMessages = window.pageMessages

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
            readPosition = readPosition,
            hasReachedHistoryStart = computeHasReachedHistoryStart(pageMessages),
            
            
            
            
            hasReachedLatest = expectedUnreadMessages.size <= INITIAL_PAGE_SIZE,
        )
        observerMessagesChanges()
    }

    













    private suspend fun loadFirstScreenAnchoredAtFailure(
        failed: MessageModel,
        readPosition: Long,
    ) {
        val anchorTs = failed.systemShowTimestamp
        
        val afterMessages = source.atOrNewerThan(anchorTs, INITIAL_PAGE_SIZE + 1)
        val allMessages = if (afterMessages.size < INITIAL_PAGE_SIZE) {
            
            val earlierMessages = source.olderThan(anchorTs, INITIAL_PAGE_SIZE - afterMessages.size + 1)
            earlierMessages + afterMessages
        } else {
            afterMessages
        }

        val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }

        
        val window = splitMessageWindow(sortedMessages, afterMessages.size, INITIAL_PAGE_SIZE.toInt())

        L.i {
            "[${forWhat.id}] first screen anchored at failed message: anchorTs=$anchorTs " +
                "page=${window.pageMessages.size}"
        }

        _chatMessagesStateFlow.value = ChatMessageListBehavior(
            messageList = window.pageMessages,
            
            
            
            
            scrollAction = ScrollAction.ToMessage(failed.timeStamp),
            updateTimestamp = System.currentTimeMillis(),
            anchorMessageBefore = window.anchorBefore,
            anchorMessageAfter = window.anchorAfter,
            readPosition = readPosition,
            hasReachedHistoryStart = computeHasReachedHistoryStart(window.pageMessages),
            
            hasReachedLatest = afterMessages.size <= INITIAL_PAGE_SIZE,
        )
        observerMessagesChanges()
    }

    override
    suspend fun loadPreviousPage(): Boolean = withContext(Dispatchers.IO) {
        val current = chatMessagesStateFlow.value
        val currentMessages = current?.messageList ?: emptyList()
        val oldestMessageSystemShowTimeStamp: Long =
            currentMessages.minOfOrNull { it.systemShowTimestamp } ?: Long.MAX_VALUE

        
        val allPageMessages = source.olderThan(oldestMessageSystemShowTimeStamp, SCROLL_PAGE_SIZE + 1)
        L.i { "[${forWhat.id}] loadPreviousPage, allPageMessages: ${allPageMessages.size}" }

        val anchorMessageBefore = if (allPageMessages.size > SCROLL_PAGE_SIZE.toInt()) allPageMessages.last() else null
        val pageMessages = if (allPageMessages.size > SCROLL_PAGE_SIZE.toInt()) {
            allPageMessages.dropLast(1)
        } else {
            allPageMessages
        }

        val messageList = (pageMessages + currentMessages).distinctBy { it.id }.sortedBy { it.systemShowTimestamp }
        val newMessageList = messageList.take(MAX_MESSAGE_COUNT)

        
        val anchorMessageAfter = if (messageList.size > MAX_MESSAGE_COUNT) {
            messageList[MAX_MESSAGE_COUNT]
        } else null

        
        
        val displayMinSystemShowTimestamp =
            newMessageList.minOfOrNull { it.systemShowTimestamp } ?: Long.MIN_VALUE
        val hasOlder = hasOlderMessages(displayMinSystemShowTimestamp)

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = newMessageList,
                scrollAction = ScrollAction.PreservePosition,
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                anchorMessageAfter = anchorMessageAfter,
                hasReachedHistoryStart = !hasOlder,
                
                
                
                
                
                
                hasReachedLatest = anchorMessageAfter == null && (current?.hasReachedLatest ?: false),
            )
        observerMessagesChanges()
        return@withContext hasOlder
    }

    override
    suspend fun loadNextPage(): Boolean = withContext(Dispatchers.IO) { 
        val currentMessages = chatMessagesStateFlow.value?.messageList ?: emptyList()
        val latestMessageSystemShowTimeStamp: Long =
            currentMessages.maxOfOrNull { it.systemShowTimestamp }
                ?: Long.MIN_VALUE

        
        val allPageMessages = source.newerThan(latestMessageSystemShowTimeStamp, SCROLL_PAGE_SIZE + 1)

        val anchorMessageAfter = if (allPageMessages.size > SCROLL_PAGE_SIZE.toInt()) allPageMessages.last() else null
        val pageMessages = if (allPageMessages.size > SCROLL_PAGE_SIZE.toInt()) {
            allPageMessages.dropLast(1)
        } else {
            allPageMessages
        }

        val allMessages = (currentMessages + pageMessages).distinctBy { it.id }.sortedBy { it.systemShowTimestamp }
        
        val latestWindow = takeLatestWindow(allMessages, MAX_MESSAGE_COUNT)
        val messageList = latestWindow.pageMessages
        val anchorMessageBefore = latestWindow.droppedNeighbour
        L.i { "[${forWhat.id}] loadNextPage, after mering exist messages and new messages and take max size of messages, messageList: ${messageList.size}" }

        
        
        val displayMaxSystemShowTimestamp = messageList.maxOfOrNull { it.systemShowTimestamp }
        val hasNewer = displayMaxSystemShowTimestamp != null &&
            source.countNewerThan(displayMaxSystemShowTimestamp) != 0

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = messageList,
                scrollAction = ScrollAction.PreservePosition,
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                anchorMessageAfter = anchorMessageAfter,
                hasReachedHistoryStart = computeHasReachedHistoryStart(messageList),
                
                hasReachedLatest = displayMaxSystemShowTimestamp != null && !hasNewer,
            )
        observerMessagesChanges()
        return@withContext hasNewer
    }

    override
    suspend fun jumpToMessage(messageTimeStamp: Long): Boolean = withContext(Dispatchers.IO) {
        
        val targetMessage = source.byTimeStamp(messageTimeStamp)
        if (targetMessage == null) {
            return@withContext false
        } else {
            
            val afterMessages =
                source.atOrNewerThan(targetMessage.systemShowTimestamp, JUMP_PAGE_SIZE + 1)
            L.i { "[${forWhat.id}] jumpToMessage, afterMessages behind with current message: ${afterMessages.size}" }

            val allMessages = if (afterMessages.size < JUMP_PAGE_SIZE) {
                
                
                val expectedMessages = source.olderThan(
                    targetMessage.systemShowTimestamp, JUMP_PAGE_SIZE - afterMessages.size + 1
                )
                expectedMessages + afterMessages
            } else {
                afterMessages
            }
            L.i { "[${forWhat.id}] jumpToMessage, after load previous messages, allMessages: ${allMessages.size}" }

            val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }

            val window = splitMessageWindow(sortedMessages, afterMessages.size, JUMP_PAGE_SIZE.toInt())

            L.i { "[${forWhat.id}] jumpToMessage, after make up hot data and convert from message Model, pageMessages: ${window.pageMessages.size}" }

            _chatMessagesStateFlow.value =
                ChatMessageListBehavior(
                    messageList = window.pageMessages,
                    scrollAction = ScrollAction.ToMessage(messageTimeStamp),
                    updateTimestamp = System.currentTimeMillis(),
                    anchorMessageBefore = window.anchorBefore,
                    anchorMessageAfter = window.anchorAfter,
                    hasReachedHistoryStart = computeHasReachedHistoryStart(window.pageMessages),
                    
                    hasReachedLatest = afterMessages.size <= JUMP_PAGE_SIZE,
                )
            observerMessagesChanges()
        }
        return@withContext true
    }

    override
    suspend fun jumpToBottom() = withContext(Dispatchers.IO) {
        
        val allMessages = source.latest(JUMP_PAGE_SIZE + 1)

        val sortedMessages = allMessages.distinctBy { it.id }
            .sortedBy { it.systemShowTimestamp }
        L.i { "[${forWhat.id}] jumpToBottom, after convert from message Model, sortedMessages: ${sortedMessages.size}" }

        val anchorMessageBefore = if (sortedMessages.size > JUMP_PAGE_SIZE.toInt()) sortedMessages.first() else null
        val pageMessages = if (sortedMessages.size > JUMP_PAGE_SIZE.toInt()) {
            sortedMessages.drop(1)
        } else {
            sortedMessages
        }

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = pageMessages,
                scrollAction = ScrollAction.ToBottom,
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                hasReachedHistoryStart = computeHasReachedHistoryStart(pageMessages),
                
                
                hasReachedLatest = true,
            )
        observerMessagesChanges()
    }


    private var observeMessageChangesJob: Job? = null

    private suspend fun observerMessagesChanges() {
        observeMessageChangesJob?.cancelAndJoin()
        val lastMessageId = source.latestMessageId()
        val currentMessageList = chatMessagesStateFlow.value?.messageList ?: emptyList()
        val existMessageIds = currentMessageList.map { it.id }.toTypedArray()
        val minSystemShowTimeStamp =
            currentMessageList.minOfOrNull { it.systemShowTimestamp }
                ?: Long.MIN_VALUE
        val maxSystemShowTimeStamp =
            currentMessageList.maxOfOrNull { it.systemShowTimestamp }
                ?: Long.MAX_VALUE
        
        val windowUpperBound: Long? = if (lastMessageId == null || lastMessageId in existMessageIds) {
            L.d { "[Chat][${forWhat.id}] observerMessagesChanges, include new incoming messages" }
            null
        } else {
            L.i { "[${forWhat.id}] observerMessagesChanges, not include new incoming messages" }
            maxSystemShowTimeStamp
        }
        observeMessageChangesJob = source.messageChanges
            .onEach {
                
                
                val startedNs = System.nanoTime()
                val updatedMessages = source.ascendingFrom(minSystemShowTimeStamp, windowUpperBound)
                val queryCostMs = (System.nanoTime() - startedNs) / NANOS_PER_MILLI
                L.i {
                    "[message] observer window re-query room=${forWhat.id} " +
                        "rows=${updatedMessages.size} cost=${queryCostMs}ms"
                }
                
                
                
                val edges = if (updatedMessages.isEmpty()) {
                    WindowEdges(null, null)
                } else {
                    source.resolveWindowEdges(
                        oldestTs = updatedMessages.first().systemShowTimestamp,
                        newestTs = updatedMessages.last().systemShowTimestamp,
                    )
                }
                
                _chatMessagesStateFlow.value = ChatMessageListBehavior(
                    messageList = updatedMessages,
                    scrollAction = null,
                    updateTimestamp = System.currentTimeMillis(),
                    anchorMessageBefore = edges.anchorBefore,
                    anchorMessageAfter = edges.anchorAfter,
                    hasReachedHistoryStart = computeHasReachedHistoryStart(updatedMessages),
                    
                    
                    
                    
                    
                    hasReachedLatest = updatedMessages.isNotEmpty() && edges.anchorAfter == null,
                )
            }
            .flowOn(Dispatchers.IO)
            .launchIn(coroutineScope)
    }

    override fun addOneMessage(messageModel: MessageModel) {
        
        
        _chatMessagesStateFlow.update { current ->
            val currentMessages = current?.messageList ?: emptyList()
            
            if (currentMessages.any { it.id == messageModel.id }) return@update current
            ChatMessageListBehavior(
                messageList = currentMessages + messageModel,
                scrollAction = ScrollAction.ToBottom,
                updateTimestamp = System.currentTimeMillis(),
                
                
                anchorMessageBefore = current?.anchorMessageBefore,
                
                
                
                anchorMessageAfter = null,
                
                
                hasReachedHistoryStart = current?.hasReachedHistoryStart ?: false,
                
                hasReachedLatest = current?.hasReachedLatest ?: false,
            )
        }
    }

    




















    override suspend fun trimToLatest() {
        var sizeBefore = 0
        var sizeAfter = 0
        _chatMessagesStateFlow.update { current ->
            if (current == null || current.messageList.size <= MAX_MESSAGE_COUNT) return@update current
            val window = takeLatestWindow(current.messageList, MAX_MESSAGE_COUNT)
            sizeBefore = current.messageList.size
            sizeAfter = window.pageMessages.size
            current.copy(
                messageList = window.pageMessages,
                
                
                scrollAction = ScrollAction.PreservePosition,
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = window.droppedNeighbour,
                
                
                
                hasReachedHistoryStart = false,
            )
        }
        
        
        if (sizeBefore == 0) return
        L.i { "[${forWhat.id}] trimToLatest: $sizeBefore -> $sizeAfter (highWater=$TRIM_HIGH_WATER)" }
        
        
        withContext(Dispatchers.IO) { observerMessagesChanges() }
    }
}
