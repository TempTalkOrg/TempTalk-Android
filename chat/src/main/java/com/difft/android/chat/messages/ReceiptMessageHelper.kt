package com.difft.android.chat.messages

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
import org.difft.app.database.isGroupMember
import org.difft.app.database.models.MessageModel
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import javax.inject.Inject
import javax.inject.Singleton

private const val ROOM_TYPE_ONE_TO_ONE = 0
private const val ROOM_TYPE_GROUP = 1

/**
 * The message a confidential receipt from [senderId] may burn, among the stored messages that share
 * the receipt timestamp: one I sent, sent as confidential, in the 1:1 conversation with the sender
 * or in a group the sender belongs to (locally known membership). Same rule as Mac
 * `read_receipts.js` and the iOS confidential check. Null when nothing qualifies.
 */
internal fun pickConfidentialReceiptTarget(
    candidates: List<MessageModel>,
    myId: String,
    senderId: String,
    isSenderInGroup: (gid: String) -> Boolean,
): MessageModel? = candidates.firstOrNull { message ->
    message.fromWho == myId &&
        message.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE &&
        when (message.roomType) {
            ROOM_TYPE_ONE_TO_ONE -> message.roomId == senderId
            ROOM_TYPE_GROUP -> message.roomId?.let(isSenderInGroup) == true
            else -> false
        }
}

// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
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
        L.i { "[ReceiptMessageHelper] receipt type=${receiptMessage.type} hasReadPosition=${receiptMessage.hasReadPosition()} readAt=${receiptMessage.readPosition.readAt} maxServerTime=${receiptMessage.readPosition.maxServerTime}" }
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
                convertConfidentialTargets(receiptMessage, signalService)
            } else {
                if (readPosition == null) return
                updateReadInfo(signalService.conversation.id, signalService.senderId, readPosition.maxServerTime)
            }
        } else {
            if (mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                convertConfidentialTargets(receiptMessage, signalService)
            } else {
                if (readPosition == null) return
                updateReadInfo(signalService.conversation.id, signalService.senderId, readPosition.maxServerTime)
            }
        }
    }

    /**
     * A confidential receipt burns my sent copy into a placeholder. Only a message that was sent
     * as confidential, in a conversation the receipt sender belongs to, qualifies (see
     * [pickConfidentialReceiptTarget]); a receipt can never wipe a normal message.
     */
    private fun convertConfidentialTargets(
        receiptMessage: SignalServiceProtos.ReceiptMessage,
        signalService: SignalServiceDataClass,
    ) {
        val senderId = signalService.senderId
        receiptMessage.timestampList?.forEach { timestamp ->
            val candidates = wcdb.message.getAllObjects(DBMessageModel.timeStamp.eq(timestamp))
            if (candidates.isEmpty()) {
                L.i { "[Message] confidential receipt target not stored yet ts=$timestamp viewer=$senderId" }
                dbMessageStore.savePendingMessage(signalService.messageId, timestamp, signalService.signalServiceEnvelope.toByteArray())
                return@forEach
            }
            val target = pickConfidentialReceiptTarget(candidates, globalServices.myId, senderId) { gid ->
                wcdb.isGroupMember(gid, senderId)
            }
            if (target == null) {
                L.w { "[Message] skip confidential receipt: no confidential message of mine in a conversation with the viewer ts=$timestamp viewer=$senderId candidates=${candidates.size}" }
                return@forEach
            }
            L.i { "[Message] convert confidential message to placeholder ts=$timestamp viewer=$senderId room=${target.roomType}" }
            target.convertToConfidentialPlaceholder()
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