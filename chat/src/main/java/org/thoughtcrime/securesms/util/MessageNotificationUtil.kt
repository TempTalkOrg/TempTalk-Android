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
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitFirst
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBRoomModel
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.base.widget.ToastUtil
@Singleton
class MessageNotificationUtil @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val userManager: UserManager,
    private val appIconBadgeManager: AppIconBadgeManager,
    private val activityProvider: ActivityProvider,
) : IMessageNotificationUtil {

    companion object {
        private const val NOTIFICATION_GROUP_KEY = "NOTIFICATION_GROUP_KEY"
        private const val MESSAGE_SUMMARY_NOTIFICATION_ID = 10000

        private const val CHANNEL_CONFIG_NAME_MESSAGE_SUMMARY = "MESSAGE_SUMMARY"
        private const val CHANNEL_CONFIG_NAME_MESSAGE = "MESSAGE"
        private const val CHANNEL_CONFIG_NAME_CALL = "CALL"
        private const val CHANNEL_CONFIG_NAME_ONGOING_CALL = "ONGOING_CALL"
        private const val CHANNEL_CONFIG_NAME_BACKGROUND = "BACKGROUND"

        private const val CHANNEL_CONFIG_MESSAGE_GROUP = "MESSAGE_GROUP"

        private val messageMap = mutableMapOf<String, MutableList<NotificationCompat.MessagingStyle.Message>>()
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
        if (SendMessageUtils.isExistChat(forWhat.id) || ConversationUtils.isConversationListVisible || LCallManager.isCallScreenSharing()) {
            L.i { "[MessageNotificationUtil] Show notification:${message.id} isExistChat:${SendMessageUtils.isExistChat(forWhat.id)} isConversationListVisible:${ConversationUtils.isConversationListVisible} isCallScreenSharing:${LCallManager.isCallScreenSharing()}" }
            return@runCatching
        }

        val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(forWhat.id))
        if (room != null) {
            if (room.muteStatus == MuteStatus.MUTED.value) {
                L.i { "[MessageNotificationUtil] ${forWhat.id} isMuted, No need to show notification" }
                return@runCatching
            }
            if (message.systemShowTimestamp <= room.readPosition) {
                L.i { "[MessageNotificationUtil] Message already read (timestamp: ${message.systemShowTimestamp} <= readPosition: ${room.readPosition}), skipping notification for ${forWhat.id}" }
                return@runCatching
            }
        }

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
                    L.i { "[MessageNotificationUtil] Global notification is OFF, not showing notification for ${forWhat.id}" }
                    return@runCatching
                }

                GlobalNotificationType.MENTION.value -> {
                    if (!isMentionMessage(message)) {
                        L.i { "[MessageNotificationUtil] Global notification is MENTION, not showing notification for ${forWhat.id}" }
                        return@runCatching
                    }
                }
            }
        }

        val intent = Intent(context, activityProvider.getActivityClass(ActivityType.MAIN))
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(LinkDataEntity.LINK_CATEGORY, LinkDataEntity.CATEGORY_MESSAGE)
        if (forWhat is For.Group) {
            intent.putExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID, forWhat.id)
        } else {
            intent.putExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID, forWhat.id)
        }

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

        val messageList = messageMap.getOrPut(forWhat.id) { mutableListOf() }
        if (isRecall) { //删除被recall的消息通知
            val index = messageList.indexOfFirst { it.timestamp == message.systemShowTimestamp }
            L.i { "[MessageNotificationUtil] delete notification for ${forWhat.id} timestamp:${message.systemShowTimestamp} index:${index} messageList:${messageList.size}" }
            if (index != -1) {
                messageList.removeAt(index)
            }
        } else {
            messageList.add(
                NotificationCompat.MessagingStyle.Message(
                    content,
                    message.systemShowTimestamp,
                    user
                )
            )
        }

        createSummaryNotification(context)

        val messagingStyle = NotificationCompat.MessagingStyle(user)
        if (forWhat is For.Group) {
            messagingStyle.isGroupConversation = true
            messagingStyle.conversationTitle = title
        }
        messageList.distinctBy { it.timestamp }.forEach {
            messagingStyle.addMessage(it)
        }

        val deleteIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("conversation_id", forWhat.id)
        }
        val pendingDeleteIntent = PendingIntent.getBroadcast(
            context,
            forWhat.id.hashCode(),
            deleteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = if (supportConversationNotification() && nm.getNotificationChannel(getConversationChannelId(forWhat.id)) != null) {
            getConversationChannelId(forWhat.id)
        } else {
            CHANNEL_CONFIG_NAME_MESSAGE
        }
        L.i { "[MessageNotificationUtil] forWhat:${forWhat.id} channelId:${channelId}" }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setNumber(unreadMessageNumber)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setWhen(message.systemShowTimestamp)
            .setStyle(messagingStyle)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setDeleteIntent(pendingDeleteIntent)
            .setOnlyAlertOnce(false)

        nm.notify(notificationID, builder.build())

        // Clear all empty message lists from messageMap
        messageMap.entries.removeAll { it.value.isEmpty() }

        if (messageMap.isEmpty()) {
            nm.cancel(MESSAGE_SUMMARY_NOTIFICATION_ID)
        }
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


    //无法解析出加密消息内容时显示默认的server推送内容
    fun showNotificationOfPush(
        context: Context,
        forWhat: For,
        title: String?,
        content: String?,
    ) = runCatching {
        if (SendMessageUtils.isExistChat(forWhat.id) || ConversationUtils.isConversationListVisible || LCallManager.isCallScreenSharing()) {
            return@runCatching
        }

        val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(forWhat.id))
        if (room != null) {
            if (room.muteStatus == MuteStatus.MUTED.value) {
                L.i { "[MessageNotificationUtil] ${forWhat.id} isMuted, No need to show notification" }
                return@runCatching
            }
        }

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
                    L.i { "[MessageNotificationUtil] Global notification is OFF, not showing notification for ${forWhat.id}" }
                    return@runCatching
                }
            }
        }

        val intent = Intent(context, activityProvider.getActivityClass(ActivityType.MAIN))
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(LinkDataEntity.LINK_CATEGORY, LinkDataEntity.CATEGORY_MESSAGE)
        if (forWhat is For.Group) {
            intent.putExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID, forWhat.id)
        } else {
            intent.putExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID, forWhat.id)
        }

        createSummaryNotification(context)

        val notificationID = forWhat.id.hashCode()

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            notificationID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = if (supportConversationNotification() && nm.getNotificationChannel(getConversationChannelId(forWhat.id)) != null) {
            getConversationChannelId(forWhat.id)
        } else {
            CHANNEL_CONFIG_NAME_MESSAGE
        }
        L.i { "[MessageNotificationUtil] forWhat:${forWhat.id} channelId:${channelId}" }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setNumber(getUnreadMessageNumber())
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setOnlyAlertOnce(false)

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
        messageMap.clear()
    }

    override fun cancelNotificationsByConversation(conversationId: String?) {
        L.i { "[MessageNotificationUtil] cancelNotificationsByConversation:${conversationId}" }
        conversationId?.let {
            nm.cancel(it.hashCode())
            messageMap.remove(it)
        }
        if (messageMap.isEmpty()) {
            nm.cancel(MESSAGE_SUMMARY_NOTIFICATION_ID)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    setData(("package:" + activity.packageName).toUri())
                }
                activity.startActivity(intent)
            } else {
                openNotificationSettings(activity)
            }
        } catch (e: Exception) {
            L.i { "[MessageNotificationUtil] openFullScreenNotificationSettings fail:" + e.stackTraceToString() }
            openNotificationSettings(activity)
        }
    }

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

    fun hasFullScreenNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//            userManager.getUserData()?.supportFullScreenNotification == false
            return nm.canUseFullScreenIntent()
        }

        return true
    }

    fun isNotificationsEnabled(): Boolean {
        return nm.areNotificationsEnabled()
    }

    //注册广播监听通知删除，用于删除通知缓存，避免显示旧通知
    class NotificationDismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val conversationId = intent?.getStringExtra("conversation_id")
            if (!conversationId.isNullOrEmpty()) {
                messageMap[conversationId]?.clear()
            }
        }
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
}