package org.difft.app.database

import androidx.annotation.WorkerThread
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.Order
import com.tencent.wcdb.winq.StatementSelect
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.MessageModel












private fun realOutgoingMessageScope(): Expression =
    DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER)
        .and(DBMessageModel.screenShotJson.isNull().or(DBMessageModel.screenShotJson.eq("")))

private fun failedOutgoingCondition(roomId: String): Expression =
    DBMessageModel.roomId.eq(roomId)
        .and(DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_FAILED))
        .and(realOutgoingMessageScope())










@WorkerThread
fun WCDB.hasFailedOutgoingMessage(roomId: String): Boolean =
    message.getFirstObject(
        failedOutgoingCondition(roomId),
        DBMessageModel.systemShowTimestamp.order(Order.Desc)
    ) != null












@WorkerThread
fun WCDB.earliestFailedOutgoingMessage(roomId: String): MessageModel? =
    message.getFirstObject(
        failedOutgoingCondition(roomId),
        DBMessageModel.systemShowTimestamp.order(Order.Asc)
    )






















@WorkerThread
fun WCDB.firstUnreadFromOthersMessage(
    roomId: String,
    roomType: Int,
    readPosition: Long,
    myId: String,
): MessageModel? =
    message.getFirstObject(
        DBMessageModel.roomId.eq(roomId)
            .and(DBMessageModel.roomType.eq(roomType))
            .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
            .and(DBMessageModel.fromWho.notEq(myId)),
        DBMessageModel.systemShowTimestamp.order(Order.Asc)
    )







@WorkerThread
fun WCDB.writeRoomSendStatus(roomId: String, status: Int) {
    room.updateRow(
        arrayOf(Value(status)),
        arrayOf(DBRoomModel.sendStatus),
        DBRoomModel.roomId.eq(roomId)
    )
}













@WorkerThread
fun WCDB.clearRoomSendStatusIfNoFailure(roomId: String) {
    val stillFailing = StatementSelect()
        .select(DBMessageModel.id)
        .from("message")
        .where(failedOutgoingCondition(roomId))
        .limit(1)
    room.updateRow(
        arrayOf(Value(ROOM_SEND_STATUS_NONE)),
        arrayOf(DBRoomModel.sendStatus),
        DBRoomModel.roomId.eq(roomId)
            .and(DBRoomModel.sendStatus.notEq(ROOM_SEND_STATUS_NONE))
            .and(Expression.notExists(stillFailing))
    )
}


@WorkerThread
fun WCDB.writeRoomSendStatusFor(roomIds: List<String>, status: Int) {
    if (roomIds.isEmpty()) return
    room.updateRow(
        arrayOf(Value(status)),
        arrayOf(DBRoomModel.sendStatus),
        DBRoomModel.roomId.`in`(*roomIds.toTypedArray())
    )
}













@WorkerThread
fun WCDB.roomIdsWithStaleSendingOutgoing(): List<String> =
    message.getOneColumnString(
        DBMessageModel.roomId,
        DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_SENDING).and(realOutgoingMessageScope())
    ).filter { !it.isNullOrEmpty() }.distinct()





@WorkerThread
fun WCDB.roomIdsWithFailedOutgoingMessage(): Set<String> =
    message.getOneColumnString(
        DBMessageModel.roomId,
        DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_FAILED).and(realOutgoingMessageScope())
    ).filter { !it.isNullOrEmpty() }.toSet()





@WorkerThread
fun WCDB.roomIdsWithNonNoneSendStatus(): Set<String> =
    room.getOneColumnString(
        DBRoomModel.roomId,
        DBRoomModel.sendStatus.notEq(ROOM_SEND_STATUS_NONE)
    ).filter { !it.isNullOrEmpty() }.toSet()




private fun sendingOutgoingCondition(roomId: String): Expression =
    DBMessageModel.roomId.eq(roomId)
        .and(DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_SENDING))
        .and(realOutgoingMessageScope())






@WorkerThread
fun WCDB.hasSendingOutgoingMessage(roomId: String): Boolean =
    message.getFirstObject(
        sendingOutgoingCondition(roomId),
        DBMessageModel.systemShowTimestamp.order(Order.Desc)
    ) != null






@WorkerThread
fun WCDB.writeRoomSendingStatus(roomId: String, status: Int) {
    room.updateRow(
        arrayOf(Value(status)),
        arrayOf(DBRoomModel.sendingStatus),
        DBRoomModel.roomId.eq(roomId)
    )
}






@WorkerThread
fun WCDB.clearRoomSendingStatusIfNoSending(roomId: String) {
    val stillSending = StatementSelect()
        .select(DBMessageModel.id)
        .from("message")
        .where(sendingOutgoingCondition(roomId))
        .limit(1)
    room.updateRow(
        arrayOf(Value(ROOM_SENDING_STATUS_NONE)),
        arrayOf(DBRoomModel.sendingStatus),
        DBRoomModel.roomId.eq(roomId)
            .and(DBRoomModel.sendingStatus.notEq(ROOM_SENDING_STATUS_NONE))
            .and(Expression.notExists(stillSending))
    )
}








@WorkerThread
fun WCDB.roomIdsWithSendingStatusFlagged(): List<String> =
    room.getOneColumnString(
        DBRoomModel.roomId,
        DBRoomModel.sendingStatus.notEq(ROOM_SENDING_STATUS_NONE)
    ).filter { !it.isNullOrEmpty() }
