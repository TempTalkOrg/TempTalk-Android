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

    







    suspend fun trimToLatest()
}





sealed class ScrollAction {
    
    data class ToPosition(val position: Int) : ScrollAction()

    
    data class ToMessage(val messageTimeStamp: Long) : ScrollAction()

    
    data object ToBottom : ScrollAction()

    
    data object PreservePosition : ScrollAction()
}

data class ChatMessageListBehavior(
    val messageList: List<MessageModel> = emptyList(),
    val scrollAction: ScrollAction? = null, 
    val updateTimestamp: Long = System.currentTimeMillis(),
    val anchorMessageBefore: MessageModel? = null, 
    val anchorMessageAfter: MessageModel? = null, 
    
    
    
    val readPosition: Long? = null,
    


    val hasReachedHistoryStart: Boolean = false,
    









    val hasReachedLatest: Boolean = false,
)










fun ChatMessageListBehavior.messagesToConvert(): List<MessageModel> =
    listOfNotNull(anchorMessageBefore) + messageList + listOfNotNull(anchorMessageAfter)