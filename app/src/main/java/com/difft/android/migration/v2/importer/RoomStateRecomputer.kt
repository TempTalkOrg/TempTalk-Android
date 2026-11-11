package com.difft.android.migration.v2.importer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import difft.android.messageserialization.model.isWhitelistedVideoType
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.SharedContactModel






















object RoomStateRecomputer {

    
    private const val NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED = 10012

    










    fun recompute(
        target: RoomStateReadWrite,
        selfUid: String,
        strings: PreviewStrings = PreviewStrings.ResUtilsBacked,
    ) {
        val roomIds = target.roomIdsWithMessages()
        var updated = 0
        var failed = 0
        roomIds.forEach { roomId ->
            try {
                if (recomputeRoom(target, roomId, selfUid, strings)) updated++
            } catch (e: Exception) {
                failed++
                L.w { "[MigrationV2][Import] room-state recompute failed roomId=$roomId: ${e.stackTraceToString()}" }
            }
        }
        L.i { "[MigrationV2][Import] room-state recomputed rooms=$updated failed=$failed" }
    }

    
    private fun recomputeRoom(
        target: RoomStateReadWrite,
        roomId: String,
        selfUid: String,
        strings: PreviewStrings,
    ): Boolean {
        val latest = target.latestMessagePreviewInfo(roomId) ?: return false
        
        
        val isArchive = isArchiveExpiredSystemMessage(latest)
        val lastDisplayContent = if (isArchive) "" else previewContent(latest, strings)
        val lastActiveTime = latest.systemShowTimestamp

        
        
        val readPosition = target.roomReadPosition(roomId)
        val unreadIds = target.unreadMessageIds(roomId, readPosition, selfUid)
        val unreadNum = unreadIds.size
        val mentionType = if (unreadNum == 0) {
            MENTIONS_TYPE_NONE
        } else {
            mentionRollup(target.mentionUidsForMessages(unreadIds, selfUid))
        }

        
        
        val sendStatus = if (target.hasFailedOutgoingMessage(roomId)) ROOM_SEND_STATUS_FAILED else ROOM_SEND_STATUS_NONE
        val criticalAlertType = if (unreadNum > 0 && target.hasCriticalAlert(unreadIds)) {
            CRITICAL_ALERT_TYPE_ALERT
        } else {
            CRITICAL_ALERT_TYPE_NONE
        }

        target.updateRoomDerivedState(
            roomId,
            RoomDerivedState(
                lastDisplayContent = lastDisplayContent,
                lastActiveTime = lastActiveTime,
                unreadMessageNum = unreadNum,
                mentionType = mentionType,
                sendStatus = sendStatus,
                criticalAlertType = criticalAlertType,
            ),
        )
        return true
    }

    




    private fun previewContent(m: LatestMessagePreviewInfo, strings: PreviewStrings): String = when {
        m.type == MessageModel.TYPE_UNSUPPORTED -> strings.unsupported()

        m.mode == 1 -> strings.confidential()

        m.type == MessageModel.TYPE_ATTACHMENT -> when {
            m.attachmentContentType?.contains("image") == true -> strings.image()
            isWhitelistedVideoType(m.attachmentContentType) -> strings.video()
            
            m.attachmentContentType?.contains("audio") == true -> strings.audio()
            else -> strings.attachment()
        }

        m.type == MessageModel.TYPE_TEXT -> when {
            m.hasSharedContact -> strings.contactCard()
            m.hasForwardContext -> "[" + strings.chatHistory() + "]"
            else -> m.messageText.orEmpty()
        }

        m.type == MessageModel.TYPE_NOTIFY -> m.notifyShowContent.orEmpty()

        else -> ""
    }

    
    private fun mentionRollup(mentionUids: List<String>): Int = when {
        mentionUids.any { it == SELF_MENTION_SENTINEL } -> MENTIONS_TYPE_ME
        mentionUids.any { it == MENTIONS_ALL_ID } -> MENTIONS_TYPE_ALL
        else -> MENTIONS_TYPE_NONE
    }

    




    private fun isArchiveExpiredSystemMessage(m: LatestMessagePreviewInfo): Boolean {
        if (m.type != MessageModel.TYPE_NOTIFY) return false
        return m.notifyActionType == NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED
    }

    




    const val SELF_MENTION_SENTINEL = "__SELF__"

    






    interface PreviewStrings {
        fun unsupported(): String
        fun confidential(): String
        fun image(): String
        fun video(): String
        fun audio(): String
        fun attachment(): String
        fun contactCard(): String
        fun chatHistory(): String

        
        object ResUtilsBacked : PreviewStrings {
            override fun unsupported() = ResUtils.getString(com.difft.android.base.R.string.chat_message_unsupported)
            override fun confidential() = ResUtils.getString(com.difft.android.base.R.string.chat_message_confidential_message)
            override fun image() = ResUtils.getString(com.difft.android.base.R.string.chat_message_image)
            override fun video() = ResUtils.getString(com.difft.android.base.R.string.chat_message_video)
            override fun audio() = ResUtils.getString(com.difft.android.base.R.string.chat_message_audio)
            override fun attachment() = ResUtils.getString(com.difft.android.base.R.string.chat_message_attachment)
            override fun contactCard() = ResUtils.getString(com.difft.android.base.R.string.chat_message_contact_card)
            override fun chatHistory() = ResUtils.getString(com.difft.android.base.R.string.chat_history)
        }
    }
}









interface RoomStateReadWrite {

    
    fun roomIdsWithMessages(): List<String>

    
    fun roomReadPosition(roomId: String): Long

    
    fun latestMessagePreviewInfo(roomId: String): LatestMessagePreviewInfo?

    




