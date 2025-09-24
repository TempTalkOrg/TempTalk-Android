package com.difft.android.chat.setting

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
import com.tencent.wcdb.base.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationSettingsManager @Inject constructor(
    @ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
    private val messageArchiveManager: MessageArchiveManager
) : CoroutineScope by appScope {
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
                e.printStackTrace()
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
    private suspend fun updateRoomSettings(
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
                room.messageExpiry != finalMessageExpiry ||
                room.messageClearAnchor != setting.messageClearAnchor
    }

    /**
     * 更新单个房间的设置
     */
    private suspend fun updateRoomSetting(
        room: RoomModel,
        setting: ConversationSetResponseBody,
        finalMessageExpiry: Long
    ) {
        try {
            L.i { "[ConversationSettingsManager] Updating settings for room: ${room.roomId}" }

            wcdb.room.updateRow(
                arrayOf(
                    Value(setting.muteStatus),
                    Value(finalMessageExpiry),
                    Value(setting.messageClearAnchor)
                ),
                arrayOf(
                    DBRoomModel.muteStatus,
                    DBRoomModel.messageExpiry,
                    DBRoomModel.messageClearAnchor
                ),
                DBRoomModel.roomId.eq(room.roomId)
            )
        } catch (e: Exception) {
            L.e { "[ConversationSettingsManager] Failed to update room ${room.roomId}: ${e.message}" }
        }
    }
} 