package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.appScope
import org.difft.app.database.convertToConfidentialPlaceholder
import org.difft.app.database.delete
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBMessageStore
import difft.android.messageserialization.model.ReadPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBReadInfoModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.getGroupMemberCount
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptMessageHelper @Inject constructor(
    private val dbMessageStore: DBMessageStore
) {
    private data class PendingReceiptMessage(
        val message: SignalServiceProtos.ReceiptMessage,
        val signalService: SignalServiceDataClass
    )

    private val receiptMessageChannel = Channel<PendingReceiptMessage>(Channel.BUFFERED)

    init {
        appScope.launch(Dispatchers.IO) {
            processReceiptMessages()
        }
    }

    private suspend fun processReceiptMessages() {
        while (true) {
            try {
                val pendingMessage = receiptMessageChannel.receive()
                processReceiptMessage(pendingMessage.message, pendingMessage.signalService)
            } catch (e: Exception) {
                L.e(e) { "[ReceiptMessageHelper] Error processing receipt message, continuing with next message" }
            }
        }
    }

    suspend fun handleReceiptMessage(message: SignalServiceProtos.ReceiptMessage, signalService: SignalServiceDataClass) {
        try {
            receiptMessageChannel.send(PendingReceiptMessage(message, signalService))
        } catch (e: Exception) {
            L.e(e) { "[ReceiptMessageHelper] Failed to send message to channel: ${e.message}" }
        }
    }

    private suspend fun processReceiptMessage(receiptMessage: SignalServiceProtos.ReceiptMessage, signalService: SignalServiceDataClass) {
        L.i { "[ReceiptMessageHelper] Processing receipt message -> ${receiptMessage.readPosition}" }
        var readPosition: ReadPosition? = null
        if (receiptMessage.readPosition != null) {
            readPosition = ReadPosition(
                groupId = signalService.conversation.id.takeIf { signalService.conversation is For.Group },
                readAt = receiptMessage.readPosition.readAt,
                maxServerTime = receiptMessage.readPosition.maxServerTime,
                maxNotifySequenceId = receiptMessage.readPosition.maxNotifySequenceId,
                maxSequenceId = receiptMessage.readPosition.maxSequenceId
            )
        }
        val mode = if (receiptMessage.hasMessageMode()) receiptMessage.messageMode.number else SignalServiceProtos.Mode.NORMAL_VALUE

        if (signalService.conversation is For.Group) {
            val groupId = signalService.conversation.id

            // 如果是大群（群人数大于阈值），不处理已读回执（机密消息除外）
            val threshold = globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.group?.chatWithoutReceiptThreshold ?: Double.MAX_VALUE
            val memberCount = wcdb.getGroupMemberCount(groupId)
            if (memberCount > threshold && mode != SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                L.i { "[ReceiptMessageHelper] Large group with $memberCount members (threshold: $threshold), skipping read receipt processing" }
                return
            }

            if (mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                // Group confidential: convert to placeholder when any member reads
                receiptMessage.timestampList?.forEach { timestamp ->
                    val originalMessage = wcdb.message.getFirstObject(DBMessageModel.timeStamp.eq(timestamp)) ?: run {
                        L.i { "[Message] can't find the original group confidential message, message timestamp:${timestamp}" }
                        dbMessageStore.savePendingMessage(signalService.messageId, timestamp, signalService.signalServiceEnvelope.toByteArray())
                        return@forEach
                    }
                    L.i { "[Message] convert group confidential message to placeholder -> timestamp:${timestamp}, viewer:${signalService.senderId}" }
                    originalMessage.convertToConfidentialPlaceholder()
                }
            } else {
                if (readPosition == null) return
                updateReadInfo(signalService.conversation.id, signalService.senderId, readPosition.maxServerTime)
            }
        } else {
            if (mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                // 1-on-1 confidential: convert to placeholder when recipient reads
                receiptMessage.timestampList?.forEach { timestamp ->
                    val originalMessage = wcdb.message.getFirstObject(DBMessageModel.timeStamp.eq(timestamp)) ?: run {
                        L.i { "[Message] can't find the original confidential message, message timestamp:${timestamp}" }
                        dbMessageStore.savePendingMessage(signalService.messageId, timestamp, signalService.signalServiceEnvelope.toByteArray())
                        return@forEach
                    }
                    L.i { "[Message] convert confidential message to placeholder -> timestamp:${timestamp}" }
                    originalMessage.convertToConfidentialPlaceholder()
                }
            } else {
                if (readPosition == null) return
                updateReadInfo(signalService.conversation.id, signalService.senderId, readPosition.maxServerTime)
            }
        }
    }

    suspend fun updateReadInfo(
        conversationId: String,
        senderId: String,
        readPosition: Long
    ) {
        val currentPosition = wcdb.readInfo.getFirstObject(
            DBReadInfoModel.roomId.eq(conversationId).and(DBReadInfoModel.uid.eq(senderId))
        )?.readPosition ?: 0L
        if (readPosition > currentPosition) {
            L.i { "[ReceiptMessageHelper] Updating read position for $conversationId from $senderId, current position: $currentPosition, new position: $readPosition" }
            wcdb.readInfo.insertOrReplaceObject(ReadInfoModel().apply {
                this.roomId = conversationId
                this.uid = senderId
                this.readPosition = readPosition
            })
            RoomChangeTracker.trackRoomReadInfoUpdate(conversationId)
        } else {
            L.w { "[ReceiptMessageHelper] Ignoring read position update for $conversationId from $senderId, current position: $currentPosition, new position: $readPosition" }
        }
    }
}