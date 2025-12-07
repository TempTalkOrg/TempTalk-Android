package org.thoughtcrime.securesms.util

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.net.toUri
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.LCallConstants.CALL_NOTIFICATION_OPERATION_REJECT
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.IMessageNotificationUtil
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.call.CallIntent
import com.difft.android.call.InComingNewCallActionReceiver
import com.difft.android.call.LCallManager
import com.difft.android.chat.R
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.message.getRecordMessageContentTwo
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.ConversationUtils
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.TextMessage
import com.difft.android.network.responses.MuteStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitFirst
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBRoomModel
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.util.FullScreenPermissionHelper
import com.difft.android.chat.common.CriticalAlertSoundPlayer
import com.difft.android.chat.common.StopCriticalAlertSoundReceiver


@Singleton
class MessageNotificationUtil @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val userManager: UserManager,
    private val appIconBadgeManager: AppIconBadgeManager,
    private val activityProvider: ActivityProvider,
    private val cacheManager: NotificationCacheManager,
) : IMessageNotificationUtil {

    companion object {
        private const val NOTIFICATION_GROUP_KEY = "NOTIFICATION_GROUP_KEY"
        private const val MESSAGE_SUMMARY_NOTIFICATION_ID = 10000

        private const val CHANNEL_CONFIG_NAME_MESSAGE_SUMMARY = "MESSAGE_SUMMARY"
        private const val CHANNEL_CONFIG_NAME_MESSAGE = "MESSAGE"
        private const val CHANNEL_CONFIG_NAME_CALL = "CALL"
        private const val CHANNEL_CONFIG_NAME_ONGOING_CALL = "ONGOING_CALL"
        private const val CHANNEL_CONFIG_NAME_BACKGROUND = "BACKGROUND"
        private const val CHANNEL_CONFIG_NAME_CRITICAL_ALERT = "CRITICAL_ALERT_V2"
        private const val CHANNEL_CONFIG_MESSAGE_GROUP = "MESSAGE_GROUP"
        const val STOP_CRITICAL_ALERT_SOUND = "STOP_CRITICAL_ALERT_SOUND"
    }


    private val nm: NotificationManager by lazy {
        ServiceUtil.getNotificationManager(context)
    }


    fun checkAndCreateNotificationChannels() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            nm.notificationChannels.forEach {
//                if (it.id !in listOf(
//                        CHANNEL_CONFIG_NAME_MESSAGE,
//                        CHANNEL_CONFIG_NAME_CALL,
//                        CHANNEL_CONFIG_NAME_ONGOING_CALL,
//                        CHANNEL_CONFIG_NAME_BACKGROUND,
//                    )
//                ) {
//                    deleteNotificationChannelSafely(it.id)
//                }
//            }
//        }
        createNotificationChannelGroup()
        checkSummaryChannel()
        checkMessageChannel()
        checkCallChannel()
        checkOngoingCallChannel()
        checkBackgroundChannel()
        checkCriticalAlertChannel()

        // 启动时异步清理过期缓存
        try {
            appScope.launch(Dispatchers.IO) {
                cacheManager.cleanupOldCache()
            }
        } catch (e: Exception) {
            L.e { "[MessageNotificationUtil] Failed to start cleanup task: ${e.message}" }
        }
    }


    private fun checkSummaryChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = nm.getNotificationChannel(CHANNEL_CONFIG_NAME_MESSAGE_SUMMARY)
            if (existingChannel == null) {
                val notificationChannel = NotificationChannel(
                    CHANNEL_CONFIG_NAME_MESSAGE_SUMMARY,
                    CHANNEL_CONFIG_NAME_MESSAGE_SUMMARY,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationChannel.group = CHANNEL_CONFIG_MESSAGE_GROUP
                notificationChannel.setShowBadge(false)
                notificationChannel.enableVibration(false)
                notificationChannel.setSound(null, null)
                nm.createNotificationChannel(notificationChannel)
            }
        }
    }

    private fun checkMessageChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = nm.getNotificationChannel(CHANNEL_CONFIG_NAME_MESSAGE)
            if (existingChannel == null || !existingChannel.canShowBadge() || existingChannel.group == null) {
                deleteNotificationChannelSafely(CHANNEL_CONFIG_NAME_MESSAGE)
                val notificationChannel = NotificationChannel(
                    CHANNEL_CONFIG_NAME_MESSAGE,
                    CHANNEL_CONFIG_NAME_MESSAGE,
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationChannel.group = CHANNEL_CONFIG_MESSAGE_GROUP
                notificationChannel.setShowBadge(true)
                notificationChannel.enableVibration(true)
                nm.createNotificationChannel(notificationChannel)
            }
        }
    }

    private fun checkCriticalAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = nm.getNotificationChannel(CHANNEL_CONFIG_NAME_CRITICAL_ALERT)
            if (existingChannel == null || existingChannel.canShowBadge()) {
                deleteNotificationChannelSafely(CHANNEL_CONFIG_NAME_CRITICAL_ALERT)
                val notificationChannel = NotificationChannel(
                    CHANNEL_CONFIG_NAME_CRITICAL_ALERT,
                    CHANNEL_CONFIG_NAME_CRITICAL_ALERT,
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationChannel.setShowBadge(false)
                notificationChannel.enableVibration(true)
                notificationChannel.setBypassDnd(true)
//                val ringtoneUri = Uri.parse("android.resource://${context.packageName}/${R.raw.critical_alert}")
//                notificationChannel.setSound(ringtoneUri,
//                    AudioAttributes.Builder()
//                        .setUsage(AudioAttributes.USAGE_ALARM)
//                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
//                        .build())
                notificationChannel.vibrationPattern = longArrayOf(0, 100)
                nm.createNotificationChannel(notificationChannel)
            }
        }
    }

    private fun checkCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = nm.getNotificationChannel(CHANNEL_CONFIG_NAME_CALL)
            if (existingChannel == null || existingChannel.canShowBadge()) {
                deleteNotificationChannelSafely(CHANNEL_CONFIG_NAME_CALL)
                val notificationChannel = NotificationChannel(
                    CHANNEL_CONFIG_NAME_CALL,
                    CHANNEL_CONFIG_NAME_CALL,
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationChannel.setShowBadge(false)
                notificationChannel.enableVibration(true)
                notificationChannel.vibrationPattern = longArrayOf(0, 100)
                nm.createNotificationChannel(notificationChannel)
            }
        }
    }

    private fun checkOngoingCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = nm.getNotificationChannel(
                CHANNEL_CONFIG_NAME_ONGOING_CALL
            )
            if (existingChannel == null || existingChannel.canShowBadge()) {
                deleteNotificationChannelSafely(CHANNEL_CONFIG_NAME_ONGOING_CALL)
                val notificationChannel = NotificationChannel(
                    CHANNEL_CONFIG_NAME_ONGOING_CALL,
                    CHANNEL_CONFIG_NAME_ONGOING_CALL,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null) // 不设置声音
                    enableVibration(false) // 不震动
                }
                nm.createNotificationChannel(notificationChannel)
            }
        }
    }

    private fun checkBackgroundChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = nm.getNotificationChannel(CHANNEL_CONFIG_NAME_BACKGROUND)
            if (existingChannel == null || existingChannel.canShowBadge()) {
                deleteNotificationChannelSafely(CHANNEL_CONFIG_NAME_BACKGROUND)
                val notificationChannel = NotificationChannel(
                    CHANNEL_CONFIG_NAME_BACKGROUND,
                    CHANNEL_CONFIG_NAME_BACKGROUND,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(notificationChannel)
            }
        }
    }

    private fun createNotificationChannelGroup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val group = NotificationChannelGroup(CHANNEL_CONFIG_MESSAGE_GROUP, CHANNEL_CONFIG_MESSAGE_GROUP)
                nm.createNotificationChannelGroup(group)
            } catch (e: Exception) {
                L.e { "[MessageNotificationUtil] create notification channel group $CHANNEL_CONFIG_MESSAGE_GROUP fail:" + e.stackTraceToString() }
            }
        }
    }

    private fun deleteNotificationChannelSafely(channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                nm.deleteNotificationChannel(channelId)
            } catch (e: Exception) {
                L.e { "[MessageNotificationUtil] delete notification channel $channelId fail:" + e.stackTraceToString() }
            }
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    fun supportConversationNotification(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    fun getConversationChannelId(conversationId: String): String {
        return CHANNEL_CONFIG_NAME_MESSAGE + conversationId
    }

    fun createChannelForConversation(conversationId: String, name: String) {
        try {
            if (supportConversationNotification()) {
                val channelId = getConversationChannelId(conversationId)
                val existingChannel = nm.getNotificationChannel(channelId)
                if (existingChannel == null) {
                    val notificationChannel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH)
                    notificationChannel.group = CHANNEL_CONFIG_MESSAGE_GROUP
                    notificationChannel.setShowBadge(true)
                    notificationChannel.enableVibration(true)
                    notificationChannel.setConversationId(CHANNEL_CONFIG_NAME_MESSAGE, conversationId)
                    nm.createNotificationChannel(notificationChannel)
                }
            }
        } catch (e: Exception) {
            L.e { "[MessageNotificationUtil] createChannelForConversation $conversationId failed: ${e.stackTraceToString()}" }
        }
    }

    /**
     * 显示通知
     */
    suspend fun showNotificationSuspend(
        context: Context,
        message: Message,
        forWhat: For,
        isRecall: Boolean = false, // 是否是撤回消息
    ) = runCatching {
        // ⚠️ 撤回消息：删除缓存，判断是否需要重建通知（无条件执行，跳过拦截）
        if (isRecall) {
            val removedCount = cacheManager.removeMessageByTimestamp(forWhat.id, message.systemShowTimestamp)
            L.i { "[MessageNotificationUtil] Recall message for ${forWhat.id} timestamp:${message.systemShowTimestamp} removed:$removedCount" }

            val messageList = cacheManager.getMessages(forWhat.id)
            if (messageList.isEmpty()) {
                // 没有剩余消息，取消通知
                val notificationId = forWhat.id.hashCode()
                nm.cancel(notificationId)
                L.i { "[MessageNotificationUtil] All messages recalled for ${forWhat.id}, notification cancelled" }

                // 检查是否还有其他消息通知，如果没有则取消汇总通知
                // 注意：nm.cancel() 是异步的，需要排除刚取消的通知
                if (!hasAnyMessageNotifications(excludeNotificationId = notificationId)) {
                    nm.cancel(MESSAGE_SUMMARY_NOTIFICATION_ID)
                    L.i { "[MessageNotificationUtil] No message notifications left, summary notification cancelled" }
                }
                return@runCatching
            }

            // 有剩余消息，继续往下执行重建通知（跳过拦截条件检查）
            L.i { "[MessageNotificationUtil] Recall: has ${messageList.size} remaining messages, rebuilding notification (skip interceptions)" }
        } else {
            // 正常新消息：检查拦截条件
            if (shouldInterceptNotification(forWhat, message)) {
                return@runCatching
            }
        }

        val intent = createConversationIntent(forWhat)

        val fromId = message.fromWho.id
        var sender: ContactorModel? = null

        // 获取用户的通知显示设置
        val displayType = userManager.getUserData()?.notificationContentDisplayType ?: 0

        // 根据设置决定是否需要计算title和content
        val needTitle = displayType != NotificationContentDisplayType.NO_NAME_OR_CONTENT.value
        val needContent = displayType == NotificationContentDisplayType.NAME_AND_CONTENT.value

        var title: String

        if (needTitle) {
            val contactor = ContactorUtil.getContactWithID(context, fromId).await()
            if (contactor.isPresent) {
                sender = contactor.get()
            }
            if (forWhat is For.Group) {
                val group = GroupUtil.getSingleGroupInfo(context, forWhat.id, false).awaitFirst()
                title = if (group.isPresent) {
                    group.get().name.toString()
                } else {
                    forWhat.id
                }
            } else {
                title = sender?.getDisplayNameForUI() ?: fromId.formatBase58Id()
            }
        } else {
            // NO_NAME_OR_CONTENT 情况下，设置默认值
            title = PackageUtil.getAppName() ?: ""
        }

        val content: String = if (needContent) {
            getRecordMessageContentTwo(message, false, "")
        } else {
            ResUtils.getString(R.string.notification_received_message)
        }

        val unreadMessageNumber = getUnreadMessageNumber()
        appIconBadgeManager.updateAppIconBadgeNum(unreadMessageNumber)

        val notificationID = forWhat.id.hashCode()

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            notificationID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val personName = if (displayType == NotificationContentDisplayType.NO_NAME_OR_CONTENT.value) {
            PackageUtil.getAppName() ?: ""
        } else {
            sender?.getDisplayNameForUI() ?: fromId.formatBase58Id()
        }

        val user = Person.Builder().setName(personName).setKey(fromId).build()

        // 新消息:添加到缓存（存储消息发送时刻的快照数据）
        // 撤回消息不需要添加，因为已经在开头删除了
        if (!isRecall) {
            cacheManager.addMessage(
                forWhat.id,
                NotificationCacheManager.NotificationMessageData(
                    content = content,
                    timestamp = message.systemShowTimestamp,
                    personKey = fromId,
                    personName = personName
                )
            )
        }

        // 获取排序后的消息列表
        val messageList = cacheManager.getMessages(forWhat.id)

        createSummaryNotification(context)

        // 构建MessagingStyle
        val messagingStyle = NotificationCompat.MessagingStyle(user)
        if (forWhat is For.Group) {
            messagingStyle.isGroupConversation = true
            messagingStyle.conversationTitle = title
        }

        // 去重并添加消息到通知样式(按时间顺序)
        // 直接使用缓存的快照数据，不再查询数据库
        messageList.distinctBy { it.timestamp }.forEach { msgData ->
            val person = Person.Builder()
                .setName(msgData.personName)
                .setKey(msgData.personKey)
                .build()

            messagingStyle.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    msgData.content,
                    msgData.timestamp,
                    person
                )
            )
        }

        val pendingDeleteIntent = createDeletePendingIntent(forWhat.id)

        val channelId = selectNotificationChannel(forWhat.id)
        L.i { "[MessageNotificationUtil] forWhat:${forWhat.id} channelId:${channelId}" }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE) // 标记为消息类通知，提升权重
            .setPriority(if (isRecall) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH) // 撤回时低优先级，不显示横幅
            .setAutoCancel(true)
            .setNumber(unreadMessageNumber)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setWhen(message.systemShowTimestamp)
            .setStyle(messagingStyle)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setDeleteIntent(pendingDeleteIntent)
            .setOnlyAlertOnce(isRecall) // 撤回时不震动/响铃，新消息时震动/响铃
            .setSilent(isRecall) // 撤回时完全静默（Android 8.0+）

        nm.notify(notificationID, builder.build())
    }.onFailure {
        L.e { "showNotification failed: ${it.stackTraceToString()}" }
    }

    private fun isMentionMessage(message: Message): Boolean {
        if (message !is TextMessage) return false

        val mentions = message.mentions ?: return false
        if (mentions.isEmpty()) return false

        return mentions.any { mention ->
            mention.uid == globalServices.myId || mention.uid == MENTIONS_ALL_ID
        }
    }

    private fun createSummaryNotification(context: Context) {
        if (isNotificationShowing(MESSAGE_SUMMARY_NOTIFICATION_ID)) {
            L.d { "Summary notification already exists, no need to create again." }
            return
        }
        val summaryNotification = NotificationCompat.Builder(context, CHANNEL_CONFIG_NAME_MESSAGE_SUMMARY)
            .setContentTitle(PackageUtil.getAppName())
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(MESSAGE_SUMMARY_NOTIFICATION_ID, summaryNotification)
    }

    private fun getUnreadMessageNumber(): Int {
        return SharedPrefsUtil.getInt(SharedPrefsUtil.SP_UNREAD_MSG_NUM)
    }

    /**
     * 检查是否应该拦截通知（返回true表示应该拦截，不显示通知）
     * @param forWhat 会话信息
     * @param message 消息对象（可选，用于检查是否已读和@提及）
     * @return true表示应该拦截，false表示可以显示通知
     */
    private fun shouldInterceptNotification(forWhat: For, message: Message? = null): Boolean {
        // 1. 检查聊天窗口/会话列表/屏幕共享状态
        if (SendMessageUtils.isExistChat(forWhat.id) ||
            ConversationUtils.isConversationListVisible ||
            LCallManager.isCallScreenSharing()
        ) {
            L.i { "[MessageNotificationUtil] Intercepted: isExistChat:${SendMessageUtils.isExistChat(forWhat.id)} isConversationListVisible:${ConversationUtils.isConversationListVisible} isCallScreenSharing:${LCallManager.isCallScreenSharing()}" }
            return true
        }

        // 2. 检查静音状态和已读位置
        val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(forWhat.id))
        if (room != null) {
            if (room.muteStatus == MuteStatus.MUTED.value) {
                L.i { "[MessageNotificationUtil] ${forWhat.id} isMuted" }
                return true
            }
            // 如果有消息对象，检查是否已读
            if (message != null && message.systemShowTimestamp <= room.readPosition) {
                L.i { "[MessageNotificationUtil] Message already read (timestamp: ${message.systemShowTimestamp} <= readPosition: ${room.readPosition})" }
                return true
            }
        }

        // 3. 检查群组通知设置
        if (forWhat is For.Group) {
            val mySelfGroupInfo = wcdb.groupMemberContactor.getFirstObject(
                (DBGroupMemberContactorModel.gid.eq(forWhat.id))
                    .and(DBGroupMemberContactorModel.id.eq(globalServices.myId))
            )

            val notificationType = if (mySelfGroupInfo == null || mySelfGroupInfo.useGlobal == true) {
                userManager.getUserData()?.globalNotification
            } else {
                mySelfGroupInfo.notification
            }

            when (notificationType) {
                GlobalNotificationType.OFF.value -> {
                    L.i { "[MessageNotificationUtil] Global notification is OFF for ${forWhat.id}" }
                    return true
                }

                GlobalNotificationType.MENTION.value -> {
                    // 如果设置为仅@提及，且有消息对象，检查是否@了当前用户
                    if (message != null && !isMentionMessage(message)) {
                        L.i { "[MessageNotificationUtil] Global notification is MENTION, but not mentioned for ${forWhat.id}" }
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * 创建会话跳转Intent
     */
    private fun createConversationIntent(forWhat: For): Intent {
        val intent = Intent(context, activityProvider.getActivityClass(ActivityType.MAIN))
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(LinkDataEntity.LINK_CATEGORY, LinkDataEntity.CATEGORY_MESSAGE)
        if (forWhat is For.Group) {
            intent.putExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID, forWhat.id)
        } else {
            intent.putExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID, forWhat.id)
        }
        return intent
    }

    /**
     * 创建删除监听PendingIntent
     */
    private fun createDeletePendingIntent(conversationId: String): PendingIntent {
        val deleteIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("conversation_id", conversationId)
        }
        return PendingIntent.getBroadcast(
            context,
            conversationId.hashCode(),
            deleteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 选择通知Channel
     */
    private fun selectNotificationChannel(conversationId: String): String {
        return if (supportConversationNotification() &&
            nm.getNotificationChannel(getConversationChannelId(conversationId)) != null
        ) {
            getConversationChannelId(conversationId)
        } else {
            CHANNEL_CONFIG_NAME_MESSAGE
        }
    }

    //无法解析出加密消息内容时显示默认通知
    fun showNotificationOfPush(
        context: Context,
        forWhat: For
    ) = runCatching {
        // 检查是否已有通知正在显示，如果有则不显示兜底通知，避免覆盖正常的MessagingStyle通知
        val notificationID = forWhat.id.hashCode()
        if (isNotificationShowing(notificationID)) {
            L.i { "[MessageNotificationUtil] Normal notification is showing for ${forWhat.id}, skipping push fallback" }
            return@runCatching
        }

        // 使用统一的拦截检查（不传message参数，因为兜底通知没有message对象）
        if (shouldInterceptNotification(forWhat, null)) {
            return@runCatching
        }

        val intent = createConversationIntent(forWhat)

        createSummaryNotification(context)

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            notificationID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 添加删除监听，用于清理缓存（虽然兜底通知不写缓存，但为了一致性和防止将来逻辑变化）
        val pendingDeleteIntent = createDeletePendingIntent(forWhat.id)

        val channelId = selectNotificationChannel(forWhat.id)
        L.i { "[MessageNotificationUtil] forWhat:${forWhat.id} channelId:${channelId}" }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(ResUtils.getString(R.string.notification_received_message))
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE) // 标记为消息类通知，提升权重
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setNumber(getUnreadMessageNumber())
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setWhen(System.currentTimeMillis()) // 显示通知到达时间
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setDeleteIntent(pendingDeleteIntent) // 监听通知删除，用于清理缓存
            .setOnlyAlertOnce(true) // 避免同一会话多次解密失败时重复提醒

        nm.notify(notificationID, builder.build())
    }.onFailure {
        L.e { "showNotificationOfPush failed: ${it.stackTraceToString()}" }
    }

    fun showCallNotificationNew(roomId: String, callName: String, callerId: String, conversationId: String?, callType: CallType) {

        val notificationID = roomId.hashCode()  //roomId为空
        var title = ""
        var content = ""
        val callerInfo = ContactorUtil.getContactWithID(context, callerId).blockingGet()
        if (callType.isGroup()) {
            conversationId ?: return
            val groupInfo = GroupUtil.getSingleGroupInfo(context, conversationId).blockingFirst()
            title = if (groupInfo.isPresent) groupInfo.get().name ?: conversationId else conversationId
            content = "${if (callerInfo.isPresent) callerInfo.get().getDisplayNameForUI() else callerId.formatBase58Id()} ${ResUtils.getString(R.string.call_invite_of_group)}"
        } else if (callType.isOneOnOne()) {
            title = if (callerInfo.isPresent) callerInfo.get().getDisplayNameForUI() else callerId.formatBase58Id()
            content = ResUtils.getString(R.string.call_invite)
        } else if (callType.isInstant()) {
            title = if (callerInfo.isPresent) callerInfo.get().getDisplayNameForUI() else callerId.formatBase58Id()
            content = ResUtils.getString(R.string.call_invite)
        }

        L.d { "[Call] showCallNotificationNew createCallIntent roomId:$roomId callName:$callName callerId:$callerId conversationId:$conversationId" }
        val intent = createCallIntent(context, callType, callerId, roomId, conversationId, null, callName)
        val pendingIntent = createPendingIntent(context, notificationID, intent)
        val acceptIntent = CallIntent.Builder(context, activityProvider.getActivityClass(ActivityType.L_INCOMING_CALL))
            .withAction(CallIntent.Action.ACCEPT_CALL)
            .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .withCallType(callType.type)
            .withCallerId(callerId)
            .withRoomId(roomId)
            .withRoomName(callName)
            .withConversationId(conversationId)
            .withCallRole(CallRole.CALLEE.type)
            .build()
        val acceptPendingIntent = createPendingIntent(context, notificationID, acceptIntent)

        val rejectIntent = Intent(context, InComingNewCallActionReceiver::class.java).apply {
            action = CALL_NOTIFICATION_OPERATION_REJECT
            putExtra(LCallConstants.BUNDLE_KEY_CALLER_ID, callerId)
            putExtra(LCallConstants.KEY_CALLING_NOTIFICATION_ID, notificationID)
            putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, callType.type)
            putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, roomId)
            putExtra(LCallConstants.BUNDLE_KEY_CONVERSATION_ID, conversationId)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationID,
            rejectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_CONFIG_NAME_CALL)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setFullScreenIntent(pendingIntent, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder().setName(title).setKey(roomId).build()
            val callStyle = NotificationCompat.CallStyle.forIncomingCall(
                person,
                rejectPendingIntent,
                acceptPendingIntent
            )
            builder.setStyle(callStyle)
        } else {
            builder.addAction(
                R.mipmap.chat_ic_call_reject,
                ResUtils.getString(R.string.call_reject),
                rejectPendingIntent
            )
            builder.addAction(
                R.mipmap.chat_ic_call_accept,
                ResUtils.getString(R.string.call_accept),
                acceptPendingIntent
            )
            builder.setStyle(NotificationCompat.InboxStyle())
        }

        L.i { "[Call] showCallNotificationNew notificationID:$notificationID roomId:$roomId" }
        try {
            nm.notify(notificationID, builder.build())
        } catch (e: Exception) {
            L.e { "[Call] showCallNotificationNew failed:${e.message}" }
        }
    }

    private fun createCallIntent(context: Context, callType: CallType, callerId: String, roomId: String, conversationId: String?, action: String? = null, roomName: String): Intent {
        return CallIntent.Builder(context, activityProvider.getActivityClass(ActivityType.L_INCOMING_CALL))
            .withAction(CallIntent.Action.INCOMING_CALL)
            .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .withCallType(callType.type)
            .withCallerId(callerId)
            .withRoomId(roomId)
            .withRoomName(roomName)
            .withConversationId(conversationId)
            .withCallRole(CallRole.CALLEE.type)
            .build()
    }

    private fun createPendingIntent(context: Context, notificationID: Int, intent: Intent): PendingIntent {
        return PendingIntent.getActivity(
            context,
            notificationID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun createMessageForegroundNotification(): Notification {
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFIG_NAME_BACKGROUND)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(ResUtils.getString(R.string.background_connection_enabled))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setWhen(0)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .build()

        return notification
    }

    override fun cancelAllNotifications() {
        L.i { "[MessageNotificationUtil] cancelAllNotifications" }
        nm.cancelAll()

        // 异步清理所有缓存，避免主线程阻塞
        appScope.launch(Dispatchers.IO) {
            cacheManager.clearAll()
            L.i { "[MessageNotificationUtil] All notification caches cleared" }
        }
    }

    override fun getNotificationChannelName(): String {
        return CHANNEL_CONFIG_NAME_CALL
    }

    override fun cancelNotificationsByConversation(conversationId: String?) {
        L.i { "[MessageNotificationUtil] cancelNotificationsByConversation:${conversationId}" }
        conversationId?.let {
            val notificationId = it.hashCode()
            nm.cancel(notificationId)

            // 异步清理缓存，避免主线程阻塞
            appScope.launch(Dispatchers.IO) {
                cacheManager.removeConversation(it)

                // 检查是否还有其他消息通知，如果没有则取消汇总通知
                // 注意：nm.cancel() 是异步的，需要排除刚取消的通知
                if (!hasAnyMessageNotifications(excludeNotificationId = notificationId)) {
                    nm.cancel(MESSAGE_SUMMARY_NOTIFICATION_ID)
                    L.i { "[MessageNotificationUtil] No message notifications left, summary notification cancelled" }
                }
            }
        }
    }

    fun cancelNotificationsById(id: Int) {
        L.i { "[MessageNotificationUtil] cancelNotificationsById:${id}" }
        nm.cancel(id)
    }

    fun isNotificationShowing(notificationId: Int): Boolean {
        val activeNotifications = nm.activeNotifications
        for (notification in activeNotifications) {
            if (notification.id == notificationId) {
                return true
            }
        }
        return false
    }

    /**
     * 检查是否还有消息类通知（排除汇总通知）
     * @param excludeNotificationId 需要排除的通知ID（用于处理 nm.cancel() 异步取消的竞态条件）
     */
    private fun hasAnyMessageNotifications(excludeNotificationId: Int? = null): Boolean {
        return try {
            nm.activeNotifications.any { notification ->
                notification.id != MESSAGE_SUMMARY_NOTIFICATION_ID &&
                        (excludeNotificationId == null || notification.id != excludeNotificationId) &&
                        notification.notification.group == NOTIFICATION_GROUP_KEY
            }
        } catch (e: Exception) {
            L.e { "[MessageNotificationUtil] hasAnyMessageNotifications failed: ${e.message}" }
            false
        }
    }

    fun openNotificationSettings(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                }
                activity.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_SETTINGS)
                activity.startActivity(intent)
            }
        } catch (e: Exception) {
            L.i { "[MessageNotificationUtil] openNotificationSettings fail:" + e.stackTraceToString() }
            ToastUtil.show(R.string.notification_settings_open_fail)
        }
    }

    fun openFullScreenNotificationSettings(activity: Activity) {
        try {
            if (FullScreenPermissionHelper.isMainStreamChinaMobile()) {
                FullScreenPermissionHelper.jumpToPermissionSettingActivity(activity)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        setData(("package:" + activity.packageName).toUri())
                    }
                    activity.startActivity(intent)
                } else {
                    openNotificationSettings(activity)
                }
            }
        } catch (e: Exception) {
            L.i { "[MessageNotificationUtil] openFullScreenNotificationSettings fail:" + e.stackTraceToString() }
            openNotificationSettings(activity)
        }
    }

    /**
     * 检查是否有通知权限（仅检查POST_NOTIFICATIONS权限，Android 13+）
     * 注意：这个方法只检查权限本身，不检查通知是否被用户关闭
     * 建议使用 [canShowNotifications] 进行更全面的检查
     */
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val res: Int = context.checkCallingOrSelfPermission(permission)
            if (res != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }

    /**
     * 全面检查通知是否可用
     * 包括：
     * 1. Android 13+ 的 POST_NOTIFICATIONS 权限
     * 2. 系统通知总开关（areNotificationsEnabled）
     * 3. 检查消息通知渠道和渠道组的有效性
     *
     * @param checkChannel 是否检查消息通知渠道的有效性，默认为true（全面检查）
     * @return true表示通知可以正常显示，false表示通知被阻止
     */
    fun canShowNotifications(checkChannel: Boolean = true): Boolean {
        // 1. 检查POST_NOTIFICATIONS权限（Android 13+）
        if (!hasNotificationPermission()) {
            L.w { "[MessageNotificationUtil] canShowNotifications: no POST_NOTIFICATIONS permission" }
            return false
        }

        // 2. 检查系统通知总开关
        if (!isNotificationsEnabled()) {
            L.w { "[MessageNotificationUtil] canShowNotifications: notifications are disabled by system" }
            return false
        }

        // 3. 可选：检查消息通知渠道和渠道组是否有效
        if (checkChannel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 3.1 检查渠道组是否被阻止（API 28+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val channelGroup = nm.getNotificationChannelGroup(CHANNEL_CONFIG_MESSAGE_GROUP)
                if (channelGroup?.isBlocked == true) {
                    L.w { "[MessageNotificationUtil] canShowNotifications: MESSAGE channel group is blocked" }
                    return false
                }
            }

            // 3.2 检查单个渠道是否被阻止
            val messageChannel = nm.getNotificationChannel(CHANNEL_CONFIG_NAME_MESSAGE)
            if (messageChannel == null) {
                L.w { "[MessageNotificationUtil] canShowNotifications: MESSAGE channel does not exist" }
                return false
            }
            if (messageChannel.importance == NotificationManager.IMPORTANCE_NONE) {
                L.w { "[MessageNotificationUtil] canShowNotifications: MESSAGE channel is blocked (importance=NONE)" }
                return false
            }
        }

        return true
    }

    fun hasFullScreenNotificationPermission(): Boolean {
        return if (FullScreenPermissionHelper.isMainStreamChinaMobile()) {
            FullScreenPermissionHelper.canBackgroundStart(context)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//            userManager.getUserData()?.supportFullScreenNotification == false
                nm.canUseFullScreenIntent()
            } else {
                true
            }
        }
    }

    fun isNotificationsEnabled(): Boolean {
        return nm.areNotificationsEnabled()
    }

    //注册广播监听通知删除，用于删除通知缓存，避免显示旧通知
    class NotificationDismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val conversationId = intent?.getStringExtra("conversation_id")
            if (!conversationId.isNullOrEmpty() && context != null) {
                try {
                    // 通过Hilt EntryPoint获取NotificationCacheManager
                    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        NotificationDismissReceiverEntryPoint::class.java
                    )
                    val cacheManager = entryPoint.notificationCacheManager()

                    // 异步清理缓存，避免阻塞 BroadcastReceiver
                    appScope.launch(Dispatchers.IO) {
                        cacheManager.removeConversation(conversationId)
                        L.d { "[MessageNotificationUtil] NotificationDismissReceiver cleared cache for $conversationId" }
                    }
                } catch (e: Exception) {
                    L.e { "[MessageNotificationUtil] NotificationDismissReceiver failed: ${e.message}" }
                }
            }
        }
    }

    // Hilt EntryPoint for BroadcastReceiver
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface NotificationDismissReceiverEntryPoint {
        fun notificationCacheManager(): NotificationCacheManager
    }

    /**
     * 跳转到默认消息通知设置页面
     */
    fun openMessageNotificationChannelSettings(context: Activity, conversationId: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_CONFIG_NAME_MESSAGE)

                if (conversationId != null && supportConversationNotification()) {
                    putExtra(Settings.EXTRA_CONVERSATION_ID, conversationId)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            L.d { "[MessageNotificationUtil] Opening default message notification settings" }
        } else {
            openNotificationSettings(context)
        }
    }

    fun isNotificationPolicyAccessGranted(): Boolean {
        return try {
            nm.isNotificationPolicyAccessGranted()
        } catch (e: Exception) {
            L.i { "[MessageNotificationUtil] isNotificationPolicyAccessGranted failed:" + e.stackTraceToString() }
            false
        }
    }

    fun openNotificationDndSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            L.i { "[MessageNotificationUtil] openFullScreenNotificationSettings fail:" + e.stackTraceToString() }
            openNotificationSettings(activity)
        }
    }

    fun showCriticalAlertNotification(forWhat: For, alertTitle: String, alertContent: String) {
        L.i { "[MessageNotificationUtil] showCriticalAlertNotification for ${forWhat.id}"}
        val title = "🚨$alertTitle"
        val notificationId = forWhat.id.hashCode()

        val intent = createConversationIntent(forWhat)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_CONFIG_NAME_CRITICAL_ALERT)
            .setContentTitle(title)
            .setContentText(alertContent)
            .setContentIntent(pendingIntent)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setOnlyAlertOnce(false)
            .setDeleteIntent(createStopSoundIntent(context, notificationId))

        try {
            nm.notify(notificationId, builder.build())
        } catch (e: Exception) {
            L.e { "[MessageNotificationUtil] showCriticalAlertNotification failed:${e.message}" }
        }

        // 播放critical alert声音
        CriticalAlertSoundPlayer.play(context, notificationId)
    }

    fun createStopSoundIntent(context: Context, notificationID: Int): PendingIntent {
        val intent = Intent(context, StopCriticalAlertSoundReceiver::class.java).apply {
            action = STOP_CRITICAL_ALERT_SOUND
            `package` = context.packageName
            putExtra("notification_id", notificationID)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun cancelCriticalAlertNotification(conversationId: String?) {
        conversationId?.let {
            val notificationId = conversationId.hashCode()
            CriticalAlertSoundPlayer.stopIfMatch(notificationId)
            nm.cancel(notificationId)
        }
    }

}