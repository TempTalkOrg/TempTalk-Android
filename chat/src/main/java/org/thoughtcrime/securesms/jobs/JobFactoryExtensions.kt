package org.thoughtcrime.securesms.jobs

import com.difft.android.PushReadReceiptSendJobFactory
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ReadPosition
import com.google.protobuf.ByteString
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.readPosition

fun PushReadReceiptSendJobFactory.create(
    recipientId: String,
    forWhat: For,
    messageTimeStamps: List<Long>,
    readPosition: ReadPosition,
    messageMode: Int,
    sendReceiptToSender: Boolean = true,  // 默认发送已读回执
    sendSyncToSelf: Boolean = true        // 默认发送同步消息
): PushReadReceiptSendJob {
    val readPos = readPosition {
        readAt = readPosition.readAt
        (if (forWhat is For.Group) ByteString.copyFrom(forWhat.id.toByteArray()) else null)?.let {
            this.groupId = it
        }
        maxNotifySequenceId = readPosition.maxNotifySequenceId
        maxServerTime = readPosition.maxServerTime
        maxSequenceId = readPosition.maxSequenceId
    }
    val mode = SignalServiceProtos.Mode.valueOf(messageMode)
    return create(null, recipientId, messageTimeStamps, readPos, mode, forWhat.id, sendReceiptToSender, sendSyncToSelf)
}
