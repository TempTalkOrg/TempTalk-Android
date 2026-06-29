package com.difft.android.chat.setting

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
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

/**
 * Special value to indicate "set saveToPhotos to null (default/follow global)"
 * Used to distinguish between "no change" (null) and "set to default" (-1)
 */
const val SAVE_TO_PHOTOS_SET_DEFAULT = -1

/** Min interval between full syncs; repeated triggers within this window are skipped. */
private const val MIN_BULK_SYNC_INTERVAL_MS = 60_000L

/**
 * Conversation setting update event
 * null indicates the field has not changed
 */
data class ConversationSettingUpdate(
    val conversationId: String,
    // Type 4 related: muteStatus, blockStatus, confidentialMode
    val muteStatus: Int? = null,
    val blockStatus: Int? = null,
    val confidentialMode: Int? = null,
    // Type 5 related: messageExpiry, messageClearAnchor (always appear together)
    val messageExpiry: Long? = null,
    val messageClearAnchor: Long? = null,
    // Save to photos setting (null: no change, -1: set to default, 0: disabled, 1: enabled)
    val saveToPhotos: Int? = null
)

// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
@Singleton
class ConversationSettingsManager @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
    private val messageArchiveManager: MessageArchiveManager,
    private val dbRoomStore: DBRoomStore
) : CoroutineScope by appScope {

    private val _conversationSettingUpdate = MutableSharedFlow<ConversationSettingUpdate>(extraBufferCapacity = 1)
    val conversationSettingUpdate: SharedFlow<ConversationSettingUpdate> = _conversationSettingUpdate.asSharedFlow()

    // Full-sync throttle, shared by cold start and every foreground (guarded by bulkSyncLock).
    // The window is stamped only AFTER a successful sync, so a failed/empty attempt does not burn
    // it and the next foreground can retry. bulkSyncInFlight dedups the near-simultaneous
    // cold-start IndexActivity + onForeground triggers.
    private var lastBulkSyncSuccessAtMs = 0L
    private var bulkSyncInFlight = false
    private val bulkSyncLock = Any()

    init {
        // Refetch a single conversation's config when its room is (re)created, so delete + recreate
        // recovers immediately even when the full sync is throttled. roomCreated has a replay buffer
        // so events emitted just before this collector subscribes at startup are not lost.
        launch(Dispatchers.IO) {
            RoomChangeTracker.roomCreated.collect { roomIds ->
                syncSettingsForRooms(roomIds)
            }
        }
    }

    // Claims the single in-flight slot; check + claim must be locked, otherwise cold start's
    // near-simultaneous IndexActivity + onForeground triggers both pass.
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

    /**
     * 通知会话配置已更新
     * @param conversationId 会话ID
     * @param muteStatus 静音状态 (null = 未变化)
     * @param blockStatus 屏蔽状态 (null = 未变化)
     * @param confidentialMode 机密模式 (null = 未变化)
     * @param messageExpiry 消息过期时间 (null = 未变化)
     * @param messageClearAnchor 消息清除锚点 (null = 未变化)
     */
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
        L.i { "[ConversationSettingsManager] emitConversationSettingUpdate: $update" }
        _conversationSettingUpdate.tryEmit(update)
    }

    /**
     * Emit save to photos setting update
     * @param conversationId Conversation ID
     * @param saveToPhotos Save to photos setting (null: follow global, 0: disabled, 1: enabled)
     */
    fun emitSaveToPhotosUpdate(conversationId: String, saveToPhotos: Int?) {
        // Use SAVE_TO_PHOTOS_SET_DEFAULT (-1) to indicate "set to null (default)"
        // This distinguishes from null which means "no change"
        val updateValue = saveToPhotos ?: SAVE_TO_PHOTOS_SET_DEFAULT
        val update = ConversationSettingUpdate(
            conversationId = conversationId,
            saveToPhotos = updateValue
        )
        L.i { "[ConversationSettingsManager] emitSaveToPhotosUpdate: $update" }
        _conversationSettingUpdate.tryEmit(update)
    }
    /**
     * Full sync of all rooms' server config. Called on cold start and every foreground,
     * throttled by [MIN_BULK_SYNC_INTERVAL_MS]. Delete + recreate of a single conversation is
     * handled instantly by [syncSettingsForRooms], so no startup delay is needed here.
     *
     * @param force bypass the throttle.
     */
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
                    success = true // nothing to sync is a stable outcome — keep the throttle window
                    return@launch
                }
                success = fetchAndApply(roomModels)
                if (!success) {
                    L.i { "[ConversationSettingsManager] No conversation settings received from server" }
                }
            } catch (e: Exception) {
                L.e { "[ConversationSettingsManager] syncConversationSettings error: ${e.stackTraceToString()}" }
            } finally {
                // Only a successful sync arms the throttle; a failure lets the next foreground retry.
                endBulkSync(success)
            }
        }
    }

    /**
     * Refetch server config for the given rooms (delete + recreate and similar incremental cases).
     * Not throttled: room-created events are inherently sparse (only a genuine new/recreated room
     * fires one), and a recreated room always needs a fresh fetch since its row starts at defaults.
     */
    private suspend fun syncSettingsForRooms(roomIds: List<String>) {
        try {
            val ids = roomIds.filter { it.isNotEmpty() && it != "server" }
            if (ids.isEmpty()) return

            // Only rooms still present (an event may arrive after the room was deleted again).
            val roomModels = wcdb.room.getAllObjects(DBRoomModel.roomId.`in`(ids))
            if (roomModels.isEmpty()) return

            L.i { "[ConversationSettingsManager] sync settings for newly created rooms: ${roomModels.map { it.roomId }}" }
            fetchAndApply(roomModels)
        } catch (e: Exception) {
            L.e { "[ConversationSettingsManager] syncSettingsForRooms error: ${e.stackTraceToString()}" }
        }
    }

    /** Shared fetch → apply core. Returns true if settings were fetched and applied. */
    private suspend fun fetchAndApply(roomModels: List<RoomModel>): Boolean {
        if (roomModels.isEmpty()) return false
        val conversationSettings = fetchConversationSettings(roomModels.map { it.roomId })
        if (conversationSettings.isEmpty()) return false
        updateRoomSettings(roomModels, conversationSettings)
        return true
    }

    /**
     * 获取房间模型列表
     */
    private suspend fun getRoomModels(): List<RoomModel> {
        return wcdb.room.getAllObjects(
            DBRoomModel.roomId.notEq("server")
                .and(DBRoomModel.roomName.notNull())
                .and(DBRoomModel.roomName.notEq(""))
        )
    }

    /**
     * 从服务器获取会话设置
     */
    private suspend fun fetchConversationSettings(conversationIds: List<String>): List<ConversationSetResponseBody> {
        if (conversationIds.isEmpty()) {
            return emptyList()
        }

        val response = httpClient.httpService.fetchGetConversationSet(
            (globalServices.userManager.getUserData()?.baseAuth ?: ""),
            GetConversationSetRequestBody(conversationIds)
        )

        if (response.status != 0) {
            L.e { "[ConversationSettingsManager] Server error: ${response.status} - ${response.reason}" }
            return emptyList()
        }

        val conversations = response.data?.conversations ?: emptyList()
        L.i { "[ConversationSettingsManager] Received ${conversations.size} conversation settings from server" }

        return conversations
    }

    /**
     * 更新房间设置
     */
    private fun updateRoomSettings(
        roomModels: List<RoomModel>,
        conversationSettings: List<ConversationSetResponseBody>
    ) {
        val defaultMessageExpiry = messageArchiveManager.getDefaultMessageArchiveTime()

        conversationSettings.forEach { setting ->
            try {
                // 处理 messageExpiry
                val finalMessageExpiry = when {
                    setting.conversation == globalServices.myId -> 0L
                    setting.messageExpiry >= 0 -> setting.messageExpiry
                    else -> defaultMessageExpiry
                }

                // 查找对应的房间
                val currentRoom = roomModels.find { it.roomId == setting.conversation }
                if (currentRoom == null) {
                    L.w { "[ConversationSettingsManager] Room not found for conversation: ${setting.conversation}" }
                    return@forEach
                }

                // 检查是否需要更新
                if (needsUpdate(currentRoom, setting, finalMessageExpiry)) {
                    updateRoomSetting(currentRoom, setting, finalMessageExpiry)
                }

            } catch (e: Exception) {
                L.e { "[ConversationSettingsManager] Error processing setting for conversation ${setting.conversation}: ${e.message}" }
            }
        }
    }

    /**
     * 检查房间设置是否需要更新
     */
    private fun needsUpdate(
        room: RoomModel,
        setting: ConversationSetResponseBody,
        finalMessageExpiry: Long
    ): Boolean {
        return room.muteStatus != setting.muteStatus ||
                room.blockStatus != setting.blockStatus ||
                room.messageExpiry != finalMessageExpiry ||
                room.messageClearAnchor != setting.messageClearAnchor ||
                room.confidentialMode != setting.confidentialMode
    }

    /**
     * 更新单个房间的设置
     */
    private fun updateRoomSetting(
        room: RoomModel,
        setting: ConversationSetResponseBody,
        finalMessageExpiry: Long
    ) {
        try {
            L.i { "[ConversationSettingsManager] Updating room ${room.roomId}: muteStatus=${setting.muteStatus}, blockStatus=${setting.blockStatus}, confidentialMode=${setting.confidentialMode}, messageExpiry=$finalMessageExpiry, messageClearAnchor=${setting.messageClearAnchor}" }

            dbRoomStore.updateConversationSettings(
                roomId = room.roomId,
                muteStatus = setting.muteStatus,
                blockStatus = setting.blockStatus,
                confidentialMode = setting.confidentialMode,
                messageExpiry = finalMessageExpiry,
                messageClearAnchor = setting.messageClearAnchor
            )
        } catch (e: Exception) {
            L.e { "[ConversationSettingsManager] Failed to update room ${room.roomId}: ${e.message}" }
        }
    }
} 