package org.difft.app.database

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Order
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
            cleanEmptyRooms()
            updateSavedMessageExpire()
            deleteOldRecallMessages()
//            clearInvalidGroupMembers()
        }
    }

    private fun cleanEmptyRooms() {
        wcdb.room.deleteObjects(
            DBRoomModel.lastActiveTime.isNull()
                .or(DBRoomModel.lastActiveTime.eq(0L))
                .or(DBRoomModel.roomId.isNull())
                .or(DBRoomModel.roomId.`is`(""))
                .and(DBRoomModel.roomId.notEq(globalServices.myId))
        )
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

                                    val (lastDisplayContent, lastActiveTime) = if (previewMessage == null) {
                                        "" to 0L
                                    } else {
                                        previewMessage.previewContent() to previewMessage.systemShowTimestamp
                                    }

                                    // 只在内容或时间发生变化时更新
                                    if (roomObject.lastDisplayContent != lastDisplayContent || roomObject.lastActiveTime != lastActiveTime) {
                                        wcdb.room.updateRow(
                                            arrayOf(Value(lastDisplayContent), Value(lastActiveTime)),
                                            arrayOf(DBRoomModel.lastDisplayContent, DBRoomModel.lastActiveTime),
                                            DBRoomModel.roomId.eq(roomId)
                                        )
                                    }

                                    // 更新未读状态
                                    if (previewMessage == null || lastActiveTime == 0L) {
                                        roomObject.resetRoomUnreadState()
                                    } else if (roomObject.readPosition < lastActiveTime) {
                                        roomObject.updateRoomUnreadState()
                                    } else {
                                        roomObject.resetRoomUnreadState()
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
                                e.printStackTrace()
                                L.e { "[Message][WCDBUpdateService] Error updating room:$roomId: ${e.stackTraceToString()}" }
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
                e.printStackTrace()
                L.e { "[Message][WCDBUpdateService] Error in flow:" + e.stackTraceToString() }
                FirebaseCrashlytics.getInstance().recordException(e)
            }
            .launchIn(this)
    }
}