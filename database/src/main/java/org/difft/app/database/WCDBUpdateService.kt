package org.difft.app.database

import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateDataStoreEntryPoint
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.user.ActiveConversation
import com.difft.android.base.utils.RoomChange
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.application
import com.difft.android.base.utils.dbKeyFailSoftExceptionHandler
import com.difft.android.base.utils.globalServices
import dagger.hilt.android.EntryPointAccessors
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for updating database and notifying UI changes.
 * Uses direct notification mechanism instead of SQL listener for better reliability.
 */
object WCDBUpdateService :
    CoroutineScope by CoroutineScope(CoroutineName("WCDBUpdateService") + Dispatchers.IO + SupervisorJob() + dbKeyFailSoftExceptionHandler) {

    private val isUpdatingRoomsStarted = AtomicBoolean(false)

    // Room changes merged by roomId, drained fairly. Replaces sampleAfterFirst, which kept only
    // the latest batch and let a busy room starve quiet 1:1 conversations.
    private val pendingRoomChanges = LinkedHashMap<String, MutableSet<RoomChangeType>>()
    private val pendingRoomChangesMutex = Mutex()
    private val drainSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    // Room table update notification
    private val _roomTableUpdated = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val roomTableUpdated: SharedFlow<Unit> = _roomTableUpdated.asSharedFlow()

    fun start() {
        launch {
            // Fail-soft: skip all DB work if the cipher key is unavailable (one-way process-lifetime
            // flag). The CEH on this scope is the crash-safety net if the flag flips mid-run.
            if (wcdb.isDbInaccessible) {
                L.w { "[WCDBUpdateService] skip start, DB inaccessible" }
                return@launch
            }
            updatingRooms()
            updateSavedMessageExpire()
            migrateArchiveTombstoneSortSentinel()
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
    suspend fun cleanEmptyRooms(activeConversationConfig: ActiveConversation) = withContext(Dispatchers.IO) {
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

            // Pinned conversations are kept even when empty/expired — only their messages
            // are auto-archived (by MessageArchiveManager); the room row itself stays.
            val notPinnedCondition = DBRoomModel.pinnedTime.isNull()

            // Final condition: exclude self AND not pinned AND (invalid rooms OR expired empty rooms)
            val finalCondition = DBRoomModel.roomId.notEq(globalServices.myId)
                .and(notPinnedCondition)
                .and(invalidRoomCondition.or(expiredGroupCondition).or(expiredOtherCondition))

            // Get rooms to delete first
            val roomsToDelete = wcdb.room.getAllObjects(finalCondition)
            if (roomsToDelete.isEmpty()) {
                L.i { "[WCDBUpdateService] cleanEmptyRooms: no rooms to delete" }
                return@withContext
            }

            val roomIdsToDelete = roomsToDelete.map { it.roomId }
            L.i { "[WCDBUpdateService] cleanEmptyRooms: deleting ${roomIdsToDelete.size} rooms: $roomIdsToDelete" }

            // Delete all messages in these rooms (should mostly be archive system messages).
            // #909 R3: use deleteMessagesPaged so we never materialize an entire room's messages
            // into memory (same unbounded getAllObjects anti-pattern the issue removed elsewhere).
            roomIdsToDelete.forEach { roomId ->
                // Diagnostic: warn if any business (non-Notify) message survives in an "empty" room.
                // Archive-expired system messages are TYPE_NOTIFY (actionType 10012); a non-Notify
                // message here is unexpected. Checked via COUNT (no object load) — this is a SQL
                // approximation of the old per-row isArchiveExpiredSystemMessage filter (which parses
                // messageText JSON and cannot be expressed in SQL); it narrows to "stray business
                // messages", the case the warning actually exists to surface.
                val nonArchiveCount = messageCount(
                    DBMessageModel.roomId.eq(roomId)
                        .and(DBMessageModel.type.notEq(MessageModel.TYPE_NOTIFY))
                )
                if (nonArchiveCount > 0) {
                    L.w { "[WCDBUpdateService] cleanEmptyRooms: Found $nonArchiveCount non-archive (non-Notify) messages in empty room $roomId" }
                }

                // Delete all messages with related data (per-row delete preserved by the helper).
                // #969: snapshot the upper bound (max databaseId) before deleting so concurrent
                // inserts during the paged delete cannot extend the unbounded `roomId.eq` match set
                // (the same non-convergence under flood that DBMessageStore.removeRoomAndMessages
                // fixes). Empty room → snapshotMax 0 → le(0) matches nothing → first page breaks.
                val snapshotMax = maxMessageDatabaseId(roomId)
                deleteMessagesPaged(
                    DBMessageModel.roomId.eq(roomId).and(DBMessageModel.databaseId.le(snapshotMax))
                )
            }
            L.i { "[WCDBUpdateService] cleanEmptyRooms: deleted messages with related data for ${roomIdsToDelete.size} rooms" }

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

    // In-process single-run guard: start() has no reentry guard, and two concurrent dedup passes
    // could keep different rows (getAllObjects order is unspecified) and delete both tombstones
    private val isTombstoneMigrationStarted = AtomicBoolean(false)

    /**
     * Re-anchor legacy archive tombstones (dynamic sort key) to the fixed sentinel, and collapse
     * per-room duplicates left by the old delete-then-recreate flow (the exists-then-skip path
     * never deletes, so this is where duplicates converge). Idempotent; a persisted app_state flag
     * skips the scan after one success — set only on completion, so a failed run retries next start.
     */
    private suspend fun migrateArchiveTombstoneSortSentinel() {
        if (!isTombstoneMigrationStarted.compareAndSet(false, true)) return
        val dataStore = runCatching {
            EntryPointAccessors.fromApplication(application, AppStateDataStoreEntryPoint::class.java)
                .appStateDataStore()
        }.getOrNull() ?: return
        val migrated = dataStore.data.first()[AppStateKeys.ARCHIVE_TOMBSTONE_SENTINEL_MIGRATED]
            ?: AppStateDefaults.ARCHIVE_TOMBSTONE_SENTINEL_MIGRATED
        if (migrated) return
        reAnchorLegacyArchiveTombstones()
        dataStore.edit { it[AppStateKeys.ARCHIVE_TOMBSTONE_SENTINEL_MIGRATED] = true }
    }

    // messageText LIKE pre-filter narrows the scan to tombstone candidates; the per-row JSON
    // check confirms the match (actionType is not a DB column).
    private fun reAnchorLegacyArchiveTombstones() {
        val tombstones = wcdb.message.getAllObjects(
            DBMessageModel.type.eq(MessageModel.TYPE_NOTIFY)
                .and(DBMessageModel.messageText.like("%\"actionType\":$NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED%"))
        ).filter { isArchiveExpiredSystemMessage(it) }

        val duplicates = tombstones.groupBy { it.roomId }.values.flatMap { it.drop(1) }
        duplicates.forEach { it.delete() }

        val toMigrate = (tombstones - duplicates.toSet())
            .filter { it.systemShowTimestamp != MessageModel.ARCHIVE_TOMBSTONE_SORT_SENTINEL }
        toMigrate.forEach { message ->
            wcdb.message.updateValue(
                MessageModel.ARCHIVE_TOMBSTONE_SORT_SENTINEL,
                DBMessageModel.systemShowTimestamp,
                DBMessageModel.id.eq(message.id)
            )
        }
        if (toMigrate.isNotEmpty() || duplicates.isNotEmpty()) {
            L.i { "[WCDBUpdateService] archive tombstone migration reAnchored=${toMigrate.size} duplicatesRemoved=${duplicates.size}" }
        }
    }

    fun updatingRooms() {
        // Atomic check-then-act: exactly one caller registers the collector, concurrent callers no-op.
        if (!isUpdatingRoomsStarted.compareAndSet(false, true)) return

        L.i { "[WCDBUpdateService] Starting room updates listener" }

        // Collector: merge each change into pendingRoomChanges (cheap, never drops), then signal.
        val collectorJob = launch {
            RoomChangeTracker.roomChanges.collect { changes ->
                pendingRoomChangesMutex.withLock {
                    changes.forEach { pendingRoomChanges.getOrPut(it.roomId) { mutableSetOf() }.add(it.type) }
                    // Signal inside the lock so write + signal are atomic vs collectorJob.cancel()
                    // (trySend is non-suspending, so it's safe to call while holding the mutex).
                    drainSignal.trySend(Unit)
                }
            }
        }

        // Drainer: on each signal, process ALL pending rooms (deduped) so a busy room can't starve
        // quiet ones. RoomChangeTracker already throttles emissions to ~500ms.
        drainedRoomChanges()
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
                // Drainer crashed: stop the collector too (SupervisorJob won't) so re-registration is clean.
                collectorJob.cancel()
                isUpdatingRoomsStarted.set(false)
            }
            .launchIn(this)
    }

    // Emits the merged pending changes once per drain signal (conflated). Merges by roomId so no
    // room is dropped; the conflated signal collapses bursts that arrive while a drain is in flight.
    private fun drainedRoomChanges(): Flow<List<RoomChange>> = flow {
        for (signal in drainSignal) {
            val batch = pendingRoomChangesMutex.withLock {
                if (pendingRoomChanges.isEmpty()) return@withLock null
                pendingRoomChanges.flatMap { (roomId, types) -> types.map { RoomChange(roomId, it) } }
                    .also { pendingRoomChanges.clear() }
            }
            if (batch != null) emit(batch)
        }
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