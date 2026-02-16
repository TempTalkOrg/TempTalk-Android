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
            deleteOldRecallMessages()
//            clearInvalidGroupMembers()
        }
    }

    /**
     * 清理空会话
     * 条件：
     * 1. lastActiveTime 为空或0 或 roomId 为空（无效会话）
     * 2. lastActiveTime 超过配置时间（超时会话，配置为0表示立即删除）
     */
    fun cleanEmptyRooms(activeConversationConfig: ActiveConversation) {
        try {
            val currentTime = System.currentTimeMillis()
            val groupTimeoutMillis = activeConversationConfig.group * 1000L
            val otherTimeoutMillis = activeConversationConfig.other * 1000L

            // 条件1：无效会话（lastActiveTime 为空或0，或 roomId 为空）
            val invalidRoomCondition = DBRoomModel.lastActiveTime.isNull()
                .or(DBRoomModel.lastActiveTime.eq(0L))
                .or(DBRoomModel.roomId.isNull())
                .or(DBRoomModel.roomId.`is`(""))

            // 条件2：超时的空会话（lastDisplayContent 为空）
            val emptyContentCondition = DBRoomModel.lastDisplayContent.isNull()
                .or(DBRoomModel.lastDisplayContent.`is`(""))
            val expiredGroupCondition = DBRoomModel.roomType.eq(1)
                .and(emptyContentCondition)
                .and(DBRoomModel.lastActiveTime.add(groupTimeoutMillis).lt(currentTime))
            val expiredOtherCondition = DBRoomModel.roomType.eq(0)
                .and(emptyContentCondition)
                .and(DBRoomModel.lastActiveTime.add(otherTimeoutMillis).lt(currentTime))

            // 最终条件：排除自己 AND (无效会话 OR 超时群聊 OR 超时单聊)
            val finalCondition = DBRoomModel.roomId.notEq(globalServices.myId)
                .and(invalidRoomCondition.or(expiredGroupCondition).or(expiredOtherCondition))

            wcdb.room.deleteObjects(finalCondition)
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
                                        // 没有消息，只清空 lastDisplayContent，保留 lastActiveTime 用于超时清理
                                        if (roomObject.lastDisplayContent != "") {
                                            wcdb.room.updateRow(
                                                arrayOf(Value("")),
                                                arrayOf(DBRoomModel.lastDisplayContent),
                                                DBRoomModel.roomId.eq(roomId)
                                            )
                                        }
                                        roomObject.resetRoomUnreadState()
                                    } else {
                                        val lastActiveTime = previewMessage.systemShowTimestamp

                                        // 检查是否是归档系统消息
                                        val isArchiveMessage = isArchiveExpiredSystemMessage(previewMessage)

                                        if (isArchiveMessage) {
                                            // 归档系统消息：更新 lastActiveTime，lastDisplayContent 置空
                                            if (roomObject.lastDisplayContent != "" || roomObject.lastActiveTime != lastActiveTime) {
                                                wcdb.room.updateRow(
                                                    arrayOf(Value(""), Value(lastActiveTime)),
                                                    arrayOf(DBRoomModel.lastDisplayContent, DBRoomModel.lastActiveTime),
                                                    DBRoomModel.roomId.eq(roomId)
                                                )
                                            }
                                        } else {
                                            // 普通消息：更新内容和时间
                                            val lastDisplayContent = previewMessage.previewContent()
                                            if (roomObject.lastDisplayContent != lastDisplayContent || roomObject.lastActiveTime != lastActiveTime) {
                                                wcdb.room.updateRow(
                                                    arrayOf(Value(lastDisplayContent), Value(lastActiveTime)),
                                                    arrayOf(DBRoomModel.lastDisplayContent, DBRoomModel.lastActiveTime),
                                                    DBRoomModel.roomId.eq(roomId)
                                                )
                                            }
                                        }

                                        // 更新未读状态
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