    fun unreadMessageIds(roomId: String, readPosition: Long, selfUid: String): List<String>

    




    fun mentionUidsForMessages(messageIds: List<String>, selfUid: String): List<String>

    




    fun hasFailedOutgoingMessage(roomId: String): Boolean

    
    fun hasCriticalAlert(messageIds: List<String>): Boolean

    
    fun updateRoomDerivedState(roomId: String, state: RoomDerivedState)
}


data class RoomDerivedState(
    val lastDisplayContent: String,
    val lastActiveTime: Long,
    val unreadMessageNum: Int,
    val mentionType: Int,
    val sendStatus: Int,
    val criticalAlertType: Int,
)













data class LatestMessagePreviewInfo(
    val id: String,
    val type: Int,
    val mode: Int,
    val messageText: String?,
    val systemShowTimestamp: Long,
    val attachmentContentType: String? = null,
    val hasSharedContact: Boolean = false,
    val hasForwardContext: Boolean = false,
    val notifyShowContent: String? = null,
    val notifyActionType: Int? = null,
)









internal object RoomStateSupport {

    
    private const val TYPE_CONFIDENTIAL_PLACEHOLDER = MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER

    private val gson = Gson()

    fun roomIdsWithMessages(messages: List<MessageModel>): List<String> =
        messages.mapNotNull { it.roomId?.takeIf { id -> id.isNotEmpty() } }.distinct()

    
    fun latestMessagePreviewInfo(
        roomId: String,
        messages: List<MessageModel>,
        attachments: List<AttachmentModel>,
        sharedContacts: List<SharedContactModel>,
        forwardContexts: List<ForwardContextModel>,
    ): LatestMessagePreviewInfo? {
        val latest = messages.filter { it.roomId == roomId }.maxByOrNull { it.systemShowTimestamp } ?: return null
        val fcPk = forwardContextPk(latest)
        return previewInfoOf(
            latest,
            attachmentContentType = attachments.firstOrNull { it.messageId == latest.id }?.contentType,
            hasSharedContact = sharedContacts.any { it.messageId == latest.id },
            hasForwardContext = fcPk != null && forwardContexts.any { it.databaseId.toLong() == fcPk },
        )
    }

    
    fun forwardContextPk(m: MessageModel): Long? = m.forwardContextDatabaseId?.takeIf { it != 0L }

    
    fun previewInfoOf(
        latest: MessageModel,
        attachmentContentType: String?,
        hasSharedContact: Boolean,
        hasForwardContext: Boolean,
    ): LatestMessagePreviewInfo {
        val notify = if (latest.type == MessageModel.TYPE_NOTIFY) parseNotify(latest.messageText) else null
        return LatestMessagePreviewInfo(
            id = latest.id,
            type = latest.type,
            mode = latest.mode,
            messageText = latest.messageText,
            systemShowTimestamp = latest.systemShowTimestamp,
            attachmentContentType = attachmentContentType,
            hasSharedContact = hasSharedContact,
            hasForwardContext = hasForwardContext,
            notifyShowContent = notify?.first,
            notifyActionType = notify?.second,
        )
    }

    
    fun unreadMessageIds(
        roomId: String,
        readPosition: Long,
        selfUid: String,
        messages: List<MessageModel>,
    ): List<String> = messages.filter { m ->
        m.roomId == roomId &&
            m.systemShowTimestamp > readPosition &&
            m.type != MessageModel.TYPE_NOTIFY &&
            m.type != TYPE_CONFIDENTIAL_PLACEHOLDER &&
            
            
            (selfUid.isEmpty() || !(m.fromWho ?: "").equals(selfUid, ignoreCase = true))
    }.map { it.id }

    




    fun mentionUidsForMessages(
        messageIds: List<String>,
        selfUid: String,
        mentions: List<MentionModel>,
    ): List<String> {
        val idSet = messageIds.toHashSet()
        return mentions.filter { it.messageId != null && idSet.contains(it.messageId) }
            .mapNotNull { mn ->
                val uid = mn.uid ?: return@mapNotNull null
                when {
                    selfUid.isNotEmpty() && uid.equals(selfUid, ignoreCase = true) ->
                        RoomStateRecomputer.SELF_MENTION_SENTINEL
                    uid == MENTIONS_ALL_ID -> MENTIONS_ALL_ID
                    else -> null
                }
            }
    }

    
    fun hasFailedOutgoingMessage(roomId: String, messages: List<MessageModel>): Boolean = messages.any { m ->
        m.roomId == roomId &&
            m.sendType == MessageModel.SEND_TYPE_FAILED &&
            m.type != MessageModel.TYPE_NOTIFY &&
            m.type != TYPE_CONFIDENTIAL_PLACEHOLDER &&
            m.screenShotJson.isNullOrEmpty()
    }

    
    fun hasCriticalAlert(messageIds: List<String>, messages: List<MessageModel>): Boolean {
        val idSet = messageIds.toHashSet()
        return messages.any { it.id in idSet && it.criticalAlertType == CRITICAL_ALERT_TYPE_ALERT }
    }

    
    private fun parseNotify(messageText: String?): Pair<String?, Int?> {
        val raw = messageText?.takeIf { it.isNotEmpty() } ?: return null to null
        val json: JsonObject = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
            ?: return null to null
        val showContent = json.get("showContent")?.takeIf { !it.isJsonNull }?.asString
        val actionType = runCatching {
            json.getAsJsonObject("data")?.get("actionType")?.takeIf { !it.isJsonNull }?.asInt
        }.getOrNull()
        return showContent to actionType
    }
}
