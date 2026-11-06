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

/**
 * Local row writes for [PushScreenShotSendJob], kept behind one seam so the job's
 * shown → sent / withdrawn lifecycle is unit-testable without a WCDB instance.
 *
 * Every call is synchronous: the job runs these on the job thread, and a withdrawal must be
 * committed before the job framework finishes its terminal cleanup.
 */
class ScreenShotRowWriter @Inject constructor(
    private val messageStore: MessageStore,
) {
    /** Shows the notice right away, while its send is still pending. */
    fun insertPending(message: TextMessage) {
        messageStore.putWhenNonExist(message)
    }

    /**
     * Writes [message]'s send status and server ordering keys onto its row. Inserts the row first
     * when the pending write never landed, so a send that went out always leaves its row behind.
     */
    fun markSent(message: TextMessage) {
        messageStore.putWhenNonExist(message)
        wcdb.message.updateRow(
            arrayOf(
                Value(message.systemShowTimestamp),
                Value(message.notifySequenceId),
                Value(message.sequenceId),
                Value(message.sendType),
                Value(message.systemShowTimestamp), // readTime = systemShowTimestamp for self-sent messages
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

    /** Withdraws the notice shown by [insertPending] once the send has finally failed. */
    fun withdraw(message: TextMessage) {
        wcdb.message.getFirstObject(DBMessageModel.id.eq(message.id))?.delete()
        RoomChangeTracker.trackRoom(message.forWhat.id, RoomChangeType.MESSAGE)
    }
}
