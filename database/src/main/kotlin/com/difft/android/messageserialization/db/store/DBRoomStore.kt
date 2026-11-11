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
import difft.android.messageserialization.model.MARK_UNREAD_FLAG_CLEAR
import difft.android.messageserialization.model.MARK_UNREAD_FLAG_UNREAD
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
        var created = false
        val room =
            wcdb.room.getFirstObject(DBRoomModel.roomId.eq(forWhat.id)) ?: (RoomModel().apply {
                created = true
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
                
                if (forWhat.id == globalServices.myId) {
                    this.lastActiveTime = System.currentTimeMillis()
                    this.pinnedTime = System.currentTimeMillis()
                    this.roomName = ResUtils.getString(R.string.chat_favorites)
                    this.emptyRoomSince = null  
                }
                try {
                    wcdb.room.insertObject(this)
                    
                    
                    RoomChangeTracker.trackRoomCreated(forWhat.id)
                } catch (e: Exception) {
                    L.w { "[DBRoomStore] insertObject failed: ${e.stackTraceToString()}" }
                }
            })
        
        
        
        if (forWhat.id == globalServices.myId) repairSavedMessages(room)
        val cost = System.currentTimeMillis() - startTime
        
        if (created || cost > SLOW_ROOM_LOOKUP_MS) {
            L.i { "[Message] createRoomIfNotExist created=$created cost=${cost}ms room=${forWhat.id}" }
        }
        return room
    }

    





    private fun repairSavedMessages(room: RoomModel) {
        val favoritesName = ResUtils.getString(R.string.chat_favorites)
        val needsName = room.roomName.isNullOrEmpty()
        val needsActiveTime = room.lastActiveTime == 0L
        if (!needsName && !needsActiveTime && room.emptyRoomSince == null) return

        if (needsName) room.roomName = favoritesName
        if (needsActiveTime) room.lastActiveTime = System.currentTimeMillis()
        room.emptyRoomSince = null
        try {
            wcdb.room.updateObject(
                room,
                arrayOf(DBRoomModel.roomName, DBRoomModel.lastActiveTime, DBRoomModel.emptyRoomSince),
                DBRoomModel.roomId.eq(room.roomId),
            )
            L.i { "[DBRoomStore] repaired Saved Messages room (name=$needsName activeTime=$needsActiveTime)" }
        } catch (e: Exception) {
            L.w { "[DBRoomStore] Saved Messages repair failed: ${e.stackTraceToString()}" }
        }
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

        RoomChangeTracker.trackRoom(forWhat.id, RoomChangeType.REFRESH)
    }

    override suspend fun getPinnedTime(forWhat: For): Optional<Long> {
        return findRoom(forWhat)?.let { Optional.ofNullable(it.pinnedTime) } ?: Optional.empty()
    }

    override suspend fun getMarkUnread(forWhat: For): Pair<Int, Long> {
        val room = findRoom(forWhat) ?: return MARK_UNREAD_FLAG_CLEAR to 0L
        return room.markUnreadFlag to room.markUnreadAt
    }

    override suspend fun updateMarkUnread(forWhat: For, markUnreadFlag: Int, markUnreadAt: Long) {
        L.i { "[DBRoomStore] updateMarkUnread id:${forWhat.id} flag:$markUnreadFlag at:$markUnreadAt" }
        createRoomIfNotExist(forWhat)
        writeMarkUnread(forWhat, markUnreadFlag, markUnreadAt)
    }

    private fun writeMarkUnread(forWhat: For, markUnreadFlag: Int, markUnreadAt: Long) {
        wcdb.room.updateRow(
            arrayOf(Value(markUnreadFlag), Value(markUnreadAt)),
            arrayOf(DBRoomModel.markUnreadFlag, DBRoomModel.markUnreadAt),
            DBRoomModel.roomId.eq(forWhat.id)
        )
        RoomChangeTracker.trackRoom(forWhat.id, RoomChangeType.REFRESH)
    }

    fun getConfidentialMode(roomId: String): Int {
        return wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.confidentialMode ?: 0
    }

    fun getRoom(roomId: String): RoomModel? = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId))

    
    fun resolveFor(roomId: String): For =
        if (wcdb.group.getFirstObject(DBGroupModel.gid.eq(roomId)) != null) For.Group(roomId) else For.Account(roomId)

    fun updateConfidentialMode(roomId: String, confidentialMode: Int) {
        L.i { "[DBRoomStore] updateConfidentialMode id:$roomId confidentialMode: $confidentialMode" }
        wcdb.room.updateValue(confidentialMode, DBRoomModel.confidentialMode, DBRoomModel.roomId.eq(roomId))

        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    


















    fun updateConversationSettings(
        roomId: String,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null,
        messageExpiry: Long? = null,
        messageClearAnchor: Long? = null,
        conversationConfigVersion: Int? = null,
        allowMentionMe: Boolean? = null,
        allowMentionAll: Boolean? = null,
        allowGroupCall: Boolean? = null
    ) {
        val values = mutableListOf<Value>()
        val fields = mutableListOf<Column>()
        val fieldNames = mutableListOf<String>()

        conversationConfigVersion?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.conversationConfigVersion)
            fieldNames.add("conversationConfigVersion")
        }

        muteStatus?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.muteStatus)
            fieldNames.add("muteStatus")
        }
        allowMentionMe?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.allowMentionMe)
            fieldNames.add("allowMentionMe")
        }
        allowMentionAll?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.allowMentionAll)
            fieldNames.add("allowMentionAll")
        }
        allowGroupCall?.let {
            values.add(Value(it))
            fields.add(DBRoomModel.allowGroupCall)
            fieldNames.add("allowGroupCall")
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

        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    




    fun updateSaveToPhotos(roomId: String, saveToPhotos: Int?) {
        L.i { "[DBRoomStore] updateSaveToPhotos id:$roomId saveToPhotos: $saveToPhotos" }
        
        val value = if (saveToPhotos != null) Value(saveToPhotos) else Value()
        wcdb.room.updateValue(
            value,
            DBRoomModel.saveToPhotos,
            DBRoomModel.roomId.eq(roomId)
        )

        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    




    fun getSaveToPhotos(roomId: String): Int? {
        return wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.saveToPhotos
    }

    




    fun updateTranslateSetting(roomId: String, translateSetting: Int) {
        L.i { "[Translate] updateTranslateSetting id:$roomId translateSetting: $translateSetting" }
        wcdb.room.updateValue(
            translateSetting,
            DBRoomModel.translateSetting,
            DBRoomModel.roomId.eq(roomId)
        )

        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    



    fun getTranslateSetting(roomId: String): Int =
        wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.translateSetting ?: 0

    



    fun getActiveTranslateTargets(): Set<Int> =
        wcdb.room.getOneColumnInt(
            DBRoomModel.translateSetting,
            DBRoomModel.translateSetting.notEq(0)
        ).filterNotNull().toSet()

    




    fun updateCriticalAlertType(roomId: String, criticalAlertType: Int) {
        L.i { "[DBRoomStore] updateCriticalAlertType id:$roomId criticalAlertType: $criticalAlertType" }
        wcdb.room.updateValue(criticalAlertType, DBRoomModel.criticalAlertType, DBRoomModel.roomId.eq(roomId))

        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
    }

    




    fun setCriticalAlertIfUnread(roomId: String, messageTimestamp: Long) {
        val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId)) ?: return
        if (messageTimestamp > room.readPosition) {
            L.i { "[DBRoomStore] setCriticalAlertIfUnread id:$roomId, messageTimestamp:$messageTimestamp > readPosition:${room.readPosition}" }
            updateCriticalAlertType(roomId, difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT)
        } else {
            L.i { "[DBRoomStore] setCriticalAlertIfUnread id:$roomId, messageTimestamp:$messageTimestamp <= readPosition:${room.readPosition}, skip" }
        }
    }

    



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
                
                if (room.markUnreadFlag == MARK_UNREAD_FLAG_UNREAD && readPosition >= room.markUnreadAt) {
                    L.i { "[DBRoomStore] readPosition passed markUnreadAt=${room.markUnreadAt}, clearing manual unread id:${forWhat.id}" }
                    writeMarkUnread(forWhat, MARK_UNREAD_FLAG_CLEAR, room.markUnreadAt)
                }

                RoomChangeTracker.trackRoom(forWhat.id, RoomChangeType.REFRESH)
            }

            updatingReadPositions.remove(forWhat.id, readPosition)
        } catch (e: Exception) {
            L.e { "[Message] Error updating read position for ${forWhat.id}: ${e.message}" }
        }
    }


    override suspend fun getUnreadMessageInfo(room: For): UnreadMessageInfo {
        val readPosition = getMessageReadPosition(room)
        
        
        val unreadCond = DBMessageModel.roomId.eq(room.id)
            .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
            .and(DBMessageModel.fromWho.notEq(myID))
            .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER))

        val unreadCount = messageCount(unreadCond).toInt()
        
        if (unreadCount == 0) return UnreadMessageInfo(0)
        if (room !is For.Group) return UnreadMessageInfo(unreadCount)

        val (mentionType, mentionTimeStamps) = queryUnreadMentions(room.id, readPosition)
        return UnreadMessageInfo(unreadCount, mentionType, mentionTimeStamps.size, mentionTimeStamps)
    }

    











    private fun queryUnreadMentions(roomId: String, readPosition: Long): Pair<Int, List<Long>> {
        
        val unreadIdsSubquery = StatementSelect()
            .select(DBMessageModel.id)
            .from("message")
            .where(
                DBMessageModel.roomId.eq(roomId)
                    .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
                    .and(DBMessageModel.fromWho.notEq(myID))
                    .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER))
            )

        
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

        
        
        
        
        
        val mentioningIds = mentionRows.map { it.messageId }.distinct()
        val mentionTimeStamps = wcdb.message.getOneColumnLong(
            DBMessageModel.timeStamp,
            DBMessageModel.id.`in`(mentioningIds),
            DBMessageModel.systemShowTimestamp.order(Order.Asc)
        )
        return mentionType to mentionTimeStamps
    }
}


private const val SLOW_ROOM_LOOKUP_MS = 50L
