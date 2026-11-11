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
import com.google.gson.JsonObject
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.Order
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import difft.android.messageserialization.model.needsSendStatusRecompute
import difft.android.messageserialization.model.resolveRoomSendStatus
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.ROOM_FOCUS_PIN_NONE
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





object WCDBUpdateService :
    CoroutineScope by CoroutineScope(CoroutineName("WCDBUpdateService") + Dispatchers.IO + SupervisorJob() + dbKeyFailSoftExceptionHandler) {

    private val isUpdatingRoomsStarted = AtomicBoolean(false)

    
    private val pendingRoomChanges = LinkedHashMap<String, MutableSet<RoomChangeType>>()
    private val pendingRoomChangesMutex = Mutex()
    private val drainSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    
    private val _roomTableUpdated = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val roomTableUpdated: SharedFlow<Unit> = _roomTableUpdated.asSharedFlow()

    fun start() {
        launch {
            
            
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

    







    suspend fun cleanEmptyRooms(activeConversationConfig: ActiveConversation) = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()
            val groupTimeoutMillis = activeConversationConfig.group * 1000L
            val otherTimeoutMillis = activeConversationConfig.other * 1000L

            
            val invalidRoomCondition = DBRoomModel.roomId.isNull()
                .or(DBRoomModel.roomId.`is`(""))

            
            val emptyContentCondition = DBRoomModel.lastDisplayContent.isNull()
                .or(DBRoomModel.lastDisplayContent.`is`(""))

            
            
            val expiredGroupNewData = DBRoomModel.roomType.eq(1)
                .and(emptyContentCondition)
                .and(DBRoomModel.emptyRoomSince.gt(0))
                .and(DBRoomModel.emptyRoomSince.add(groupTimeoutMillis).lt(currentTime))
            
            val expiredGroupOldData = DBRoomModel.roomType.eq(1)
                .and(emptyContentCondition)
                .and(DBRoomModel.emptyRoomSince.isNull())
                .and(DBRoomModel.lastActiveTime.gt(0))
                .and(DBRoomModel.lastActiveTime.add(groupTimeoutMillis).lt(currentTime))
            val expiredGroupCondition = expiredGroupNewData.or(expiredGroupOldData)

            
            
            val expiredOtherNewData = DBRoomModel.roomType.eq(0)
                .and(emptyContentCondition)
                .and(DBRoomModel.emptyRoomSince.gt(0))
                .and(DBRoomModel.emptyRoomSince.add(otherTimeoutMillis).lt(currentTime))
            
            val expiredOtherOldData = DBRoomModel.roomType.eq(0)
                .and(emptyContentCondition)
                .and(DBRoomModel.emptyRoomSince.isNull())
                .and(DBRoomModel.lastActiveTime.gt(0))
                .and(DBRoomModel.lastActiveTime.add(otherTimeoutMillis).lt(currentTime))
            val expiredOtherCondition = expiredOtherNewData.or(expiredOtherOldData)

            
            
            
            val notPinnedCondition = DBRoomModel.pinnedTime.isNull().and(DBRoomModel.focusPin.eq(ROOM_FOCUS_PIN_NONE))

            
            val finalCondition = DBRoomModel.roomId.notEq(globalServices.myId)
                .and(notPinnedCondition)
                .and(invalidRoomCondition.or(expiredGroupCondition).or(expiredOtherCondition))

            
            val roomsToDelete = wcdb.room.getAllObjects(finalCondition)
            if (roomsToDelete.isEmpty()) {
                L.i { "[WCDBUpdateService] cleanEmptyRooms: no rooms to delete" }
                return@withContext
            }

            val roomIdsToDelete = roomsToDelete.map { it.roomId }
            L.i { "[WCDBUpdateService] cleanEmptyRooms: deleting ${roomIdsToDelete.size} rooms: $roomIdsToDelete" }

            
            roomIdsToDelete.forEach { roomId ->
                
                
                val nonArchiveCount = messageCount(
                    DBMessageModel.roomId.eq(roomId)
                        .and(DBMessageModel.type.notEq(MessageModel.TYPE_NOTIFY))
                )
                if (nonArchiveCount > 0) {
                    L.w { "[WCDBUpdateService] cleanEmptyRooms: Found $nonArchiveCount non-archive (non-Notify) messages in empty room $roomId" }
                }

                
                
                
                val snapshotMax = maxMessageDatabaseId(roomId)
                deleteMessagesPaged(
                    DBMessageModel.roomId.eq(roomId).and(DBMessageModel.databaseId.le(snapshotMax))
                )
            }
            L.i { "[WCDBUpdateService] cleanEmptyRooms: deleted messages with related data for ${roomIdsToDelete.size} rooms" }

            
            val deletedRooms = wcdb.room.deleteObjects(finalCondition)
            L.i { "[WCDBUpdateService] cleanEmptyRooms: deleted $deletedRooms rooms" }

        } catch (e: Exception) {
            L.e(e) { "[WCDBUpdateService] cleanEmptyRooms error:" }
        }
    }

    
    private fun updateSavedMessageExpire() {
        wcdb.message.updateValue(
            0L,
            DBMessageModel.expiresInSeconds,
            DBMessageModel.roomId.eq(globalServices.myId)
        )
    }

    
    
    private val isTombstoneMigrationStarted = AtomicBoolean(false)

    





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
        
        if (!isUpdatingRoomsStarted.compareAndSet(false, true)) return

        L.i { "[WCDBUpdateService] Starting room updates listener" }

        
        val collectorJob = launch {
            RoomChangeTracker.roomChanges.collect { changes ->
                pendingRoomChangesMutex.withLock {
                    changes.forEach { pendingRoomChanges.getOrPut(it.roomId) { mutableSetOf() }.add(it.type) }
                    
                    
                    drainSignal.trySend(Unit)
                }
            }
        }

        
        
        drainedRoomChanges()
            .onEach { changes ->
                val changesByRoom = changes.groupBy { it.roomId }

                val roomIds = changesByRoom.keys.toList()
                val roomObjects = wcdb.room.getAllObjects(DBRoomModel.roomId.`in`(roomIds))
                    .associateBy { it.roomId }

                coroutineScope {
                    changesByRoom.forEach { (roomId, roomChanges) ->
                        val roomObject = roomObjects[roomId] ?: return@forEach

                        launch {
                            try {
                                L.i { "[Message][WCDBUpdateService] updating room=$roomId changes=${roomChanges.map { it.type }}" }

                                val needsDataUpdate = roomChanges.any {
                                    it.type != RoomChangeType.REFRESH
                                }

                                if (!needsDataUpdate) {
                                    
                                    L.d { "[Message][WCDBUpdateService] Room $roomId: REFRESH only, skipping data queries" }
                                    return@launch
                                }

                                if (roomChanges.any { it.type == RoomChangeType.MESSAGE }) {
                                    
                                    
                                    
                                    
                                    
                                    
                                    
                                    
                                    
                                    if (needsSendStatusRecompute(roomObject.sendStatus)) {
                                        resolveRoomSendStatus(roomObject.sendStatus, wcdb.hasFailedOutgoingMessage(roomId))
                                            ?.let { targetStatus ->
                                                
                                                
                                                
                                                
                                                
                                                
                                                
                                                
                                                if (targetStatus == ROOM_SEND_STATUS_NONE) {
                                                    wcdb.clearRoomSendStatusIfNoFailure(roomId)
                                                } else {
                                                    wcdb.writeRoomSendStatus(roomId, targetStatus)
                                                }
                                                L.i { "[WCDBUpdateService] room=$roomId sendStatus ${roomObject.sendStatus}->$targetStatus (clear is conditional; no-op if a failure raced in)" }
                                            }
                                    }

                                    
                                    
                                    
                                    
                                    
                                    if (roomObject.sendingStatus != ROOM_SENDING_STATUS_NONE &&
                                        !wcdb.hasSendingOutgoingMessage(roomId)
                                    ) {
                                        wcdb.clearRoomSendingStatusIfNoSending(roomId)
                                        L.i { "[WCDBUpdateService] room=$roomId sendingStatus cleared (conditional; no-op if a send raced in)" }
                                    }

                                    
                                    
                                    
                                    
                                    
                                    val previewMessage = wcdb.message.getFirstObject(
                                        previewableMessageCondition(roomId),
                                        DBMessageModel.systemShowTimestamp.order(Order.Desc)
                                    )

                                    if (previewMessage == null) {
                                        
                                        
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

                                        val isArchiveMessage = isArchiveExpiredSystemMessage(previewMessage)

                                        if (isArchiveMessage) {
                                            
                                            val emptyRoomSince = roomObject.emptyRoomSince ?: System.currentTimeMillis()
                                            if (roomObject.lastDisplayContent != "" || roomObject.emptyRoomSince == null) {
                                                wcdb.room.updateRow(
                                                    arrayOf(Value(""), Value(emptyRoomSince)),
                                                    arrayOf(DBRoomModel.lastDisplayContent, DBRoomModel.emptyRoomSince),
                                                    DBRoomModel.roomId.eq(roomId)
                                                )
                                            }
                                        } else {
                                            
                                            val lastDisplayContent = previewMessage.previewContent()

                                            
                                            
                                            
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

                                        if (lastActiveTime == 0L) {
                                            roomObject.resetRoomUnreadState()
                                        } else if (roomObject.readPosition < lastActiveTime) {
                                            roomObject.updateRoomUnreadState()
                                        } else {
                                            roomObject.resetRoomUnreadState()
                                        }
                                    }
                                }

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

                                if (roomObject.roomName.isNullOrEmpty()) {
                                    roomObject.updateRoomNameAndAvatar()
                                }

                                L.d { "[WCDBUpdateService] Room $roomId updated successfully" }

                            } catch (e: Exception) {
                                L.e(e) { "[Message][WCDBUpdateService] Error updating room:$roomId:" }
                            }
                        }
                    }
                }

                _roomTableUpdated.tryEmit(Unit)
                L.d { "[WCDBUpdateService] Batch processing completed, notification emitted" }
            }
            .catch { e ->
                L.e(e) { "[Message][WCDBUpdateService] Error in flow:" }
                
                collectorJob.cancel()
                isUpdatingRoomsStarted.set(false)
            }
            .launchIn(this)
    }

    
    
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

    
    private const val NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED = 10012

    
    private const val NOTIFY_ACTION_TYPE_FORWARD_NOTICE = 10020
    private const val NOTIFY_ACTION_TYPE_COPY_NOTICE = 10021
    private const val NOTIFY_ACTION_TYPE_TRANSLATE_SETTING_CHANGED = 10022

    




    private fun previewableMessageCondition(roomId: String): Expression =
        DBMessageModel.roomId.eq(roomId)
            .and(
                DBMessageModel.type.notEq(MessageModel.TYPE_NOTIFY)
                    .or(DBMessageModel.messageText.isNull())
                    .or(
                        DBMessageModel.messageText.notLike("%\"notifyType\":$NOTIFY_ACTION_TYPE_FORWARD_NOTICE,%")
                            .and(DBMessageModel.messageText.notLike("%\"notifyType\":$NOTIFY_ACTION_TYPE_COPY_NOTICE,%"))
                            .and(DBMessageModel.messageText.notLike("%\"notifyType\":$NOTIFY_ACTION_TYPE_TRANSLATE_SETTING_CHANGED,%"))
                    )
            )

    



    fun latestPreviewMessages(roomId: String, limit: Long): List<MessageModel> =
        wcdb.message.getAllObjects(
            previewableMessageCondition(roomId),
            DBMessageModel.systemShowTimestamp.order(Order.Desc),
            limit,
        ).filterNot { isArchiveExpiredSystemMessage(it) }

    
    private fun isArchiveExpiredSystemMessage(message: MessageModel): Boolean {
        if (message.type != 2) return false 
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
