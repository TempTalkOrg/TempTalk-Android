package com.difft.android.chat.pagination

import com.tencent.wcdb.winq.Order
import com.tencent.wcdb.winq.ResultColumnConvertible
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import kotlinx.coroutines.flow.Flow
import org.difft.app.database.WCDB
import org.difft.app.database.earliestFailedOutgoingMessage
import org.difft.app.database.firstUnreadFromOthersMessage
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.MessageModel









internal class WcdbChatMessageWindowSource(
    private val wcdb: WCDB,
    private val forWhat: For,
) : ChatMessageWindowSource {

    
    
    private val roomCondition =
        DBMessageModel.roomType.eq(forWhat.typeValue).and(DBMessageModel.roomId.eq(forWhat.id))

    override fun roomAnchors(): RoomAnchors? {
        val row = wcdb.room.getOneRow(
            arrayOf<ResultColumnConvertible>(DBRoomModel.readPosition, DBRoomModel.sendStatus),
            DBRoomModel.roomId.eq(forWhat.id)
        ) ?: return null
        return RoomAnchors(
            readPosition = row.getOrNull(0)?.long ?: 0L,
            sendStatus = row.getOrNull(1)?.int ?: ROOM_SEND_STATUS_NONE,
        )
    }

    override fun earliestFailedOutgoing(): MessageModel? =
        wcdb.earliestFailedOutgoingMessage(forWhat.id)

    override fun firstUnreadFromOthers(readPosition: Long, myId: String): MessageModel? =
        wcdb.firstUnreadFromOthersMessage(forWhat.id, forWhat.typeValue, readPosition, myId)

    
    
    override fun countOlderThan(ts: Long): Int =
        wcdb.message.getValue(
            DBMessageModel.id.count(),
            roomCondition.and(DBMessageModel.systemShowTimestamp.lt(ts))
        )?.int ?: 0

    override fun countNewerThan(ts: Long): Int =
        wcdb.message.getValue(
            DBMessageModel.id.count(),
            roomCondition.and(DBMessageModel.systemShowTimestamp.gt(ts))
        )?.int ?: 0

    override fun newerThan(ts: Long, limit: Long): List<MessageModel> =
        wcdb.message.getAllObjects(
            roomCondition.and(DBMessageModel.systemShowTimestamp.gt(ts)),
            DBMessageModel.systemShowTimestamp.order(Order.Asc), limit
        )

    override fun atOrNewerThan(ts: Long, limit: Long): List<MessageModel> =
        wcdb.message.getAllObjects(
            roomCondition.and(DBMessageModel.systemShowTimestamp.ge(ts)),
            DBMessageModel.systemShowTimestamp.order(Order.Asc), limit
        )

    override fun olderThan(ts: Long, limit: Long): List<MessageModel> =
        wcdb.message.getAllObjects(
            roomCondition.and(DBMessageModel.systemShowTimestamp.lt(ts)),
            DBMessageModel.systemShowTimestamp.order(Order.Desc), limit
        )

    override fun atOrOlderThan(ts: Long, limit: Long): List<MessageModel> =
        wcdb.message.getAllObjects(
            roomCondition.and(DBMessageModel.systemShowTimestamp.le(ts)),
            DBMessageModel.systemShowTimestamp.order(Order.Desc), limit
        )

    override fun latest(limit: Long): List<MessageModel> =
        wcdb.message.getAllObjects(
            roomCondition,
            DBMessageModel.systemShowTimestamp.order(Order.Desc), limit
        )

    override fun latestMessageId(): String? =
        wcdb.message.getValue(
            DBMessageModel.id,
            roomCondition,
            
            DBMessageModel.systemShowTimestamp.order(Order.Desc)
        )?.text

    override fun byTimeStamp(timeStamp: Long): MessageModel? =
        wcdb.message.getFirstObject(roomCondition.and(DBMessageModel.timeStamp.eq(timeStamp)))

    override fun ascendingFrom(fromTs: Long, toTs: Long?): List<MessageModel> {
        val condition = if (toTs == null) {
            roomCondition.and(DBMessageModel.systemShowTimestamp.ge(fromTs))
        } else {
            roomCondition.and(DBMessageModel.systemShowTimestamp.between(fromTs, toTs))
        }
        return wcdb.message.getAllObjects(
            condition,
            DBMessageModel.systemShowTimestamp.order(Order.Asc)
        )
    }

    override val messageChanges: Flow<Unit> = roomMessageChanges(forWhat.id)
}
