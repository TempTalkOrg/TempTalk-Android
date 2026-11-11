package com.difft.android.migration.v2.importer

import com.tencent.wcdb.base.Value
import com.tencent.wcdb.base.WCDBException
import com.tencent.wcdb.core.Database
import org.difft.app.database.WCDB
import org.difft.app.database.models.AttachmentModel
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.PendingMessageModelNew
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel











interface WcdbWriteTarget : RoomStateReadWrite {

    
    fun runInTransaction(block: () -> Unit)

    
    fun insertQuote(model: QuoteModel): Long

    
    fun insertForwardContext(model: ForwardContextModel): Long

    
    fun insertForward(model: ForwardModel): Long

    
    fun insertMessage(model: MessageModel)

    
    fun insertAttachment(model: AttachmentModel)

    
    fun insertMention(model: MentionModel)

    
    fun insertReaction(model: ReactionModel)

    
    fun insertSharedContact(model: SharedContactModel): Long

    
    fun insertSharedContactPhone(model: SharedContactPhoneModel)

    
    fun upsertRoom(model: RoomModel)

    



    fun insertReadInfo(model: ReadInfoModel)

    
    fun insertGroupKey(model: GroupCryptoKeysModel)

    




    fun insertPendingEnvelope(model: PendingMessageModelNew)

    








    fun markReadUpTo(roomId: String, maxServerTime: Long, readAt: Long, selfUid: String)

    





    fun markAttachmentBytesAbsent(attachmentId: String)
}






class WcdbWriteTargetImpl(private val target: WCDB) : WcdbWriteTarget {

    override fun runInTransaction(block: () -> Unit) = target.db.runRollingBack(block)

    override fun insertQuote(model: QuoteModel): Long {
        target.quote.insertObject(model)
        
        return model.databaseId.toLong()
    }

    override fun insertForwardContext(model: ForwardContextModel): Long {
        target.forwardContext.insertObject(model)
        return model.databaseId.toLong()
    }

    override fun insertForward(model: ForwardModel): Long {
        target.forward.insertObject(model)
        return model.databaseId.toLong()
    }

    override fun insertMessage(model: MessageModel) {
        target.message.insertObject(model)
    }

    override fun insertAttachment(model: AttachmentModel) {
        target.attachment.insertObject(model)
    }

    override fun insertMention(model: MentionModel) {
        target.mention.insertObject(model)
    }

    override fun insertReaction(model: ReactionModel) {
        target.reaction.insertObject(model)
    }

    override fun insertSharedContact(model: SharedContactModel): Long {
        target.sharedContact.insertObject(model)
        
        return model.databaseId.toLong()
    }

    override fun insertSharedContactPhone(model: SharedContactPhoneModel) {
        target.sharedContactPhone.insertObject(model)
    }

    override fun upsertRoom(model: RoomModel) {
        
        
        
        target.room.insertObject(model)
    }

    override fun insertReadInfo(model: ReadInfoModel) {
        
        target.readInfo.insertOrReplaceObject(model)
    }

    override fun insertGroupKey(model: GroupCryptoKeysModel) {
        target.groupCryptoKeys.insertOrReplaceObject(model)
    }

    override fun insertPendingEnvelope(model: PendingMessageModelNew) {
        target.pendingMessageNew.insertOrReplaceObject(model)
    }

    override fun markReadUpTo(roomId: String, maxServerTime: Long, readAt: Long, selfUid: String) {
        target.message.updateValue(
            readAt,
            DBMessageModel.readTime,
            DBMessageModel.roomId.eq(roomId)
                .and(DBMessageModel.readTime.eq(0L))
                .and(DBMessageModel.fromWho.notEq(selfUid))
                .and(DBMessageModel.systemShowTimestamp.le(maxServerTime)),
        )
    }

    override fun markAttachmentBytesAbsent(attachmentId: String) {
        target.attachment.updateValue(0, DBAttachmentModel.status, DBAttachmentModel.id.eq(attachmentId))
    }

    

    override fun roomIdsWithMessages(): List<String> =
        RoomStateSupport.roomIdsWithMessages(target.message.allObjects)

    override fun roomReadPosition(roomId: String): Long =
        target.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.readPosition ?: 0L

    override fun latestMessagePreviewInfo(roomId: String): LatestMessagePreviewInfo? =
        RoomStateSupport.latestMessagePreviewInfo(
            roomId,
            target.message.allObjects,
            target.attachment.allObjects,
            target.sharedContact.allObjects,
            target.forwardContext.allObjects,
        )

    override fun unreadMessageIds(roomId: String, readPosition: Long, selfUid: String): List<String> =
        RoomStateSupport.unreadMessageIds(roomId, readPosition, selfUid, target.message.allObjects)

    override fun mentionUidsForMessages(messageIds: List<String>, selfUid: String): List<String> =
        RoomStateSupport.mentionUidsForMessages(messageIds, selfUid, target.mention.allObjects)

    override fun hasFailedOutgoingMessage(roomId: String): Boolean =
        RoomStateSupport.hasFailedOutgoingMessage(roomId, target.message.getAllObjects(DBMessageModel.roomId.eq(roomId)))

    
    override fun hasCriticalAlert(messageIds: List<String>): Boolean =
        RoomStateSupport.hasCriticalAlert(
            messageIds,
            target.message.getAllObjects(DBMessageModel.criticalAlertType.eq(CRITICAL_ALERT_TYPE_ALERT)),
        )

    override fun updateRoomDerivedState(roomId: String, state: RoomDerivedState) {
        target.room.updateRow(
            arrayOf(
                Value(state.lastDisplayContent),
                Value(state.lastActiveTime),
                Value(state.unreadMessageNum),
                Value(state.mentionType),
                Value(state.sendStatus),
                Value(state.criticalAlertType),
            ),
            arrayOf(
                DBRoomModel.lastDisplayContent,
                DBRoomModel.lastActiveTime,
                DBRoomModel.unreadMessageNum,
                DBRoomModel.mentionType,
                DBRoomModel.sendStatus,
                DBRoomModel.criticalAlertType,
            ),
            DBRoomModel.roomId.eq(roomId),
        )
    }
}








internal fun Database.runRollingBack(block: () -> Unit) {
    var failure: Exception? = null
    try {
        runTransaction { _ ->
            try {
                block()
                true
            } catch (e: Exception) {
                failure = e
                false
            }
        }
    } catch (e: WCDBException) {
        throw failure ?: e
    }
}
