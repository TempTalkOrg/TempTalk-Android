package com.difft.android.chat.jobs

import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.tencent.wcdb.base.Value
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.TextMessage
import org.difft.app.database.delete
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.wcdb
import javax.inject.Inject








class ScreenShotRowWriter @Inject constructor(
    private val messageStore: MessageStore,
) {
    
    fun insertPending(message: TextMessage) {
        messageStore.putWhenNonExist(message)
    }

    



    fun markSent(message: TextMessage) {
        messageStore.putWhenNonExist(message)
        wcdb.message.updateRow(
            arrayOf(
                Value(message.systemShowTimestamp),
                Value(message.notifySequenceId),
                Value(message.sequenceId),
                Value(message.sendType),
                Value(message.systemShowTimestamp), 
            ),
            arrayOf(
                DBMessageModel.systemShowTimestamp,
                DBMessageModel.notifySequenceId,
                DBMessageModel.sequenceId,
                DBMessageModel.sendType,
                DBMessageModel.readTime,
            ),
            DBMessageModel.id.eq(message.id),
        )
        RoomChangeTracker.trackRoom(message.forWhat.id, RoomChangeType.MESSAGE)
    }

    
    fun withdraw(message: TextMessage) {
        wcdb.message.getFirstObject(DBMessageModel.id.eq(message.id))?.delete()
        RoomChangeTracker.trackRoom(message.forWhat.id, RoomChangeType.MESSAGE)
    }
}
