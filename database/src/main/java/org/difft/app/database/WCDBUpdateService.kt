package org.difft.app.database

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.core.Table
import com.tencent.wcdb.winq.Order
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel

/**
 * Observes database table updates with rate limiting.
 *
 * This function:
 * - Immediately emits the first update after subscription
 * - Subsequently throttles updates to at most one every 500ms
 * - Drops intermediate updates during the throttling interval, only emitting the latest
 * - Executes the provided [queryAction] after each emission
 *
 * Use this function for UI updates or when frequent database changes need to be rate-limited.
 *
 * @param queryAction The database query to execute after each update
 * @return A Flow emitting the query results with rate limiting applied
 */
fun <T, R> Table<T>.observe(
    queryAction: suspend Table<T>.() -> R
) = observeRealtime(queryAction).sampleAfterFirst(500)

/**
 * Observes database table updates with rate limiting.
 *
 * This function:
 * - Immediately emits the first update after subscription
 * - Subsequently throttles updates to at most one every 500ms
 * - Drops intermediate updates during the throttling interval, only emitting the latest
 * - Emits Unit values that signal when table updates have occurred
 *
 * Use this function for UI updates or when frequent database changes need to be rate-limited.
 *
 * @return A Flow that emits Unit values when the table is updated, with rate limiting applied
 */
fun <T> Table<T>.observe(): Flow<Unit> = observeRealtime().sampleAfterFirst(500)


/**
 * This object don't take part in complex business logic it only used to update database or for logging
 */
object WCDBUpdateService :
    CoroutineScope by CoroutineScope(CoroutineName("WCDBUpdateService") + Dispatchers.IO + SupervisorJob()) {
    
    private var isUpdatingRoomsStarted = false
    fun start() {
        launch {
            startLoggingSql()
            startNotifyingTableUpdates()
            updatingRooms()
            cleanEmptyRooms()
            updateSavedMessageExpire()
            deleteOldRecallMessages()
//            clearInvalidGroupMembers()
        }
    }

    private fun startLoggingSql() {
        wcdb.sqlPreExecutionEvents
            .onEach { (sql, info) ->
                //log the SQL statement with the string-based `info`
                L.d { "SQL (pre-exec): $sql Info: $info" }
            }
            .launchIn(this)
    }

    private fun startNotifyingTableUpdates() {
        wcdb.sqlPostExecutionEvents
            .onEach { (sql, perfInfo) ->
//                // 1) (Optional) Log or use performance metrics
//                L.d {
//                    "Performance: $sql\n cost=${perfInfo.costInNanoseconds}ns, " +
//                            "tablePageRead=${perfInfo.tablePageReadCount}, " +
//                            "tablePageWrite=${perfInfo.tablePageWriteCount}, etc..."
//                }
                // 2) If it's an INSERT, UPDATE, or DELETE, find the table name
                if (
                    sql.contains("INSERT INTO", ignoreCase = true) ||
                    sql.contains("UPDATE", ignoreCase = true) ||
                    sql.contains("DELETE FROM", ignoreCase = true) ||
                    sql.contains("INSERT OR REPLACE INTO", ignoreCase = true)
                ) {
                    val tableName = wcdb.extractTableNameFromSQL(sql)
                    L.d { "Post-Exec Update on Table: $tableName" }

                    // 3) Notify ObservableTable
                    tableName?.lowercase()?.let { name ->
                        wcdb.tablesMap[name]?.notifyUpdate()
                    }
                }

            }
            .launchIn(this)
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

    //更新saved(收藏)里面的旧消息为不过期
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
                        L.i { "[Message][WCDBUpdateService] updating room:$roomId, changes:$roomChanges" }

                        launch {
                            try {
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
                            } catch (e: Exception) {
                                e.printStackTrace()
                                L.e { "[Message][WCDBUpdateService] Error updating room:$roomId: ${e.stackTraceToString()}" }
                                FirebaseCrashlytics.getInstance().recordException(e)
                            }
                        }
                    }
                }
            }
            .catch { e ->
                e.printStackTrace()
                L.e { "[Message][WCDBUpdateService] Error in flow:" + e.stackTraceToString() }
                FirebaseCrashlytics.getInstance().recordException(e)
            }
            .launchIn(this)
    }
}