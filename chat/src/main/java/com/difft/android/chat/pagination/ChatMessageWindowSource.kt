package com.difft.android.chat.pagination

import kotlinx.coroutines.flow.Flow
import org.difft.app.database.models.MessageModel

















interface ChatMessageWindowSource {

    
    fun roomAnchors(): RoomAnchors?

    
    fun earliestFailedOutgoing(): MessageModel?

    
    fun firstUnreadFromOthers(readPosition: Long, myId: String): MessageModel?

    
    fun countOlderThan(ts: Long): Int

    
    fun countNewerThan(ts: Long): Int

    
    fun newerThan(ts: Long, limit: Long): List<MessageModel>

    
    fun atOrNewerThan(ts: Long, limit: Long): List<MessageModel>

    
    fun olderThan(ts: Long, limit: Long): List<MessageModel>

    
    fun atOrOlderThan(ts: Long, limit: Long): List<MessageModel>

    
    fun latest(limit: Long): List<MessageModel>

    
    fun latestMessageId(): String?

    
    fun byTimeStamp(timeStamp: Long): MessageModel?

    



    fun ascendingFrom(fromTs: Long, toTs: Long?): List<MessageModel>

    





    val messageChanges: Flow<Unit>
}


data class RoomAnchors(val readPosition: Long, val sendStatus: Int)
