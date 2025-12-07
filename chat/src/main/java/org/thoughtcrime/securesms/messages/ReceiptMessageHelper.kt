package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.appScope
import org.difft.app.database.delete
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.message.parseReadInfo
import com.difft.android.chat.message.parseReceiverIds
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBMessageStore
import difft.android.messageserialization.model.ReadInfoOfDb
import difft.android.messageserialization.model.ReadPosition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBReadInfoModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.getGroupMemberCount
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptMessageHelper @Inject constructor(
    private val dbMessageStore: DBMessageStore,
    private val gson: Gson,
    private val userManager: UserManager
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

        appScope.launch(Dispatchers.IO) {
            delay(3000)
            migrateMessageReadInfo()
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
            L.e(e) { "[ReceiptMessageHelper] Failed to send message to channel: $message" }
        }
    }

    private val readInfoMapType: Type by lazy {
        object : TypeToken<Map<String, ReadInfoOfDb>>() {}.type
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
                receiptMessage.timestampList?.forEach { timestamp ->
                    val originalMessage = wcdb.message.getFirstObject(DBMessageModel.timeStamp.eq(timestamp)) ?: run {
                        L.i { "[Message] can't find the original confidential message to update read info, message timestamp:${timestamp}" }
                        dbMessageStore.savePendingMessage(signalService.messageId, timestamp, signalService.signalServiceEnvelope.toByteArray())
                        return@forEach
                    }
                    val receiverIds = parseReceiverIds(originalMessage.receiverIds)
                    val readInfoMap: MutableMap<String, ReadInfoOfDb> = originalMessage.readInfo?.let { infoJson ->
                        gson.fromJson(infoJson, readInfoMapType)
                    } ?: mutableMapOf()

                    readInfoMap[signalService.senderId] = ReadInfoOfDb(timestamp)

                    if (!receiverIds.isNullOrEmpty() && readInfoMap.keys.containsAll(receiverIds)) {
                        originalMessage.delete()
                    } else {
                        originalMessage.readInfo = gson.toJson(readInfoMap)
                        wcdb.message.updateObject(
                            originalMessage,
                            arrayOf(DBMessageModel.readInfo),
                            DBMessageModel.databaseId.eq(originalMessage.databaseId)
                        )
                    }
                    RoomChangeTracker.trackRoom(groupId, RoomChangeType.MESSAGE)
                }
            } else {
                if (readPosition == null) return
                updateReadInfo(signalService.conversation.id, signalService.senderId, readPosition.maxServerTime)
            }
        } else {
            if (mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                receiptMessage.timestampList?.forEach { timestamp ->
                    val originalMessage = wcdb.message.getFirstObject(DBMessageModel.timeStamp.eq(timestamp)) ?: run {
                        L.i { "[Message] can't find the original confidential message to delete, message timestamp:${timestamp}" }
                        dbMessageStore.savePendingMessage(signalService.messageId, timestamp, signalService.signalServiceEnvelope.toByteArray())
                        return@forEach
                    }
                    L.i { "[Message] delete confidential message -> timestamp:${timestamp}" }
                    originalMessage.delete()
                    RoomChangeTracker.trackRoom(signalService.senderId, RoomChangeType.MESSAGE)
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

    private suspend fun migrateMessageReadInfo() {
        L.i { "[ReceiptMessageHelper] Starting read info migration..." }
        if (userManager.getUserData()?.migratedReadInfo == true) {
            L.i { "[ReceiptMessageHelper] Read info migration already completed, skipping." }
            return
        }
        wcdb.room.allObjects.forEach { room ->
            try {
                L.i { "[ReceiptMessageHelper] Start migrating read positions for room ${room.roomId}" }

                val readInfos = wcdb.message.getAllObjects(
                    DBMessageModel.roomId.eq(room.roomId)
                        .and(DBMessageModel.mode.notEq(1))
                        .and(DBMessageModel.readInfo.notNull())
                ).map { parseReadInfo(it.readInfo) }

                if (readInfos.isEmpty()) {
                    L.i { "[ReceiptMessageHelper] No read info to migrate for room ${room.roomId}, skipping." }
                    return@forEach
                }

                // Group read positions by user and find maximum position for each user
                val maxReadPositions = mutableMapOf<String, Long>()
                readInfos.forEach { readInfoMap ->
                    readInfoMap?.forEach { (userId, readInfo) ->
                        val currentMax = maxReadPositions[userId] ?: 0L
                        if (readInfo.readTimestamp > currentMax) {
                            maxReadPositions[userId] = readInfo.readTimestamp
                        }
                    }
                }

                // Create ReadInfoModel objects for bulk insert
                val readInfoModels = maxReadPositions.map { (userId, maxPosition) ->
                    ReadInfoModel().apply {
                        this.roomId = room.roomId
                        this.uid = userId
                        this.readPosition = maxPosition
                    }
                }

                // Bulk insert or replace read info records
                if (readInfoModels.isNotEmpty()) {
                    L.i { "[ReceiptMessageHelper] Migrating read positions for room ${room.roomId}, updating ${readInfoModels.size} users" }
                    wcdb.readInfo.insertOrReplaceObjects(readInfoModels)
                }

                if (room.roomType == 1) {
                    try {
                        // For group chats, we also need to update the receiverIds of messages
                        val receiverIdList = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.gid.eq(room.roomId)).map { it.id } - globalServices.myId
                        wcdb.message.updateValue(
                            globalServices.gson.toJson(receiverIdList),
                            DBMessageModel.receiverIds,
                            DBMessageModel.roomId.eq(room.roomId)
                                .and(DBMessageModel.fromWho.eq(globalServices.myId))
                                .and(DBMessageModel.receiverIds.isNull())
                        )
                    } catch (e: Exception) {
                        L.e(e) { "[ReceiptMessageHelper] Failed to update receiverIds for group ${room.roomId}" }
                    }
                }

                L.i { "[ReceiptMessageHelper] Successfully migrated room ${room.roomId}" }
            } catch (e: Exception) {
                L.e(e) { "[ReceiptMessageHelper] Failed to migrate room ${room.roomId}" }
            }
        }
        L.i { "[ReceiptMessageHelper] Migration completed" }
        userManager.update { this.migratedReadInfo = true }
    }
}