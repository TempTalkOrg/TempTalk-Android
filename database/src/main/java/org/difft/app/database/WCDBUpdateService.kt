package org.difft.app.database

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.ActiveConversation
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.JsonObject
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Order
import org.difft.app.database.models.MessageModel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel

/**
 * Service for updating database and notifying UI changes.
 * Uses direct notification mechanism instead of SQL listener for better reliability.
 */
object WCDBUpdateService :
    CoroutineScope by CoroutineScope(CoroutineName("WCDBUpdateService") + Dispatchers.IO + SupervisorJob()) {

    private var isUpdatingRoomsStarted = false

    // Room table update notification
    private val _roomTableUpdated = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val roomTableUpdated: SharedFlow<Unit> = _roomTableUpdated.asSharedFlow()

    fun start() {
        launch {
            updatingRooms()
            updateSavedMessageExpire()
            clearInvalidGroupMembers()
        }
    }

    /**
     * Clean empty rooms
     * Conditions:
     * 1. Invalid rooms (roomId is empty)
     * 2. Empty rooms (lastDisplayContent is empty) that exceed timeout
     *    - New data: use emptyRoomSince for timeout check
     *    - Old data (emptyRoomSince is null): fallback to lastActiveTime
     */
    fun cleanEmptyRooms(activeConversationConfig: ActiveConversation) {
        try {
            val currentTime = System.currentTimeMillis()
            val groupTimeoutMillis = activeConversationConfig.group * 1000L
            val otherTimeoutMillis = activeConversationConfig.other * 1000L

            // Condition 1: Invalid rooms (roomId is empty)
            val invalidRoomCondition = DBRoomModel.roomId.isNull()
                .or(DBRoomModel.roomId.`is`(""))

            // Condition 2: Empty content condition (must confirm it's an empty room)
            val emptyContentCondition = DBRoomModel.lastDisplayContent.isNull()
                .or(DBRoomModel.lastDisplayContent.`is`(""))

            // Condition 3: Expired group rooms (use emptyRoomSince, fallback to lastActiveTime for old data)
            // New data condition: emptyRoomSince exists and has expired
            val expiredGroupNewData = DBRoomModel.roomType.eq(1)
                .and(emptyContentCondition)
                .and(DBRoomModel.emptyRoomSince.gt(0))
                .and(DBRoomModel.emptyRoomSince.add(groupTimeoutMillis).lt(currentTime))
            // Old data condition: emptyRoomSince is null, fallback to lastActiveTime
            val expiredGroupOldData = DBRoomModel.roomType.eq(1)
                .and(emptyContentCondition)
                .and(DBRoomModel.emptyRoomSince.isNull())
                .and(DBRoomModel.lastActiveTime.gt(0))
                .and(DBRoomModel.lastActiveTime.add(groupTimeoutMillis).lt(currentTime))
            val expiredGroupCondition = expiredGroupNewData.or(expiredGroupOldData)

            // Condition 4: Expired other rooms (use emptyRoomSince, fallback to lastActiveTime for old data)
            // New data condition: emptyRoomSince exists and has expired
            val expiredOtherNewData = DBRoomModel.roomType.eq(0)
                .and(emptyContentCondition)
                .and(DBRoomModel.emptyRoomSince.gt(0))
                .and(DBRoomModel.emptyRoomSince.add(otherTimeoutMillis).lt(currentTime))
            // Old data condition: emptyRoomSince is null, fallback to lastActiveTime
            val expiredOtherOldData = DBRoomModel.roomType.eq(0)
                .and(emptyContentCondition)
                .and(DBRoomModel.emptyRoomSince.isNull())
                .and(DBRoomModel.lastActiveTime.gt(0))
                .and(DBRoomModel.lastActiveTime.add(otherTimeoutMillis).lt(currentTime))
            val expiredOtherCondition = expiredOtherNewData.or(expiredOtherOldData)

            // Final condition: exclude self AND (invalid rooms OR expired groups OR expired other)
            val finalCondition = DBRoomModel.roomId.notEq(globalServices.myId)
                .and(invalidRoomCondition.or(expiredGroupCondition).or(expiredOtherCondition))

            // Get rooms to delete first
            val roomsToDelete = wcdb.room.getAllObjects(finalCondition)
            if (roomsToDelete.isEmpty()) {
                L.i { "[WCDBUpdateService] cleanEmptyRooms: no rooms to delete" }
                return
            }

            val roomIdsToDelete = roomsToDelete.map { it.roomId }
            L.i { "[WCDBUpdateService] cleanEmptyRooms: deleting ${roomIdsToDelete.size} rooms: $roomIdsToDelete" }

            // Delete all messages in these rooms (should mostly be archive system messages)
            // Use MessageModel.delete() to properly clean up related data (attachments, reactions, etc.)
            var totalDeletedCount = 0

            roomIdsToDelete.forEach { roomId ->
                val messages = wcdb.message.getAllObjects(DBMessageModel.roomId.eq(roomId))

                // Log warning for non-archive messages (shouldn't exist in empty rooms)
                val nonArchiveMessages = messages.filterNot { isArchiveExpiredSystemMessage(it) }
                if (nonArchiveMessages.isNotEmpty()) {
                    L.w { "[WCDBUpdateService] cleanEmptyRooms: Found ${nonArchiveMessages.size} non-archive messages in empty room $roomId" }
                    nonArchiveMessages.forEach { message ->
                        L.w { "[WCDBUpdateService] cleanEmptyRooms: - type=${message.type}, id=${message.id}" }
                    }
                }

                // Delete all messages with related data
                messages.forEach { it.delete() }
                totalDeletedCount += messages.size
            }
            L.i { "[WCDBUpdateService] cleanEmptyRooms: deleted $totalDeletedCount messages with related data" }

            // Delete room records
            val deletedRooms = wcdb.room.deleteObjects(finalCondition)
            L.i { "[WCDBUpdateService] cleanEmptyRooms: deleted $deletedRooms rooms" }

        } catch (e: Exception) {
            L.e(e) { "[WCDBUpdateService] cleanEmptyRooms error:" }
        }
    }

    // 更新saved(收藏)里面的旧消息为不过期
    private fun updateSavedMessageExpire() {
        wcdb.message.updateValue(
            0L,
            DBMessageModel.expiresInSeconds,
            DBMessageModel.roomId.eq(globalServices.myId)
        )
    }

    fun updatingRooms() {
        if (isUpdatingRoomsStarted) {
            L.d { "[WCDBUpdateService] updatingRooms already started, skipping duplicate registration" }
            return
        }
        isUpdatingRoomsStarted = true

        L.i { "[WCDBUpdateService] Starting room updates listener" }
        RoomChangeTracker.roomChanges
            .sampleAfterFirst(500)
            .onEach { changes ->
                // 按房间ID分组，合并同一房间的多个变更
                val changesByRoom = changes.groupBy { it.roomId }

                // 一次性获取所有需要更新的房间对象
                val roomIds = changesByRoom.keys.toList()
                val roomObjects = wcdb.room.getAllObjects(DBRoomModel.roomId.`in`(roomIds))
                    .associateBy { it.roomId }

                coroutineScope {
                    changesByRoom.forEach { (roomId, roomChanges) ->
                        val roomObject = roomObjects[roomId] ?: return@forEach

                        launch {
                            try {
                                L.i { "[Message][WCDBUpdateService] updating room:$roomId, changes:$roomChanges" }

                                // ✅ 检查是否需要重新查询数据
                                val needsDataUpdate = roomChanges.any {
                                    it.type != RoomChangeType.REFRESH
                                }

                                if (!needsDataUpdate) {
                                    // 只是 REFRESH 类型，数据已经更新，跳过数据查询
                                    L.d { "[Message][WCDBUpdateService] Room $roomId: REFRESH only, skipping data queries" }
                                    return@launch
                                }

                                // 以下是需要重新查询数据的逻辑
                                if (roomChanges.any { it.type == RoomChangeType.MESSAGE }) {
                                    // 获取最新消息
                                    val previewMessage = wcdb.message.getFirstObject(
                                        DBMessageModel.roomId.eq(roomId),
                                        DBMessageModel.systemShowTimestamp.order(Order.Desc)
                                    )

                                    if (previewMessage == null) {
                                        // No messages, set emptyRoomSince (if not set), keep lastActiveTime unchanged
                                        val emptyRoomSince = roomObject.emptyRoomSince ?: System.currentTimeMillis()
                                        if (roomObject.lastDisplayContent != "" || roomObject.emptyRoomSince == null) {
                                            wcdb.room.updateRow(
                                                arrayOf(Value(""), Value(emptyRoomSince)),
                                                arrayOf(DBRoomModel.lastDisplayContent, DBRoomModel.emptyRoomSince),
                                                DBRoomModel.roomId.eq(roomId)
                                            )
                                        }
                                        roomObject.resetRoomUnreadState()
                                    } else {
                                        val lastActiveTime = previewMessage.systemShowTimestamp

                                        // Check if it's an archive system message
                                        val isArchiveMessage = isArchiveExpiredSystemMessage(previewMessage)

                                        if (isArchiveMessage) {
                                            // Archive system message: clear lastDisplayContent, set emptyRoomSince, keep lastActiveTime unchanged
                                            val emptyRoomSince = roomObject.emptyRoomSince ?: System.currentTimeMillis()
                                            if (roomObject.lastDisplayContent != "" || roomObject.emptyRoomSince == null) {
                                                wcdb.room.updateRow(
                                                    arrayOf(Value(""), Value(emptyRoomSince)),
                                                    arrayOf(DBRoomModel.lastDisplayContent, DBRoomModel.emptyRoomSince),
                                                    DBRoomModel.roomId.eq(roomId)
                                                )
                                            }
                                        } else {
                                            // Normal message: update content and time, clear emptyRoomSince
                                            val lastDisplayContent = previewMessage.previewContent()

                                            // Only update lastActiveTime if:
                                            // 1. New message time is greater (normal case, avoid time rollback on recall/delete)
                                            // 2. Or current time is 0 (new room initialization, though covered by case 1)
                                            val shouldUpdateTime = lastActiveTime > roomObject.lastActiveTime || roomObject.lastActiveTime == 0L
                                            val finalLastActiveTime = if (shouldUpdateTime) lastActiveTime else roomObject.lastActiveTime

                                            val needsUpdate = roomObject.lastDisplayContent != lastDisplayContent
                                                || roomObject.lastActiveTime != finalLastActiveTime
                                                || roomObject.emptyRoomSince != null

                                            if (needsUpdate) {
                                                wcdb.room.updateRow(
                                                    arrayOf(Value(lastDisplayContent), Value(finalLastActiveTime), Value()),
                                                    arrayOf(DBRoomModel.lastDisplayContent, DBRoomModel.lastActiveTime, DBRoomModel.emptyRoomSince),
                                                    DBRoomModel.roomId.eq(roomId)
                                                )
                                            }
                                        }

                                        // Update unread status
                                        if (lastActiveTime == 0L) {
                                            roomObject.resetRoomUnreadState()
                                        } else if (roomObject.readPosition < lastActiveTime) {
                                            roomObject.updateRoomUnreadState()
                                        } else {
                                            roomObject.resetRoomUnreadState()
                                        }
                                    }
                                }

                                // 检查是否需要更新联系人相关的内容
                                if (roomChanges.any { it.type == RoomChangeType.CONTACT || it.type == RoomChangeType.GROUP_MEMBER }) {
                                    val contactorModel = wcdb.getContactorFromAllTable(roomId) ?: return@launch
                                    val roomName = contactorModel.getDisplayNameForUI()
                                    val roomAvatar = contactorModel.avatar

                                    wcdb.room.updateRow(
                                        arrayOf(Value(roomName), Value(roomAvatar)),
                                        arrayOf(DBRoomModel.roomName, DBRoomModel.roomAvatarJson),
                                        DBRoomModel.roomId.eq(roomId)
                                    )
                                }

                                // 检查是否需要更新群组相关的内容
                                if (roomChanges.any { it.type == RoomChangeType.GROUP }) {
                                    val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(roomId)) ?: return@launch
                                    val groupName = group.name
                                    val groupAvatarJson = group.avatar
                                    val groupMembersNumber = wcdb.groupMemberContactor.getValue(
                                        DBGroupMemberContactorModel.databaseId.count(),
                                        DBGroupMemberContactorModel.gid.eq(roomId)
                                    )?.int ?: 0

                                    wcdb.room.updateRow(
                                        arrayOf(Value(groupName), Value(groupAvatarJson), Value(groupMembersNumber)),
                                        arrayOf(DBRoomModel.roomName, DBRoomModel.roomAvatarJson, DBRoomModel.groupMembersNumber),
                                        DBRoomModel.roomId.eq(roomId)
                                    )
                                }

                                // 如果房间名称为空，尝试更新
                                if (roomObject.roomName.isNullOrEmpty()) {
                                    roomObject.updateRoomNameAndAvatar()
                                }

                                L.d { "[WCDBUpdateService] Room $roomId updated successfully" }

                            } catch (e: Exception) {
                                L.e(e) { "[Message][WCDBUpdateService] Error updating room:$roomId:" }
                                FirebaseCrashlytics.getInstance().recordException(e)
                            }
                        }
                    }
                }

                // ✅ Batch处理完成后统一emit，触发UI刷新
                _roomTableUpdated.tryEmit(Unit)
                L.i { "[WCDBUpdateService] Batch processing completed, notification emitted" }
            }
            .catch { e ->
                L.e(e) { "[Message][WCDBUpdateService] Error in flow:" }
                FirebaseCrashlytics.getInstance().recordException(e)
            }
            .launchIn(this)
    }

    // Earlier messages expired 系统消息的 actionType
    // 注意：与 TTNotifyMessage.NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED 保持一致
    private const val NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED = 10012

    /**
     * 判断是否是归档系统消息（Earlier messages expired）
     * 用于判断是否需要更新会话预览内容
     */
    private fun isArchiveExpiredSystemMessage(message: MessageModel): Boolean {
        if (message.type != 2) return false // 非 Notify 消息
        return try {
            val json = globalServices.gson.fromJson(message.messageText, JsonObject::class.java)
            val data = json?.get("data")?.asJsonObject
            val actionType = data?.get("actionType")?.asInt
            actionType == NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED
        } catch (e: Exception) {
            false
        }
    }
}