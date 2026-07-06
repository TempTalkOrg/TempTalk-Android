package com.difft.android.messageserialization.db.store

import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IMessageNotificationUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import org.difft.app.database.convertToContactorModel
import com.difft.android.base.utils.globalServices
import org.difft.app.database.messageCount
import org.difft.app.database.updateRoomUnreadState
import org.difft.app.database.wcdb
import difft.android.messageserialization.For
import difft.android.messageserialization.RoomStore
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.unreadmessage.UnreadMessageInfo
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Column
import com.tencent.wcdb.winq.Order
import com.tencent.wcdb.winq.StatementSelect
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
@Singleton
class DBRoomStore @Inject constructor(
    private val messageNotificationUtil: IMessageNotificationUtil
) : RoomStore {

    private val myID by lazy {
        globalServices.myId
    }

    fun createRoomIfNotExist(forWhat: For): RoomModel {
        val startTime = System.currentTimeMillis()
        val room =
            wcdb.room.getFirstObject(DBRoomModel.roomId.eq(forWhat.id)) ?: (RoomModel().apply {
                this.roomId = forWhat.id
                this.roomType = forWhat.typeValue
                if (forWhat is For.Group) {
                    wcdb.group.getFirstObject(DBGroupModel.gid.eq(forWhat.id))?.let {
                        this.roomName = it.name
                        this.roomAvatarJson = it.avatar
                    }
                } else {
                    val contactor = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(forWhat.id))
                    if (contactor != null) {
                        this.roomName = contactor.getDisplayNameForUI()
                        this.roomAvatarJson = contactor.avatar
                    } else {
                        val groupMemberContactor = wcdb.groupMemberContactor.getFirstObject(
                            DBGroupMemberContactorModel.id.eq(forWhat.id)
                        )?.convertToContactorModel()
                        if (groupMemberContactor != null) {
                            this.roomName = groupMemberContactor.getDisplayNameForUI()
                            this.roomAvatarJson = groupMemberContactor.avatar
                        }
                    }
                }
                // Saved Messages (备忘录)
                if (forWhat.id == globalServices.myId) {
                    this.lastActiveTime = System.currentTimeMillis()
                    this.pinnedTime = System.currentTimeMillis()
                    this.roomName = ResUtils.getString(R.string.chat_favorites)
                    this.emptyRoomSince = null  // Saved Messages is not an empty room
                }
                try {
                    wcdb.room.insertObject(this)
                    // New (or re-created after delete) room: trigger a server-config refetch
                    // (mute, etc.) so settings survive delete + recreate without a restart.
                    RoomChangeTracker.trackRoomCreated(forWhat.id)
                } catch (e: Exception) {
                    L.w { "[DBRoomStore] insertObject failed: ${e.stackTraceToString()}" }
                }
            })
        val endTime = System.currentTimeMillis()
        L.i { "[Message] createRoomIfNotExist took ${endTime - startTime}ms for room ${forWhat.id}" }
        return room
    }

    private fun findRoom(forWhat: For): RoomModel? = findRoom(forWhat.id)

    private fun findRoom(id: String): RoomModel? =
        wcdb.room.getFirstObject(DBRoomModel.roomId.eq(id))

    override suspend fun updateMessageExpiry(forWhat: For, messageExpiry: Long, messageClearAnchor: Long) {
        L.i { "[DBRoomStore] updateMessageExpiry " + forWhat.id + " messageExpiry: $messageExpiry, messageClearAnchor: $messageClearAnchor" }
        wcdb.room.updateRow(
            arrayOf(Value(messageExpiry), Value(messageClearAnchor)),
            arrayOf(DBRoomModel.messageExpiry, DBRoomModel.messageClearAnchor),
            DBRoomModel.roomId.eq(forWhat.id)
        )

        // ✅ 通知UI刷新
        RoomChangeTracker.trackRoom(forWhat.id, RoomChangeType.REFRESH)
    }

    override suspend fun getMessageExpiry(forWhat: For): Optional<Long> {
        return findRoom(forWhat)?.let { Optional.ofNullable(it.messageExpiry) } ?: Optional.empty()
    }

    override suspend fun updateMuteStatus(forWhat: For, muteStatus: Int?) {
        wcdb.room.updateValue(
            muteStatus ?: 0,
            DBRoomModel.muteStatus,
            DBRoomModel.roomId.eq(forWhat.id)
        )

        // ✅ 通知UI刷新
        RoomChangeTracker.trackRoom(forWhat.id, RoomChangeType.REFRESH)
    }


    override suspend fun getMuteStatus(forWhat: For): Optional<Int> {
        return findRoom(forWhat)?.let { Optional.ofNullable(it.muteStatus) } ?: Optional.empty()
    }

    override suspend fun updatePinnedTime(forWhat: For, pinnedTime: Long?) {
        L.i { "[DBRoomStore] updatePinnedTime  id:${forWhat.id} pinnedTime: $pinnedTime" }
        wcdb.room.updateValue(
            Value(pinnedTime),
            DBRoomModel.pinnedTime,
            DBRoomModel.roomId.eq(forWhat.id)
        )

        // ✅ 通知UI刷新
        RoomChangeTracker.trackRoom(forWhat.id, RoomChangeType.REFRESH)
    }

    override suspend fun getPinnedTime(forWhat: For): Optional<Long> {
        return findRoom(forWhat)?.let { Optional.ofNullable(it.pinnedTime) } ?: Optional.empty()
    }

    fun getConfidentialMode(roomId: String): Int {
        return wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.confidentialMode ?: 0
    }

    fun updateConfidentialMode(roomId: String, confidentialMode: Int) {
        L.i { "[DBRoomStore] updateConfidentialMode id:$roomId confidentialMode: $confidentialMode" }
        wcdb.room.updateValue(confidentialMode, DBRoomModel.confidentialMode, DBRoomModel.roomId.eq(roomId))

        // ✅ 通知UI刷新
        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    /**
     * Batch update conversation settings
     * Only updates non-null fields passed in, flexibly adapting to different scenarios:
     * - WebSocket Notify type 4: muteStatus, blockStatus, confidentialMode
     * - WebSocket Notify type 5: messageExpiry, messageClearAnchor (use updateMessageExpiry)
     * - API response: all fields
     *
     * @param roomId Conversation ID
     * @param muteStatus Mute status
     * @param blockStatus Block status (only valid for single chat)
     * @param confidentialMode Confidential mode
     * @param messageExpiry Message expiry time
     * @param messageClearAnchor Message clear anchor
     */
    fun updateConversationSettings(
        roomId: String,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null,
        messageExpiry: Long? = null,
        messageClearAnchor: Long? = null
    ) {
        val values = mutableListOf<Value>()
        val fields = mutableListOf<Column>()
        val fieldNames = mutableListOf<String>()

        muteStatus?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.muteStatus)
            fieldNames.add("muteStatus")
        }
        blockStatus?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.blockStatus)
            fieldNames.add("blockStatus")
        }
        confidentialMode?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.confidentialMode)
            fieldNames.add("confidentialMode")
        }
        messageExpiry?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.messageExpiry)
            fieldNames.add("messageExpiry")
        }
        messageClearAnchor?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.messageClearAnchor)
            fieldNames.add("messageClearAnchor")
        }

        if (values.isEmpty()) return

        L.i { "[DBRoomStore] updateConversationSettings id:$roomId fields:$fieldNames" }
        wcdb.room.updateRow(
            values.toTypedArray(),
            fields.toTypedArray(),
            DBRoomModel.roomId.eq(roomId)
        )

        // Notify UI refresh
        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    /**
     * Update save to photos setting for a conversation
     * @param roomId Conversation ID
     * @param saveToPhotos Save to photos setting (null: follow global, 0: disabled, 1: enabled)
     */
    fun updateSaveToPhotos(roomId: String, saveToPhotos: Int?) {
        L.i { "[DBRoomStore] updateSaveToPhotos id:$roomId saveToPhotos: $saveToPhotos" }
        // Use Value() for null to properly set database field to null
        val value = if (saveToPhotos != null) Value(saveToPhotos) else Value()
        wcdb.room.updateValue(
            value,
            DBRoomModel.saveToPhotos,
            DBRoomModel.roomId.eq(roomId)
        )

        // Notify UI refresh
        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    /**
     * Get save to photos setting for a conversation
     * @param roomId Conversation ID
     * @return Save to photos setting (null: follow global, 0: disabled, 1: enabled)
     */
    fun getSaveToPhotos(roomId: String): Int? {
        return wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.saveToPhotos
    }

    /**
     * 更新会话的 Critical Alert 状态
     * @param roomId 会话ID
     * @param criticalAlertType Critical Alert 类型（0: 无, 1: 有）
     */
    fun updateCriticalAlertType(roomId: String, criticalAlertType: Int) {
        L.i { "[DBRoomStore] updateCriticalAlertType id:$roomId criticalAlertType: $criticalAlertType" }
        wcdb.room.updateValue(criticalAlertType, DBRoomModel.criticalAlertType, DBRoomModel.roomId.eq(roomId))

        // ✅ 通知UI刷新
        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    /**
     * 设置会话的 Critical Alert 高亮状态（仅当消息未读时才设置）
     * @param roomId 会话ID
     * @param messageTimestamp Critical Alert 消息的 systemShowTimestamp
     */
    fun setCriticalAlertIfUnread(roomId: String, messageTimestamp: Long) {
        val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId)) ?: return
        if (messageTimestamp > room.readPosition) {
            L.i { "[DBRoomStore] setCriticalAlertIfUnread id:$roomId, messageTimestamp:$messageTimestamp > readPosition:${room.readPosition}" }
            updateCriticalAlertType(roomId, difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT)
        } else {
            L.i { "[DBRoomStore] setCriticalAlertIfUnread id:$roomId, messageTimestamp:$messageTimestamp <= readPosition:${room.readPosition}, skip" }
        }
    }

    /**
     * 清除会话的 Critical Alert 高亮状态
     * @param roomId 会话ID
     */
    fun clearCriticalAlert(roomId: String) {
        updateCriticalAlertType(roomId, difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE)
    }

    override suspend fun getMessageReadPosition(forWhat: For): Long {
        return findRoom(forWhat)?.readPosition ?: 0
    }

    private val updatingReadPositions = ConcurrentHashMap<String, Long>()

    override suspend fun updateMessageReadPosition(forWhat: For, readPosition: Long) {
        L.i { "[DBRoomStore] updateMessageReadPosition  id:${forWhat.id} readPosition: $readPosition" }

        try {
            // 如果当前正在更新的值更大，直接丢弃
            val currentUpdating = updatingReadPositions[forWhat.id]
            if (currentUpdating != null && currentUpdating >= readPosition) {
                L.d { "[Message] Ignoring readPosition $readPosition for ${forWhat.id}, larger value $currentUpdating is being processed" }
                return
            }

            updatingReadPositions[forWhat.id] = readPosition
            val room = createRoomIfNotExist(forWhat)

            if (room.readPosition < readPosition) {
                room.updateRoomUnreadState(readPosition)
                L.i { "[Message] Read position advanced from ${room.readPosition} to $readPosition for ${room.roomId}, clearing notifications" }
                messageNotificationUtil.cancelNotificationsByConversation(room.roomId)

                // ✅ 触发会话列表刷新，更新未读徽章
                RoomChangeTracker.trackRoom(forWhat.id, RoomChangeType.REFRESH)
            }

            updatingReadPositions.remove(forWhat.id, readPosition)
        } catch (e: Exception) {
            L.e { "[Message] Error updating read position for ${forWhat.id}: ${e.message}" }
        }
    }


    override suspend fun getUnreadMessageInfo(room: For): UnreadMessageInfo {
        val readPosition = getMessageReadPosition(room)
        // #909 #3: count via SQL COUNT (no object load); mention info via subquery against
        // the `mention` table (bounded by mention count, not by total unread count).
        val unreadCond = DBMessageModel.roomId.eq(room.id)
            .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
            .and(DBMessageModel.fromWho.notEq(myID))
            .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER))

        val unreadCount = messageCount(unreadCond).toInt()
        // #909 R3 hot-path short-circuit: when nothing is unread, mentions are necessarily empty —
        // skip the group mention subquery entirely (this is called on every bottom-button refresh).
        if (unreadCount == 0) return UnreadMessageInfo(0)
        if (room !is For.Group) return UnreadMessageInfo(unreadCount)

        val (mentionType, mentionTimeStamps) = queryUnreadMentions(room.id, readPosition)
        return UnreadMessageInfo(unreadCount, mentionType, mentionTimeStamps.size, mentionTimeStamps)
    }

    /**
     * Computes the @-mention type and the `timeStamp`s of mentioning unread messages for a
     * group room, without loading any [MessageModel]. (#909 TP3)
     *
     * - The unread message ids are expressed as a correlated SQL subquery (never materialized).
     * - `mentionType` is derived from the matching `mention.uid`s (me > all > none).
     * - `mentionTimeStamps` = `message.timeStamp` of the mentioning messages — the consumer
     *   (ChatMessageListFragment) matches these against `MessageModel.timeStamp`, so the
     *   `timeStamp` field (not `systemShowTimestamp`) is preserved.
     *
     * @return Pair(mentionType, mentionTimeStamps).
     */
    private fun queryUnreadMentions(roomId: String, readPosition: Long): Pair<Int, List<Long>> {
        // Subquery: ids of unread messages in range (mirrors unreadCond above).
        val unreadIdsSubquery = StatementSelect()
            .select(DBMessageModel.id)
            .from("message")
            .where(
                DBMessageModel.roomId.eq(roomId)
                    .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
                    .and(DBMessageModel.fromWho.notEq(myID))
                    .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER))
            )

        // Single pass over mention rows (uid + messageId) for those unread messages where uid
        // is me or @all — derives BOTH mentionType and the mentioning message ids in one query
        // instead of two DB calls sharing the same WHERE. (#910 review r3372078153)
        val mentionRows = wcdb.mention.getAllObjects(
            DBMentionModel.uid.`in`(myID, MENTIONS_ALL_ID)
                .and(DBMentionModel.messageId.`in`(unreadIdsSubquery))
        )
        val mentionType = when {
            mentionRows.any { it.uid == myID } -> MENTIONS_TYPE_ME
            mentionRows.any { it.uid == MENTIONS_ALL_ID } -> MENTIONS_TYPE_ALL
            else -> MENTIONS_TYPE_NONE
        }
        if (mentionType == MENTIONS_TYPE_NONE) return MENTIONS_TYPE_NONE to emptyList()

        // timeStamps of mentioning messages (bounded by mention count, not total unread).
        // Order by systemShowTimestamp ASC (earliest first) so the consumer's
        // mentionIds.firstOrNull() jumps to the earliest @-mention. The select column stays
        // `timeStamp` (matched against MessageModel.timeStamp by ChatMessageListFragment);
        // ordering is on systemShowTimestamp (the authoritative display order). (#909 R3)
        val mentioningIds = mentionRows.map { it.messageId }.distinct()
        val mentionTimeStamps = wcdb.message.getOneColumnLong(
            DBMessageModel.timeStamp,
            DBMessageModel.id.`in`(mentioningIds),
            DBMessageModel.systemShowTimestamp.order(Order.Asc)
        )
        return mentionType to mentionTimeStamps
    }
}
