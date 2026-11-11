package com.difft.android.chat.setting

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.appScope
import org.difft.app.database.wcdb
import com.difft.android.chat.setting.sync.ConversationConfigFetcher
import com.difft.android.chat.setting.sync.ConversationSettingsApplier
import com.difft.android.chat.setting.sync.ServerConfigSnapshot
import com.difft.android.chat.setting.sync.SnapshotSource
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.responses.ConversationSetResponseBody
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import javax.inject.Inject
import javax.inject.Singleton





const val SAVE_TO_PHOTOS_SET_DEFAULT = -1


private const val MIN_BULK_SYNC_INTERVAL_MS = 60_000L





data class ConversationSettingUpdate(
    val conversationId: String,
    
    val muteStatus: Int? = null,
    val blockStatus: Int? = null,
    val confidentialMode: Int? = null,
    
    val messageExpiry: Long? = null,
    val messageClearAnchor: Long? = null,
    
    val saveToPhotos: Int? = null,
    
    
    val translateSetting: Int? = null,
    
    val allowMentionMe: Boolean? = null,
    val allowMentionAll: Boolean? = null,
    val allowGroupCall: Boolean? = null
)


@Suppress("BlockingWcdbInSuspend")
@Singleton
class ConversationSettingsManager @Inject constructor(
    private val fetcher: ConversationConfigFetcher,
    private val dbRoomStore: DBRoomStore,
    
    private val applier: Lazy<ConversationSettingsApplier>
) : CoroutineScope by appScope {

    private val _conversationSettingUpdate = MutableSharedFlow<ConversationSettingUpdate>(extraBufferCapacity = 1)
    val conversationSettingUpdate: SharedFlow<ConversationSettingUpdate> = _conversationSettingUpdate.asSharedFlow()

    
    
    
    
    private var lastBulkSyncSuccessAtMs = 0L
    private var bulkSyncInFlight = false
    private val bulkSyncLock = Any()

    init {
        
        
        
        launch(Dispatchers.IO) {
            RoomChangeTracker.roomCreated.collect { roomIds ->
                syncSettingsForRooms(roomIds)
            }
        }
    }

    
    
    private fun tryBeginBulkSync(force: Boolean): Boolean = synchronized(bulkSyncLock) {
        if (bulkSyncInFlight) return false
        if (!force && System.currentTimeMillis() - lastBulkSyncSuccessAtMs < MIN_BULK_SYNC_INTERVAL_MS) return false
        bulkSyncInFlight = true
        true
    }

    private fun endBulkSync(success: Boolean) = synchronized(bulkSyncLock) {
        bulkSyncInFlight = false
        if (success) lastBulkSyncSuccessAtMs = System.currentTimeMillis()
    }

    








    fun emitConversationSettingUpdate(
        conversationId: String,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null,
        messageExpiry: Long? = null,
        messageClearAnchor: Long? = null
    ) {
        val update = ConversationSettingUpdate(
            conversationId = conversationId,
            muteStatus = muteStatus,
            blockStatus = blockStatus,
            confidentialMode = confidentialMode,
            messageExpiry = messageExpiry,
            messageClearAnchor = messageClearAnchor
        )
        emitConversationSettingUpdate(update)
    }

    fun emitConversationSettingUpdate(update: ConversationSettingUpdate) {
        L.i { "[ConversationSettingsManager] emitConversationSettingUpdate: $update" }
        _conversationSettingUpdate.tryEmit(update)
    }

    




    fun emitSaveToPhotosUpdate(conversationId: String, saveToPhotos: Int?) {
        
        
        val updateValue = saveToPhotos ?: SAVE_TO_PHOTOS_SET_DEFAULT
        val update = ConversationSettingUpdate(
            conversationId = conversationId,
            saveToPhotos = updateValue
        )
        L.i { "[ConversationSettingsManager] emitSaveToPhotosUpdate: $update" }
        _conversationSettingUpdate.tryEmit(update)
    }

    




    fun emitTranslateSettingUpdate(conversationId: String, translateSetting: Int) {
        val update = ConversationSettingUpdate(
            conversationId = conversationId,
            translateSetting = translateSetting
        )
        L.i { "[Translate] emitTranslateSettingUpdate: $update" }
        _conversationSettingUpdate.tryEmit(update)
    }
    






    fun syncConversationSettings(force: Boolean = false) {
        launch(Dispatchers.IO) {
            if (!tryBeginBulkSync(force)) {
                L.i { "[ConversationSettingsManager] bulk sync throttled, skip" }
                return@launch
            }
            var success = false
            try {
                val roomModels = getRoomModels()
                if (roomModels.isEmpty()) {
                    L.i { "[ConversationSettingsManager] No rooms found for conversation settings sync" }
                    success = true 
                    return@launch
                }
                success = fetchAndApply(roomModels, source = "bulk")
            } catch (e: Exception) {
                L.e { "[ConversationSettingsManager] syncConversationSettings error: ${e.stackTraceToString()}" }
            } finally {
                
                endBulkSync(success)
            }
        }
    }

    




    private suspend fun syncSettingsForRooms(roomIds: List<String>) {
        try {
            val ids = roomIds.filter { it.isNotEmpty() && it != "server" }
            if (ids.isEmpty()) return

            
            val roomModels = wcdb.room.getAllObjects(DBRoomModel.roomId.`in`(ids))
            if (roomModels.isEmpty()) return

            L.i { "[ConversationSettingsManager] sync settings for newly created rooms: ${roomModels.map { it.roomId }}" }
            fetchAndApply(roomModels, source = "roomCreated")
        } catch (e: Exception) {
            L.e { "[ConversationSettingsManager] syncSettingsForRooms error: ${e.stackTraceToString()}" }
        }
    }

    



    private suspend fun fetchAndApply(roomModels: List<RoomModel>, source: String): Boolean {
        if (roomModels.isEmpty()) return false
        val conversationSettings = fetcher.fetch(roomModels.map { it.roomId }, source) ?: return false
        if (conversationSettings.isNotEmpty()) updateRoomSettings(roomModels, conversationSettings)
        return true
    }

    




    private suspend fun getRoomModels(): List<RoomModel> {
        return wcdb.room.getAllObjects(DBRoomModel.roomId.notEq("server"))
    }

    



    private suspend fun updateRoomSettings(
        roomModels: List<RoomModel>,
        conversationSettings: List<ConversationSetResponseBody>
    ) {
        val knownRooms = roomModels.mapTo(HashSet()) { it.roomId }
        conversationSettings.forEach { setting ->
            try {
                if (setting.conversation !in knownRooms) {
                    L.w { "[ConversationSettingsManager] Room not found for conversation: ${setting.conversation}" }
                    return@forEach
                }
                
                applier.get().applyServerSnapshot(ServerConfigSnapshot.fromPull(setting), SnapshotSource.Pull, announce = false)
            } catch (e: Exception) {
                L.e { "[ConversationSettingsManager] Error processing setting for conversation ${setting.conversation}: ${e.message}" }
            }
        }
    }
} 