package com.difft.android.chat.util

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
import com.difft.android.base.call.VoiceRecordingTracker
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.IMessageNotificationUtil
import com.difft.android.base.utils.NotificationRepostGuard
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.difft.app.database.wcdb
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallManager
import com.difft.android.chat.R
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.focus.GroupNotifyGate
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.chat.message.getRecordMessageContentTwo
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.ConversationUtils
import difft.android.messageserialization.model.Message
import com.difft.android.base.migration.pause.MessagingGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.DBRoomModel
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.manager.CriticalAlertManager
import com.difft.android.call.receiver.InComingCallNotificationReceiver
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.util.FlashLightBlinker
import com.difft.android.chat.messages.MessageForegroundService
import util.AppForegroundObserver


@Singleton
class MessageNotificationUtil @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val userManager: UserManager,
    private val appIconBadgeManager: AppIconBadgeManager,
    private val activityProvider: ActivityProvider,
    private val cacheManager: NotificationCacheManager,
    private val criticalAlertManager: CriticalAlertManager,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val groupUtil: dagger.Lazy<GroupUtil>,
    private val criticalAlertNotificationBuilder: CriticalAlertNotificationBuilder,
    private val channelManager: NotificationChannelManager,
    private val repostGuard: NotificationRepostGuard,
    private val criticalAlertCallGate: CriticalAlertCallGate,
    private val groupNotifyGate: GroupNotifyGate,
    private val messagingGate: MessagingGate,
) : IMessageNotificationUtil {

    companion object {
        private const val NOTIFICATION_GROUP_KEY = "NOTIFICATION_GROUP_KEY"
        private const val MESSAGE_SUMMARY_NOTIFICATION_ID = 10000

        private const val CHANNEL_CONFIG_NAME_MESSAGE = NotificationChannelIds.MESSAGE
        private const val CHANNEL_CONFIG_NAME_CALL = NotificationChannelIds.CALL
        private const val CHANNEL_CONFIG_NAME_CRITICAL_ALERT = NotificationChannelIds.CRITICAL_ALERT
        private const val CHANNEL_CONFIG_MESSAGE_GROUP = NotificationChannelIds.MESSAGE_GROUP
        const val STOP_CRITICAL_ALERT_SOUND = "STOP_CRITICAL_ALERT_SOUND"
        private const val CRITICAL_ALERT_VALID_WINDOW_MS = 8 * 60 * 60 * 1000L
    }

    private val nm: NotificationManager by lazy {
        ServiceUtil.getNotificationManager(context)
    }

    private val pendingCallNotifyJobs = ConcurrentHashMap<Int, Job>()


    
    fun refreshChannelNames() = channelManager.ensureChannels()

    



    fun checkAndCreateNotificationChannels() {
        channelManager.ensureChannels()
        appScope.launch(Dispatchers.IO) {
            try {
                channelManager.migrateIfNeeded()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[MessageNotificationUtil] channel migration failed: ${e.stackTraceToString()}" }
            }
        }

        try {
            appScope.launch(Dispatchers.IO) {
                cacheManager.cleanupOldCache()
                criticalAlertManager.cleanupOldCriticalAlertCache()
            }
        } catch (e: Exception) {
            L.e(e) { "[MessageNotificationUtil] Failed to start cleanup task:" }
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    fun supportConversationNotification(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    fun getConversationChannelId(conversationId: String): String {
        return CHANNEL_CONFIG_NAME_MESSAGE + conversationId
    }

    fun isCriticalAlertTimestampValid(timestamp: Long): Boolean {
        val now = ServerTimeProvider.nowMillis()
        return kotlin.math.abs(now - timestamp) <= CRITICAL_ALERT_VALID_WINDOW_MS
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

    suspend fun showNotificationSuspend(
        context: Context,
        message: Message,
        forWhat: For,
        isRecall: Boolean = false,
        delivery: NotificationDelivery = NotificationDelivery.WEB_SOCKET,
    ) = runCatching {
        
        if (isRecall) {
            val removedCount = cacheManager.removeMessageByTimestamp(forWhat.id, message.systemShowTimestamp)
            L.i { "[MessageNotificationUtil] Recall message for ${forWhat.id} timestamp:${message.systemShowTimestamp} removed:$removedCount" }

            val messageList = cacheManager.getMessages(forWhat.id)
            if (messageList.isEmpty()) {
                val notificationId = forWhat.id.hashCode()
                nm.cancel(notificationId)
                L.i { "[MessageNotificationUtil] All messages recalled for ${forWhat.id}, notification cancelled" }

                
                if (!hasAnyMessageNotifications(excludeNotificationId = notificationId)) {
                    nm.cancel(MESSAGE_SUMMARY_NOTIFICATION_ID)
                    L.i { "[MessageNotificationUtil] No message notifications left, summary notification cancelled" }
                }
                return@runCatching
            }

            L.i { "[MessageNotificationUtil] Recall: has ${messageList.size} remaining messages, rebuilding notification (skip interceptions)" }
        } else {
            if (messagingPaused("entry")) return@runCatching
            if (shouldInterceptNotification(forWhat, message, delivery)) {
                return@runCatching
            }
        }

        val fromId = message.fromWho.id
        var sender: ContactorModel? = null

        val displayType = userManager.getUserData()?.notificationContentDisplayType ?: 0

        val needTitle = displayType != NotificationContentDisplayType.NO_NAME_OR_CONTENT.value
        val needContent = displayType == NotificationContentDisplayType.NAME_AND_CONTENT.value

        var title: String

        if (needTitle) {
            val contactor = ContactorUtil.getContactWithID(context, fromId)
            if (contactor.isPresent) {
                sender = contactor.get()
            }
            if (forWhat is For.Group) {
                val group = groupUtil.get().getSingleGroupInfo(forWhat.id, false)
                title = group?.name?.toString() ?: forWhat.id
            } else {
                title = sender?.getDisplayNameForUI() ?: fromId.formatBase58Id()
            }
        } else {
            title = PackageUtil.getAppName() ?: ""
        }
        
        if (messagingPaused("after_lookup")) return@runCatching

        val content: String = if (needContent) {
            getRecordMessageContentTwo(message, false, "")
        } else {
            ResUtils.getString(R.string.notification_received_message)
        }

        val unreadMessageNumber = getUnreadMessageNumber()
        appIconBadgeManager.updateAppIconBadgeNum(unreadMessageNumber)

        val notificationID = forWhat.id.hashCode()

        val pendingIntent = createConversationPendingIntent(forWhat, notificationID)

        val personName = if (displayType == NotificationContentDisplayType.NO_NAME_OR_CONTENT.value) {
            PackageUtil.getAppName() ?: ""
        } else {
            sender?.getDisplayNameForUI() ?: fromId.formatBase58Id()
        }

        val user = Person.Builder().setName(personName).setKey(fromId).build()

        
        
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

        val messageList = cacheManager.getMessages(forWhat.id)

        createSummaryNotification(context)

        val messagingStyle = NotificationCompat.MessagingStyle(user)
        if (forWhat is For.Group) {
            messagingStyle.isGroupConversation = true
            messagingStyle.conversationTitle = title
        }

        
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
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (isRecall) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH) 
            .setAutoCancel(true)
            .setNumber(unreadMessageNumber)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setWhen(message.systemShowTimestamp)
            .setStyle(messagingStyle)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setDeleteIntent(pendingDeleteIntent)
            .setOnlyAlertOnce(isRecall) 
            .setSilent(isRecall) 

        if (messagingPaused("post")) return@runCatching
        nm.notify(notificationID, builder.build())
    }.onFailure {
        L.e { "showNotification failed: ${it.stackTraceToString()}" }
    }

    





    private fun messagingPaused(stage: String): Boolean {
        if (messagingGate.isOpen) return false
        L.i { "[MigrationPause] event=notification_suppressed stage=$stage" }
        return true
    }

    private fun createSummaryNotification(context: Context) {
        if (isNotificationShowing(MESSAGE_SUMMARY_NOTIFICATION_ID)) {
            L.d { "Summary notification already exists, no need to create again." }
            return
        }
        if (!channelManager.ensureMessageChannel()) {
            L.w { "[MessageNotificationUtil] summary skipped: channel missing id=$CHANNEL_CONFIG_NAME_MESSAGE" }
            return
        }
        if (messagingPaused("summary")) return
        nm.notify(MESSAGE_SUMMARY_NOTIFICATION_ID, buildMessageSummaryNotification(context, NOTIFICATION_GROUP_KEY))
    }

    private fun getUnreadMessageNumber(): Int {
        return userManager.getUserData()?.unreadMsgNum ?: AppStateDefaults.SP_UNREAD_MSG_NUM
    }

    








    private suspend fun shouldInterceptNotification(
        forWhat: For,
        message: Message? = null,
        delivery: NotificationDelivery = NotificationDelivery.WEB_SOCKET,
    ): Boolean {
        if (SendMessageUtils.isExistChat(forWhat.id) ||
            ConversationUtils.isConversationListVisible ||
            onGoingCallStateManager.isInScreenSharing()
        ) {
            L.i { "[MessageNotificationUtil] Intercepted: isExistChat:${SendMessageUtils.isExistChat(forWhat.id)} isConversationListVisible:${ConversationUtils.isConversationListVisible} isCallScreenSharing:${onGoingCallStateManager.isInScreenSharing()}" }
            return true
        }

        val room = withContext(Dispatchers.IO) { wcdb.room.getFirstObject(DBRoomModel.roomId.eq(forWhat.id)) }

        
        if (delivery == NotificationDelivery.WEB_SOCKET) {
            val decision = groupNotifyGate.decideMessage(forWhat, message, room)
            if (!decision.notify) {
                L.i { "[MessageNotificationUtil] Intercepted by policy room=${forWhat.id} reason=${decision.reason}" }
                return true
            }
        }

        if (room != null && message != null && message.systemShowTimestamp <= room.readPosition) {
            L.i { "[MessageNotificationUtil] Message already read (timestamp: ${message.systemShowTimestamp} <= readPosition: ${room.readPosition})" }
            return true
        }

        return false
    }

    





    private fun createConversationPendingIntent(forWhat: For, notificationId: Int): PendingIntent {
        val isGroup = forWhat is For.Group
        val conversationId = forWhat.id

        val intent = NotificationTrampolineActivity.createIntent(
            context,
            contactId = if (isGroup) null else conversationId,
            groupId = if (isGroup) conversationId else null
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            
            data = "app://notification/$notificationId/${System.currentTimeMillis()}".toUri()
        }

        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    


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

    private fun selectNotificationChannel(conversationId: String): String {
        return if (supportConversationNotification() &&
            nm.getNotificationChannel(getConversationChannelId(conversationId)) != null
        ) {
            getConversationChannelId(conversationId)
        } else {
            CHANNEL_CONFIG_NAME_MESSAGE
        }
    }

    suspend fun showNotificationOfPush(
        context: Context,
        forWhat: For
    ) = runCatching {
        
        if (messagingPaused("push_entry")) return@runCatching
        val notificationID = forWhat.id.hashCode()
        if (isNotificationShowing(notificationID)) {
            L.i { "[MessageNotificationUtil] Normal notification is showing for ${forWhat.id}, skipping push fallback" }
            return@runCatching
        }

        
        if (shouldInterceptNotification(forWhat, null, NotificationDelivery.FCM)) {
            return@runCatching
        }

        createSummaryNotification(context)

        val pendingIntent = createConversationPendingIntent(forWhat, notificationID)

        val pendingDeleteIntent = createDeletePendingIntent(forWhat.id)

        val channelId = selectNotificationChannel(forWhat.id)
        L.i { "[MessageNotificationUtil] forWhat:${forWhat.id} channelId:${channelId}" }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(ResUtils.getString(R.string.notification_received_message))
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setNumber(getUnreadMessageNumber())
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setWhen(System.currentTimeMillis())
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setDeleteIntent(pendingDeleteIntent)
            .setOnlyAlertOnce(true) 

        if (messagingPaused("push_post")) return@runCatching
        nm.notify(notificationID, builder.build())
    }.onFailure {
        L.e { "showNotificationOfPush failed: ${it.stackTraceToString()}" }
    }

    










    private suspend fun loadCallDisplayInfo(
        roomId: String,
        callerId: String,
        conversationId: String?,
        isGroup: Boolean,
    ): Pair<Optional<ContactorModel>, GroupModel?> {
        val callerInfo = try {
            ContactorUtil.getContactWithID(context, callerId)
        } catch (e: Exception) {
            L.w { "[Call] caller lookup failed, using fallback display roomId=$roomId: ${e.message}" }
            Optional.empty()
        }
        val groupInfo = if (isGroup && conversationId != null) {
            try {
                groupUtil.get().getSingleGroupInfo(conversationId)
            } catch (e: Exception) {
                L.w { "[Call] group lookup failed, using fallback display roomId=$roomId: ${e.message}" }
                null
            }
        } else null
        return callerInfo to groupInfo
    }

    fun showCallNotificationNew(roomId: String, callName: String, callerId: String, conversationId: String?, callType: CallType, needAppLock: Boolean) {
        val notificationID = roomId.hashCode()
        if (callType.isGroup() && conversationId == null) return

        
        
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            val (callerInfo, groupInfo) = loadCallDisplayInfo(roomId, callerId, conversationId, callType.isGroup())
            
            if (messagingPaused("call")) return@launch

            var title = ""
            var content = ""
            if (callType.isGroup()) {
                title = groupInfo?.name ?: conversationId.orEmpty()
                content = "${if (callerInfo.isPresent) callerInfo.get().getDisplayNameForUI() else callerId.formatBase58Id()} ${ResUtils.getString(R.string.call_invite_of_group)}"
            } else if (callType.isOneOnOne()) {
                title = if (callerInfo.isPresent) callerInfo.get().getDisplayNameForUI() else callerId.formatBase58Id()
                content = ResUtils.getString(R.string.call_invite)
            } else if (callType.isInstant()) {
                title = if (callerInfo.isPresent) callerInfo.get().getDisplayNameForUI() else callerId.formatBase58Id()
                content = ResUtils.getString(R.string.call_invite)
            }

            L.i { "[Call] showCallNotificationNew createCallIntent roomId=$roomId callType=$callType callerId=$callerId conversationId=$conversationId" }
            val intent = createCallIntent(context, callType, callerId, roomId, conversationId, null, callName, needAppLock)
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
                .withNeedAppLock(needAppLock)
                .build()
            val acceptPendingIntent = createPendingIntent(context, notificationID, acceptIntent)

            val rejectIntent = Intent(context, InComingCallNotificationReceiver::class.java).apply {
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

            
            L.i { "[Call] showCallNotificationNew notificationID:$notificationID roomId:$roomId style=inbox+2actions" }
            if (!isActive) return@launch
            try {
                repostGuard.onPosted(notificationID)
                nm.notify(notificationID, builder.build())
            } catch (e: Exception) {
                L.e { "[Call] showCallNotificationNew failed: ${e.stackTraceToString()}" }
            }
        }
        pendingCallNotifyJobs.put(notificationID, job)?.cancel()
        job.invokeOnCompletion { throwable ->
            val stillOwner = pendingCallNotifyJobs.remove(notificationID, job)
            if (throwable != null && stillOwner) repostGuard.cancel(notificationID)
        }
        job.start()
    }

    private fun createCallIntent(context: Context, callType: CallType, callerId: String, roomId: String, conversationId: String?, action: String? = null, roomName: String, isNeedAppLock: Boolean): Intent {
        return CallIntent.Builder(context, activityProvider.getActivityClass(ActivityType.L_INCOMING_CALL))
            .withAction(CallIntent.Action.INCOMING_CALL)
            .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .withCallType(callType.type)
            .withCallerId(callerId)
            .withRoomId(roomId)
            .withRoomName(roomName)
            .withConversationId(conversationId)
            .withCallRole(CallRole.CALLEE.type)
            .withNeedAppLock(isNeedAppLock)
            
            .withFullScreenLaunch(true)
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

    override fun cancelAllNotifications() {
        L.i { "[MessageNotificationUtil] cancelAllNotifications" }
        repostGuard.cancelAll()

        
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

            
            appScope.launch(Dispatchers.IO) {
                cacheManager.removeConversation(it)

                
                if (!hasAnyMessageNotifications(excludeNotificationId = notificationId)) {
                    nm.cancel(MESSAGE_SUMMARY_NOTIFICATION_ID)
                    L.i { "[MessageNotificationUtil] No message notifications left, summary notification cancelled" }
                }
            }
        }
    }

    fun cancelNotificationsById(id: Int) {
        val pendingCancelled = pendingCallNotifyJobs.remove(id)?.also { it.cancel() } != null
        L.i { "[MessageNotificationUtil] cancelNotificationsById id=$id pendingCancelled=$pendingCancelled" }
        repostGuard.cancel(id)
    }

    fun hasPendingCallNotification(id: Int): Boolean = pendingCallNotifyJobs.containsKey(id)

    fun isNotificationShowing(notificationId: Int): Boolean {
        val activeNotifications = nm.activeNotifications
        for (notification in activeNotifications) {
            if (notification.id == notificationId) {
                return true
            }
        }
        return false
    }

    




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

    





    fun canShowNotifications(checkChannel: Boolean = true): Boolean {
        if (!hasNotificationPermission()) {
            L.w { "[MessageNotificationUtil] canShowNotifications: no POST_NOTIFICATIONS permission" }
            return false
        }

        if (!isNotificationsEnabled()) {
            L.w { "[MessageNotificationUtil] canShowNotifications: notifications are disabled by system" }
            return false
        }

        if (checkChannel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val channelGroup = nm.getNotificationChannelGroup(CHANNEL_CONFIG_MESSAGE_GROUP)
                if (channelGroup?.isBlocked == true) {
                    L.w { "[MessageNotificationUtil] canShowNotifications: MESSAGE channel group is blocked" }
                    return false
                }
            }

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

    fun isNotificationsEnabled(): Boolean {
        return nm.areNotificationsEnabled()
    }

    class NotificationDismissReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val conversationId = intent?.getStringExtra("conversation_id")
            if (!conversationId.isNullOrEmpty() && context != null) {
                try {
                    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        NotificationDismissReceiverEntryPoint::class.java
                    )
                    val cacheManager = entryPoint.notificationCacheManager()

                    
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

    
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface NotificationDismissReceiverEntryPoint {
        fun notificationCacheManager(): NotificationCacheManager
    }

    
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface CriticalAlertReceiverEntryPoint {
        fun messageNotificationUtil(): MessageNotificationUtil
        fun activityProvider(): ActivityProvider
        fun criticalAlertManager(): CriticalAlertManager
        fun notificationRepostGuard(): NotificationRepostGuard
    }

    fun openMessageNotificationChannelSettings(context: Activity, conversationId: String? = null) {
        val conversation = conversationId?.takeIf { supportConversationNotification() }
        if (!NotificationFixJump.openMessageCategory(context, conversation)) {
            L.w { "[MessageNotificationUtil] open message category settings failed conversation=${conversation != null}" }
            ToastUtil.show(R.string.notification_settings_open_fail)
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

    @Synchronized
    fun showCriticalAlert(forWhat: For, alertTitle: String, alertContent: String, timestamp: Long, roomId: String?) {
        val notificationId = timestamp.hashCode()
        L.i { "[CriticalAlert] showCriticalAlert for ${forWhat.id}, timestamp=$timestamp, notificationId=$notificationId, roomId=$roomId "}

        if (criticalAlertManager.isCriticalAlertNotificationProcessed(forWhat.id, notificationId)) {
            L.w { "[CriticalAlert] Critical alert notification (id=$notificationId) already processed in cache, skip.}"}
            
            repostGuard.repostIfActive(notificationId)
            return
        }

        if(isNotificationShowing(notificationId)) {
            L.w { "[CriticalAlert] Critical alert notification (id=$notificationId) is already showing, skip.}"}
            
            criticalAlertManager.addCriticalAlertNotification(forWhat.id, notificationId)
            repostGuard.repostIfActive(notificationId)
            return
        }

        
        L.i { "[CriticalAlert] Adding critical alert notification to cache: conversationId=${forWhat.id}, notificationId=$notificationId"}
        val added = criticalAlertManager.addCriticalAlertNotificationIfNotExists(forWhat.id, notificationId)
        if (!added) {
            L.w { "[CriticalAlert] Failed to add notification to cache (may be concurrent call), skip showing"}
            return
        }

        
        
        if (criticalAlertCallGate.shouldSilence(forWhat.id, roomId)) {
            L.i { "[CriticalAlert] skip reason=silenced notificationId=$notificationId roomId=$roomId" }
            return
        }

        
        if (!roomId.isNullOrEmpty()) criticalAlertManager.recordRoomAlert(roomId, notificationId)

        if (!roomId.isNullOrEmpty()) {
            LCallManager.dismissIncomingNotificationByRoomId(roomId)
        } else {
            forWhat.id.let { conversationId ->
                LCallManager.dismissIncomingNotification(conversationId)
            }
        }

        val payload = CriticalAlertPayload(forWhat.id, alertTitle, alertContent, notificationId, timestamp, roomId)
        if (AppForegroundObserver.isForegrounded() && !VoiceRecordingTracker.isRecording) {
            L.i { "[CriticalAlert] App is in foreground, starting CriticalAlertActivity"}
            postCriticalAlertNotification(payload, CriticalAlertPresentation.SILENT_COMPANION)
            criticalAlertManager.startCriticalAlertActivity(forWhat.id, alertTitle, alertContent, notificationId, roomId)
        } else {
            
            
            
            
            if (VoiceRecordingTracker.isRecording) {
                L.i { "[CriticalAlert] suppress Activity launch due to voice recording, fall back to notification notificationId=$notificationId" }
            }
            L.i { "[CriticalAlert] App is in background, show critical alert notification" }
            if (postCriticalAlertNotification(payload, CriticalAlertPresentation.FULL_SCREEN)) {
                criticalAlertManager.playSoundAndFlashLight(forWhat.id, notificationId, roomId)
            }
        }

        
        
        if (criticalAlertCallGate.shouldSilence(forWhat.id, roomId)) {
            L.i { "[CriticalAlert] undo reason=silenced_during_publish notificationId=$notificationId roomId=$roomId" }
            repostGuard.cancel(notificationId)
            criticalAlertManager.stopSoundIfMatch(notificationId)
        }
    }

    private fun postCriticalAlertNotification(payload: CriticalAlertPayload, presentation: CriticalAlertPresentation): Boolean =
        try {
            val notification = criticalAlertNotificationBuilder.build(payload, CHANNEL_CONFIG_NAME_CRITICAL_ALERT, presentation)
            L.i { "[CriticalAlert] Notifying critical alert: notificationId=${payload.notificationId} timestamp=${payload.timestamp} presentation=$presentation" }
            repostGuard.onPosted(payload.notificationId)
            nm.notify(payload.notificationId, notification)
            true
        } catch (e: Exception) {
            L.e(e) { "[CriticalAlert] showCriticalAlert notify failed: notificationId=${payload.notificationId} presentation=$presentation" }
            false
        }

    fun cancelCriticalAlertNotification(conversationId: String? = null) {
        val shouldCancelAll = conversationId == null

        
        if (shouldCancelAll) {
            criticalAlertManager.stopSound()
        }

        
        appScope.launch(Dispatchers.IO) {
            
            
            if (FlashLightBlinker.isBlinking()) {
                FlashLightBlinker.stopBlinking()
            }

            val canceledIds = mutableListOf<Int>()

            try {
                
                if (!shouldCancelAll) {
                    val hashKey = criticalAlertManager.hashConversationId(conversationId!!)
                    val infos = criticalAlertManager.getCriticalAlertInfos()
                    val info = infos[hashKey]

                    if (info != null) {
                        info.notificationIds.forEach { notificationId ->
                            try {
                                
                                repostGuard.cancel(notificationId)
                                L.d { "[MessageNotificationUtil] cancel notificationId=$notificationId for conversationId=$conversationId" }
                                criticalAlertManager.stopSoundIfMatch(notificationId)
                                canceledIds.add(notificationId)
                            } catch (e: Exception) {
                                L.e { "[MessageNotificationUtil] cancelCriticalAlertNotification failed:${e.message}" }
                            }
                        }
                    }
                } else {
                    val infos = criticalAlertManager.getCriticalAlertInfos()
                    infos.forEach { (hashKey, info) ->
                        info.notificationIds.forEach { notificationId ->
                            try {
                                
                                repostGuard.cancel(notificationId)
                                L.d { "[MessageNotificationUtil] cancel notificationId=$notificationId" }
                                criticalAlertManager.stopSoundIfMatch(notificationId)
                                canceledIds.add(notificationId)
                            } catch (e: Exception) {
                                L.e { "[MessageNotificationUtil] cancelCriticalAlertNotification failed:${e.message}" }
                            }
                        }
                    }
                }

                L.i { "[MessageNotificationUtil] cancelCriticalAlertNotification finished. conversationId=$conversationId, canceledIds=${canceledIds.size}" }
            } catch (e: Exception) {
                L.e { "[MessageNotificationUtil] cancelCriticalAlertNotification error:${e.message}" }
            }
        }
    }




}