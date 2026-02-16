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
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.NotifyMessage
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地消息创建工具类
 *
 * 提供创建和保存本地消息的通用方法
 * 使用 Dagger 注入，内部处理 MessageStore 和消息过期时间获取
 */
@Singleton
class LocalMessageCreator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageStore: MessageStore,
    private val messageArchiveManager: MessageArchiveManager
) {

    companion object {
        private const val TAG = "LocalMessageCreator"

        /**
         * 生成消息 ID
         * 格式：timestamp + uid(去除+号) + deviceId
         *
         * @param timestamp 消息时间戳
         * @param uid 用户 ID
         * @param deviceId 设备 ID，默认为 DEFAULT_DEVICE_ID
         */
        fun generateMessageId(timestamp: Long, uid: String, deviceId: Int = DEFAULT_DEVICE_ID): String {
            return StringBuilder().apply {
                append(timestamp)
                append(uid.replace("+", ""))
                append(deviceId)
            }.toString()
        }
    }

    private val gson = Gson()

    /**
     * 为群消息设置 receiverIds
     * 只有自己发送的群消息才需要设置 receiverIds
     */
    private fun TextMessage.applyGroupReceiverIds(fromWho: For, forWhat: For): TextMessage {
        if (forWhat is For.Group && fromWho.id == globalServices.myId) {
            val receiverIds = wcdb.groupMemberContactor
                .getAllObjects(DBGroupMemberContactorModel.gid.eq(forWhat.id))
                .asSequence()
                .map { it.id }
                .filter { it != globalServices.myId }
                .toMutableSet()
            if (receiverIds.isNotEmpty()) {
                this.receiverIds = globalServices.gson.toJson(receiverIds)
            }
        }
        return this
    }

    /**
     * 创建并保存 Critical Alert 本地消息
     *
     * 此方法会自动获取消息过期时间并在 IO 线程执行数据库操作
     *
     * @param systemShowTimestamp 服务器系统时间戳
     * @param timestamp 消息时间戳（时间戳）
     * @param fromWho 消息发送者
     * @param forWhat 消息所属会话
     * @param sourceDevice 消息所属设备类型
     */
    suspend fun createCriticalAlertMessage(
        systemShowTimestamp: Long,
        timestamp: Long,
        fromWho: For,
        forWhat: For,
        sourceDevice: Int
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).await().toInt()
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
                card = null,
                mode = 0,
                criticalAlertType = CRITICAL_ALERT_TYPE_ALERT
            ).applyGroupReceiverIds(fromWho, forWhat)

            messageStore.putWhenNonExist(textMessage)
            L.i { "[$TAG] createCriticalAlertMessage success, messageId=$messageId, expiresInSeconds=$expiresInSeconds" }
        }.onFailure { error ->
            L.e { "[$TAG] createCriticalAlertMessage error: ${error.message}" }
        }
    }

    /**
     * 创建非好友限制消息
     *
     * TT 服务端增加限频，非好友每天只能发3条消息，
     * 超出后拒绝，客户端消息发送失败（感叹号），
     * 系统消息提示："You can only send up to 3 messages per day to a user who is not your friend."
     *
     * @param forWhat 消息所属会话
     */
    suspend fun createNonFriendLimitMessage(forWhat: For) = withContext(Dispatchers.IO) {
        runCatching {
            val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).await().toInt()
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

    /**
     * 构建一条对方离线或者账号禁用的消息，进行展示
     *
     * @param forWhat 消息所属会话
     * @param actionType 动作类型 (NOTIFY_ACTION_TYPE_OFFLINE / NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED / NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED)
     */
    suspend fun createOfflineMessage(forWhat: For, actionType: Int) = withContext(Dispatchers.IO) {
        runCatching {
            val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).await().toInt()
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

    /**
     * 创建一条reset identity key的notify消息
     *
     * @param operator 操作者ID
     * @param forWhat 消息所属会话
     * @param operateTime 操作时间
     * @param messageArchiveTime 消息归档时间
     */
    suspend fun createResetIdentityKeyMessage(
        operator: String,
        forWhat: For,
        operateTime: Long,
        messageArchiveTime: Long
    ): NotifyMessage = withContext(Dispatchers.IO) {
        val contactorName = if (operator == globalServices.myId) {
            ResUtils.getString(R.string.you)
        } else {
            ContactorUtil.getContactWithID(context, operator).await().orElse(null)?.getDisplayNameForUI()
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

    /**
     * 创建一条Earlier messages expired的系统消息
     *
     * @param messageRoomId 消息房间ID
     * @param messageRoomType 消息房间类型
     * @param messageSystemShowTimestamp 系统显示时间戳
     * @param messageReadTime 消息读取时间
     * @param messageExpiresInSeconds 消息过期时间
     */
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
            type = 2 // Notify
        }
    }

    /**
     * 创建通话相关的 TextMessage
     *
     * @param callActionType 通话动作类型
     * @param textContent 消息内容
     * @param sourceDevice 设备ID
     * @param timestamp 时间戳
     * @param systemShowTime 系统显示时间
     * @param fromWho 发送者
     * @param forWhat 会话
     * @param inviteeList 被邀请者列表（用于 INVITE 场景计算时间偏移）
     * @param saveToLocal 是否保存到本地数据库
     * @return TextMessage 对象
     */
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
        val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).await().toInt()

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
            card = null,
            mode = 0
        ).applyGroupReceiverIds(fromWho, forWhat)

        if (saveToLocal) {
            messageStore.putWhenNonExist(textMessage)
            L.i { "[$TAG] createCallTextMessage saved, messageId=$messageId, expiresInSeconds=$expiresInSeconds" }
        }

        textMessage
    }

}