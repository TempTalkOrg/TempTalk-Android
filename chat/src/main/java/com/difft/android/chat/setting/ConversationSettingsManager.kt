package com.difft.android.chat.setting

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Special value to indicate "set saveToPhotos to null (default/follow global)"
 * Used to distinguish between "no change" (null) and "set to default" (-1)
 */
const val SAVE_TO_PHOTOS_SET_DEFAULT = -1

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

@Singleton
class ConversationSettingsManager @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
    private val messageArchiveManager: MessageArchiveManager,
    private val dbRoomStore: DBRoomStore
) : CoroutineScope by appScope {

    private val _conversationSettingUpdate = MutableSharedFlow<ConversationSettingUpdate>(extraBufferCapacity = 1)
    val conversationSettingUpdate: SharedFlow<ConversationSettingUpdate> = _conversationSettingUpdate.asSharedFlow()

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
     * 同步会话设置从服务器
     * 此方法将从数据库获取所有房间对象并同步其设置
     */
    fun syncConversationSettings() {
        launch(Dispatchers.IO) {
            try {
                delay(3000)
                // 获取所有房间对象
                val roomModels = getRoomModels()
                if (roomModels.isEmpty()) {
                    L.i { "[ConversationSettingsManager] No rooms found for conversation settings sync" }
                    return@launch
                }

                // 获取会话设置
                val conversationSettings = fetchConversationSettings(roomModels.map { it.roomId })
                if (conversationSettings.isEmpty()) {
                    L.i { "[ConversationSettingsManager] No conversation settings received from server" }
                    return@launch
                }

                // 更新房间设置
                updateRoomSettings(roomModels, conversationSettings)
            } catch (e: Exception) {
                L.e { "[ConversationSettingsManager] syncConversationSettings error: ${e.message}" }
            }
        }
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
            SecureSharedPrefsUtil.getBasicAuth(),
            GetConversationSetRequestBody(conversationIds)
        ).await()

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