package com.difft.android.push

import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.chat.PendingMessageHelper
import com.difft.android.chat.data.NOTIFY_TYPE_CALL_HANGUP
import com.difft.android.chat.data.PushCustomContent
import com.difft.android.websocket.api.util.EnvelopDeserializer
import com.difft.android.base.utils.Base64
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.messages.EnvelopToMessageProcessor
import com.difft.android.chat.messages.EnvelopeProcessResult
import com.difft.android.chat.messages.reportPermanentDrop
import com.difft.android.chat.util.MessageNotificationUtil

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, EntryPoint::class.java)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        // Also if you intend on generating your own notifications as a result of a received FCM
        // message, here is where that should be initiated. See sendNotification method below.

        try {
            L.i { "[fcm] onMessageReceived notification:${remoteMessage.notification?.title} ${remoteMessage.notification?.body} data:${remoteMessage.data.keys.joinToString(",")}" }
            val customContent = remoteMessage.data["custom_content"]
            if (!TextUtils.isEmpty(customContent)) {
                val pushCustomContent = entryPoint.gson.fromJson(customContent, PushCustomContent::class.java)

                L.d { "[fcm] onMessageReceived pushCustomContent:${pushCustomContent}" }
                L.i { "[fcm] onMessageReceived locKey:${pushCustomContent?.locKey}" }

                when(pushCustomContent.critical) {
                    0 -> handleNormalMessage(entryPoint, pushCustomContent)
                    1 -> handleCriticalAlertMessage(entryPoint, pushCustomContent, remoteMessage.sentTime)
                    else -> {
                        L.w { "[fcm] Unknown critical value: ${pushCustomContent.critical}" }
                    }
                }
            } else {
                L.i { "[fcm] customContent is null, ignore" }
            }

            // 主动拉取和处理消息
            entryPoint.pendingMessageHelper.schedulePendingMessageWork()
        } catch (e: Exception) {
            L.i { "[fcm] onMessageReceived error - $e" }
        }
    }

    private fun handleCriticalAlertMessage(entryPoint: EntryPoint, pushCustomContent: PushCustomContent, sentTime: Long)  {

        val gid = pushCustomContent.gid
        val uid = pushCustomContent.uid

        val forWhat = when {
            gid != null -> For.Group(gid)
            uid != null -> For.Account(uid)
            else -> null
        } ?: return // 没有 gid 或 uid，直接退出

        val timestamp = pushCustomContent.timestamp

        appScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    L.i { "[CriticalAlert] handle fcm critical alert: id=${forWhat.id}, timestamp=$timestamp, sentTime=$sentTime" }
                    if (!entryPoint.messageNotificationUtil.isCriticalAlertTimestampValid(sentTime)) {
                        L.w { "[CriticalAlert] handle fcm critical alert: sentTime expired, skip. sentTime=$sentTime" }
                        return@withContext
                    }
                    val conversationId = forWhat.id
                    val source = pushCustomContent.uid
                    val roomId = pushCustomContent.roomId
                    if (source == null) {
                        L.w { "[Call] handle fcm critical alert: no source uid" }
                        return@withContext
                    }
                    if (source == globalServices.myId) {
                        L.w { "[CriticalAlert] handle fcm critical alert: source is myself, do not show notification" }
                        return@withContext
                    }

                    val (title, content) = LCallManager.getCriticalAlertNotificationContent(conversationId, source)
                    entryPoint.messageNotificationUtil
                        .showCriticalAlert(forWhat, title, content, timestamp, roomId)

                } catch (e: Exception) {
                    L.w { "[fcm] Processing critical message error: ${e.stackTraceToString()}" }
                }
            }
        }
    }

    private fun handleNormalMessage(entryPoint: EntryPoint, pushCustomContent: PushCustomContent) {
        pushCustomContent.msg?.let {
            appScope.launch(Dispatchers.IO) {
                try {
                    L.i { "[fcm] Start processing message" }

                    val serviceEnvelope = EnvelopDeserializer.deserializeFrom(Base64.decode(it))

                    val envelopToMessageProcessor = entryPoint.envelopToMessageProcessor

                    val processRes = serviceEnvelope?.let { envelopToMessageProcessor.process(it, "fcm") }
                    when (processRes) {
                        is EnvelopeProcessResult.Success -> {
                            val messageResult = processRes.result
                            if (messageResult != null) {
                                L.i { "[fcm] Processing message success:${messageResult.message.timeStamp} shouldShowNotification:${messageResult.shouldShowNotification}" }
                                if (messageResult.shouldShowNotification) {
                                    // Atomic increment (issue #725 §9.6): UserManager.update serializes
                                    // the read-modify-write under its writeMutex, so concurrent FCM
                                    // pushes can't race the way the legacy `getInt`+`putInt` pair did.
                                    entryPoint.userManager.update { unreadMsgNum += 1 }
                                    entryPoint.messageNotificationUtil.showNotificationSuspend(baseContext, messageResult.message, messageResult.conversation)
                                }
                            } else {
                                L.w { "[fcm] Processing message success result is null" }
                            }
                        }
                        is EnvelopeProcessResult.PermanentFailure -> {
                            reportPermanentDrop(
                                processRes.reason,
                                processRes.cause,
                                serviceEnvelope!!.timestamp,
                                tag = "fcm",
                            )
                        }
                        is EnvelopeProcessResult.TransientFailure -> {
                            // FCM is best-effort: don't enqueue here. The same
                            // envelope arrives via WebSocket / pending-message
                            // pull, where FailedMessageProcessor owns the retry.
                            L.w {
                                "[fcm] Transient failure (not enqueueing; WebSocket path owns retries) " +
                                    "ts=${serviceEnvelope!!.timestamp}: ${processRes.cause.stackTraceToString()}"
                            }
                        }
                        null -> {
                            L.w { "[fcm] Processing message result is null (envelope deserialization failed)" }
                        }
                    }
                } catch (e: Exception) {
                    L.w { "[fcm] Processing message envelope error: ${e.stackTraceToString()}" }
                    handleMessageProcessingError(entryPoint, pushCustomContent)
                }
            }
        } ?: run {
            L.w { "[fcm] Processing message pushCustomContent.msg is null" }
            appScope.launch(Dispatchers.IO) {
                handleMessageProcessingError(entryPoint, pushCustomContent)
            }
        }
    }

    private suspend fun handleMessageProcessingError(entryPoint: EntryPoint, pushCustomContent: PushCustomContent) {
        // 检查某些通知类型不显示通知
        if (pushCustomContent.notifyType == NOTIFY_TYPE_CALL_HANGUP) {
            L.i { "[fcm] notifyType: ${pushCustomContent.notifyType}, skip notification" }
            return
        }

        // 检查是否有有效的目标（群组ID或用户ID）
        if (pushCustomContent.gid.isNullOrEmpty() && pushCustomContent.uid.isNullOrEmpty()) {
            L.w { "[fcm] No valid target (gid or uid) found, cannot show notification" }
            return
        }

        try {
            // 根据目标类型创建For对象
            val forWhat = when {
                !pushCustomContent.gid.isNullOrEmpty() -> For.Group(pushCustomContent.gid!!)
                !pushCustomContent.uid.isNullOrEmpty() -> For.Account(pushCustomContent.uid!!)
                else -> null
            }

            forWhat?.let { target ->
                entryPoint.messageNotificationUtil.showNotificationOfPush(baseContext, target)
                L.i { "[fcm] Successfully showed fallback notification for target: $target" }
            } ?: L.w { "[fcm] Invalid target type, cannot show notification" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[fcm] Failed to show fallback notification: ${e.stackTraceToString()}" }
        }
    }

    /**
     * Called if the FCM registration token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is called when the
     * FCM registration token is initially generated so this is where you would retrieve the token.
     */
    override fun onNewToken(token: String) {
        L.i { "[fcm] Refreshed token" }
        // If you want to send messages to this application instance or
        // manage this apps subscriptions on the server side, send the
        // FCM registration token to your app server.
        if (!TextUtils.isEmpty((globalServices.userManager.getUserData()?.baseAuth ?: ""))) {
            sendRegistrationToServer(token)
        }
    }

    private fun sendRegistrationToServer(token: String) {
        appScope.launch {
            entryPoint.pushUtil.sendRegistrationToServer(token)
        }
    }

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface EntryPoint {
        val messageNotificationUtil: MessageNotificationUtil
        val envelopToMessageProcessor: EnvelopToMessageProcessor
        val pendingMessageHelper: PendingMessageHelper
        val pushUtil: PushUtil
        val userManager: UserManager
        val gson: Gson
    }
}