package com.difft.android.chat.message

import android.content.Context
import com.difft.android.base.call.CallActionType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.util.ForwardNoticeRenderer
import com.difft.android.chat.util.MessageActivityNoticeRenderer
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.MessageActivityNoticeData
import difft.android.messageserialization.model.NotifyMessage
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class LocalMessageCreator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageStore: MessageStore,
    private val messageArchiveManager: MessageArchiveManager,
    private val gson: Gson,
) {

    companion object {
        private const val TAG = "LocalMessageCreator"

        





        fun generateMessageId(timestamp: Long, uid: String, deviceId: Int = DEFAULT_DEVICE_ID): String {
            return StringBuilder().apply {
                append(timestamp)
                append(uid.replace("+", ""))
                append(deviceId)
            }.toString()
        }
    }

    


    private fun TextMessage.applyGroupReceiverIds(fromWho: For, forWhat: For): TextMessage {
        if (forWhat is For.Group && fromWho.id == globalServices.myId) {
            val receiverIds = wcdb.groupMemberContactor
                .getAllObjects(DBGroupMemberContactorModel.gid.eq(forWhat.id))
                .asSequence()
                .map { it.id }
                .filter { it != globalServices.myId }
                .toMutableSet()
            if (receiverIds.isNotEmpty()) {
                this.receiverIds = gson.toJson(receiverIds)
            }
        }
        return this
    }

    







    suspend fun createCriticalAlertMessage(
        systemShowTimestamp: Long,
        timestamp: Long,
        fromWho: For,
        forWhat: For,
        sourceDevice: Int
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).toInt()
            val messageId = generateMessageId(timestamp, fromWho.id, sourceDevice)
            val text = context.getString(R.string.chat_message_critical_alert)
            val textMessage = TextMessage(
                id = messageId,
                fromWho = fromWho,
                forWhat = forWhat,
                text = text,
                systemShowTimestamp = systemShowTimestamp,
                timeStamp = timestamp,
                receivedTimeStamp = System.currentTimeMillis(),
                sendType = 1,
                expiresInSeconds = expiresInSeconds,
                notifySequenceId = 0,
                sequenceId = 0,
                atPersons = null,
                quote = null,
                forwardContext = null,
                recall = null,
                mode = 0,
                criticalAlertType = CRITICAL_ALERT_TYPE_ALERT
            ).applyGroupReceiverIds(fromWho, forWhat)

            messageStore.putWhenNonExist(textMessage)
            L.i { "[$TAG] createCriticalAlertMessage success, messageId=$messageId, expiresInSeconds=$expiresInSeconds" }
        }.onFailure { error ->
            L.e { "[$TAG] createCriticalAlertMessage error: ${error.message}" }
        }
    }

    



    suspend fun createNonFriendLimitMessage(forWhat: For) = withContext(Dispatchers.IO) {
        runCatching {
            val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).toInt()
            val timeStamp = System.currentTimeMillis()
            val myID = globalServices.myId
            val messageId = generateMessageId(timeStamp, myID)
            val signalNotifyMessage = TTNotifyMessage(
                Data(TTNotifyMessage.NOTIFY_ACTION_TYPE_NON_FRIEND_LIMIT),
                timeStamp,
                TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL
            )
            signalNotifyMessage.showContent = ResUtils.getString(R.string.contact_non_friend_limit_tips)
            val message = NotifyMessage(
                messageId,
                For.Account(myID),
                forWhat,
                timeStamp,
                timeStamp,
                System.currentTimeMillis(),
                SendType.Sent.rawValue,
                expiresInSeconds,
                0,
                0,
                0,
                gson.toJson(signalNotifyMessage)
            )
            messageStore.putWhenNonExist(message)
            L.i { "[$TAG] createNonFriendLimitMessage success, messageId=$messageId, expiresInSeconds=$expiresInSeconds" }
        }.onFailure { error ->
            L.e { "[$TAG] createNonFriendLimitMessage error: ${error.message}" }
        }
    }

    




    suspend fun createOfflineMessage(forWhat: For, actionType: Int) = withContext(Dispatchers.IO) {
        runCatching {
            val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).toInt()
            val timeStamp = System.currentTimeMillis()
            val messageId = generateMessageId(timeStamp, forWhat.id)
            val signalNotifyMessage = TTNotifyMessage(
                Data(actionType, -1, null),
                timeStamp,
                TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL
            )
            signalNotifyMessage.showContent = when (actionType) {
                TTNotifyMessage.NOTIFY_ACTION_TYPE_OFFLINE -> ResUtils.getString(R.string.contact_offline_tips)
                TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED -> ResUtils.getString(R.string.contact_account_exception_tips)
                TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED -> ResUtils.getString(R.string.contact_unregistered_tips)
                else -> ""
            }
            val myID = globalServices.myId
            val message = NotifyMessage(
                messageId,
                For.Account(myID),
                forWhat,
                timeStamp,
                timeStamp,
                System.currentTimeMillis(),
                SendType.Sent.rawValue,
                expiresInSeconds,
                0,
                0,
                0,
                gson.toJson(signalNotifyMessage)
            )
            messageStore.putWhenNonExist(message)
            L.i { "[$TAG] createOfflineMessage success, messageId=$messageId, actionType=$actionType, expiresInSeconds=$expiresInSeconds" }
        }.onFailure { error ->
            L.e { "[$TAG] createOfflineMessage error: ${error.message}" }
        }
    }

    






    suspend fun createResetIdentityKeyMessage(
        operator: String,
        forWhat: For,
        operateTime: Long,
        messageArchiveTime: Long
    ): NotifyMessage = withContext(Dispatchers.IO) {
        val contactorName = if (operator == globalServices.myId) {
            ResUtils.getString(R.string.you)
        } else {
            ContactorUtil.getContactWithID(context, operator).orElse(null)?.getDisplayNameForUI()
                ?: forWhat.id.formatBase58Id()
        }
        val messageId = generateMessageId(operateTime, forWhat.id)
        val signalNotifyMessage = TTNotifyMessage(
            Data(TTNotifyMessage.NOTIFY_ACTION_TYPE_RESET_IDENTITY_KEY),
            operateTime,
            TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL
        )
        signalNotifyMessage.showContent = ResUtils.getString(R.string.me_renew_identity_key_notify_tips, contactorName)
        NotifyMessage(
            messageId,
            forWhat,
            forWhat,
            operateTime,
            operateTime,
            System.currentTimeMillis(),
            SendType.Sent.rawValue,
            messageArchiveTime.toInt(),
            0,
            0,
            0,
            gson.toJson(signalNotifyMessage)
        )
    }

    
























    suspend fun createForwardNoticeMessage(
        operatorId: String,
        forWhat: For,
        noticeData: ForwardNoticeData,
        systemShowTimestamp: Long,
        timestamp: Long,
        sourceDevice: Int = DEFAULT_DEVICE_ID
    ): NotifyMessage = withContext(Dispatchers.IO) {
        val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).toInt()
        val messageId = generateMessageId(timestamp, operatorId, sourceDevice)

        
        val idsToResolve = buildSet {
            add(operatorId)
            addAll(noticeData.sourceAuthorIds)
        }
        val myId = globalServices.myId
        val preferredGid = (forWhat as? For.Group)?.id
        val contactorMap = wcdb.getContactorsFromAllTable(idsToResolve.toList(), preferredGid)
            .associateBy { it.id }
        val nameCache: Map<String, String> = idsToResolve.associateWith { id ->
            contactorMap[id]?.getDisplayNameForUI() ?: id.formatBase58Id()
        }

        val showContent = ForwardNoticeRenderer.render(
            operatorId = operatorId,
            myId = myId,
            notice = noticeData,
            context = context
        ) { id -> nameCache[id] ?: id.formatBase58Id() }

        val ttNotify = TTNotifyMessage(
            data = null,
            notifyTime = timestamp,
            notifyType = TTNotifyMessage.NOTIFY_ACTION_TYPE_FORWARD_NOTICE,
            showContent = showContent,
            display = 1
        )

        val message = NotifyMessage(
            messageId,
            For.Account(operatorId),
            forWhat,
            systemShowTimestamp,
            timestamp,
            System.currentTimeMillis(),
            SendType.Sent.rawValue,
            expiresInSeconds,
            0,
            0,
            0,
            gson.toJson(ttNotify)
        )

        
        messageStore.putWhenNonExist(message)
        L.i {
            "[$TAG] createForwardNoticeMessage success, messageId=$messageId, " +
                "scene=${noticeData.scene}, count=${noticeData.messageCount}, " +
                "authors=${noticeData.sourceAuthorIds.size}"
        }
        message
    }

    
    suspend fun createActivityNoticeMessage(
        operatorId: String,
        forWhat: For,
        noticeData: MessageActivityNoticeData,
        systemShowTimestamp: Long,
        timestamp: Long,
        sourceDevice: Int = DEFAULT_DEVICE_ID
    ): NotifyMessage = withContext(Dispatchers.IO) {
        val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).toInt()
        val messageId = generateMessageId(timestamp, operatorId, sourceDevice)

        val idsToResolve = buildSet {
            add(operatorId)
            addAll(noticeData.sourceAuthorIds)
        }
        val myId = globalServices.myId
        val preferredGid = (forWhat as? For.Group)?.id
        val contactorMap = wcdb.getContactorsFromAllTable(idsToResolve.toList(), preferredGid)
            .associateBy { it.id }
        val nameCache: Map<String, String> = idsToResolve.associateWith { id ->
            contactorMap[id]?.getDisplayNameForUI() ?: id.formatBase58Id()
        }

        val showContent = MessageActivityNoticeRenderer.render(
            operatorId = operatorId,
            myId = myId,
            notice = noticeData,
            context = context
        ) { id -> nameCache[id] ?: id.formatBase58Id() }

        val ttNotify = TTNotifyMessage(
            data = null,
            notifyTime = timestamp,
            notifyType = TTNotifyMessage.NOTIFY_ACTION_TYPE_COPY_NOTICE,
            showContent = showContent,
            display = 1
        )

        val message = NotifyMessage(
            messageId,
            For.Account(operatorId),
            forWhat,
            systemShowTimestamp,
            timestamp,
            System.currentTimeMillis(),
            SendType.Sent.rawValue,
            expiresInSeconds,
            0,
            0,
            0,
            gson.toJson(ttNotify)
        )

        messageStore.putWhenNonExist(message)
        L.i {
            "[$TAG] createActivityNoticeMessage success, messageId=$messageId, " +
                "type=${noticeData.type}, count=${noticeData.messageCount}, " +
                "authors=${noticeData.sourceAuthorIds.size}"
        }
        message
    }

    







    suspend fun createEarlierMessagesExpiredMessage(
        messageRoomId: String,
        messageRoomType: Int,
        messageSystemShowTimestamp: Long,
        messageReadTime: Long,
        messageExpiresInSeconds: Int
    ): MessageModel = withContext(Dispatchers.IO) {
        val operateTime = System.currentTimeMillis()
        val messageId = generateMessageId(operateTime, globalServices.myId)
        val signalNotifyMessage = TTNotifyMessage(
            Data(TTNotifyMessage.NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED),
            operateTime,
            TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL
        )
        signalNotifyMessage.showContent = ResUtils.getString(R.string.chat_archive_messages_expired)

        MessageModel().apply {
            id = messageId
            fromWho = globalServices.myId
            roomId = messageRoomId
            roomType = messageRoomType
            systemShowTimestamp = messageSystemShowTimestamp
            timeStamp = operateTime
            readTime = messageReadTime
            expiresInSeconds = messageExpiresInSeconds
            mode = 0
            messageText = gson.toJson(signalNotifyMessage)
            type = 2 
        }
    }

    












    suspend fun createCallTextMessage(
        callActionType: CallActionType,
        textContent: String,
        sourceDevice: Int,
        timestamp: Long,
        systemShowTime: Long,
        fromWho: For,
        forWhat: For,
        inviteeList: List<String> = emptyList(),
        saveToLocal: Boolean = true
    ): TextMessage = withContext(Dispatchers.IO) {
        val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).toInt()

        val callMessageTime = when (callActionType) {
            CallActionType.INVITE -> timestamp + inviteeList.indexOf(forWhat.id)
            else -> timestamp
        }

        val messageId = generateMessageId(callMessageTime, fromWho.id, sourceDevice)

        val textMessage = TextMessage(
            id = messageId,
            fromWho = fromWho,
            forWhat = forWhat,
            text = textContent,
            systemShowTimestamp = systemShowTime,
            timeStamp = callMessageTime,
            receivedTimeStamp = System.currentTimeMillis(),
            sendType = 1,
            expiresInSeconds = expiresInSeconds,
            notifySequenceId = 0,
            sequenceId = 0,
            atPersons = null,
            quote = null,
            forwardContext = null,
            recall = null,
            mode = 0
        ).applyGroupReceiverIds(fromWho, forWhat)

        if (saveToLocal) {
            messageStore.putWhenNonExist(textMessage)
            L.i { "[$TAG] createCallTextMessage saved, messageId=$messageId, expiresInSeconds=$expiresInSeconds" }
        }

        textMessage
    }

}