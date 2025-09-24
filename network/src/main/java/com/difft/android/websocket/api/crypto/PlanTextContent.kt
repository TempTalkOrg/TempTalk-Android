package com.difft.android.websocket.api.crypto

import com.difft.android.websocket.api.messages.DetailMessageType
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import com.difft.android.websocket.internal.push.OutgoingPushMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.util.Optional

class PlanTextContent(
    val content: SignalServiceProtos.Content,
    private val plaintextContent: ByteArray,
    val groupId: Optional<ByteArray>,
    val notification: OutgoingPushMessage.Notification?
) {
    val realSource: OutgoingPushMessage.RealSourceEntity?
        get() {
            return if (content.hasDataMessage() && content.dataMessage.hasReaction() && content.dataMessage.reaction.hasSource()) {
                OutgoingPushMessage.RealSourceEntity(
                    content.dataMessage.reaction.source.timestamp,
                    content.dataMessage.reaction.source.sourceDevice,
                    content.dataMessage.reaction.source.source,
                    content.dataMessage.reaction.source.serverTimestamp,
                    0,
                    0
                )
            } else if (content.hasDataMessage() && content.dataMessage.hasRecall()) {
                OutgoingPushMessage.RealSourceEntity(
                    content.dataMessage.recall.source.timestamp,
                    content.dataMessage.recall.source.sourceDevice,
                    content.dataMessage.recall.source.source,
                    content.dataMessage.recall.source.serverTimestamp,
                    0,
                    0
                )
            } else null
        }
    val reactionInfo: OutgoingPushMessage.ReactionInfo?
        get() {
            content.dataMessage.reaction.source.timestamp
            return if (content.hasDataMessage() && content.dataMessage.hasReaction()) {
                OutgoingPushMessage.ReactionInfo(
                    content.dataMessage.reaction.emoji,
                    content.dataMessage.reaction.remove,
                    content.dataMessage.reaction.originTimestamp
                )
            } else {
                null
            }
        }
    val msgType: Int
        get() {
            var msgType = SignalServiceProtos.Envelope.MsgType.MSG_NORMAL_VALUE
            if (content.hasSyncMessage()) {
                msgType = SignalServiceProtos.Envelope.MsgType.MSG_SYNC_VALUE
                if (content.syncMessage.readList.isNotEmpty()) {
                    msgType = SignalServiceProtos.Envelope.MsgType.MSG_SYNC_READ_RECEIPT_VALUE
                }
            } else if (content.hasReceiptMessage()) {
                if (content.receiptMessage.type == SignalServiceProtos.ReceiptMessage.Type.READ) {
                    msgType = SignalServiceProtos.Envelope.MsgType.MSG_READ_RECEIPT_VALUE
                } else if (content.receiptMessage.type == SignalServiceProtos.ReceiptMessage.Type.DELIVERY) {
                    msgType = SignalServiceProtos.Envelope.MsgType.MSG_DELIVERY_RECEIPT_VALUE
                }
            } else if (content.hasDataMessage()) {
                if (content.dataMessage.hasRecall()) {
                    msgType = SignalServiceProtos.Envelope.MsgType.MSG_RECALL_VALUE
                }
            } else if (content.hasNotifyMessage()) {
                msgType = SignalServiceProtos.Envelope.MsgType.MSG_CLIENT_NOTIFY_VALUE
            }
            return msgType
        }

    val detailMessageType: Int
        get() {
            val detailMessageType = if (content.hasDataMessage()) {
                if (content.dataMessage.hasForwardContext()) {
                    DetailMessageType.Forward
                } else if (content.dataMessage.contactCount > 0) {
                    DetailMessageType.Contact
                } else if (content.dataMessage.hasRecall()) {
                    DetailMessageType.Recall
                } else if (content.dataMessage.hasReaction()) {
                    DetailMessageType.Reaction
                } else if (content.dataMessage.hasCard()) {
                    DetailMessageType.Card
                } else if (content.dataMessage.messageMode == SignalServiceProtos.Mode.CONFIDENTIAL) {
                    DetailMessageType.Confidential
                } else {
                    DetailMessageType.Unknown
                }
            } else {
                DetailMessageType.Unknown
            }
            return detailMessageType.value
        }

    val readPositionEntity: OutgoingPushMessage.ReadPositionEntity?
        get() {
            var readPositionEntity: OutgoingPushMessage.ReadPositionEntity? = null
            if (content.hasSyncMessage()) {
                if (content.syncMessage.readList.isNotEmpty()) {
                    val readPosition = content.syncMessage.readList[0].readPosition
                    if (readPosition != null) {
                        readPositionEntity = OutgoingPushMessage.ReadPositionEntity(
                            runCatching { readPosition.groupId.toByteArray().transformGroupIdFromServerToLocal() }.getOrDefault(null),
                            readPosition.readAt,
                            readPosition.maxServerTime,
                            readPosition.maxNotifySequenceId,
                            readPosition.maxSequenceId
                        )
                    }
                }
            }
            if (content.hasReceiptMessage() && content.receiptMessage.hasReadPosition()) {
                val readPosition = content.receiptMessage.readPosition
                if (readPosition != null) {
                    readPositionEntity = OutgoingPushMessage.ReadPositionEntity(
                        runCatching { readPosition.groupId.toByteArray().transformGroupIdFromServerToLocal() }.getOrDefault(null),
                        readPosition.readAt,
                        readPosition.maxServerTime,
                        readPosition.maxNotifySequenceId,
                        readPosition.maxSequenceId
                    )
                }
            }
            return readPositionEntity
        }

    fun size(): Int {
        return plaintextContent.size
    }
}
