package com.difft.android.push

import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.chat.PendingMessageHelper
import com.difft.android.chat.R
import com.difft.android.chat.data.NOTIFY_TYPE_CALL_HANGUP
import com.difft.android.chat.data.PushCustomContent
import com.difft.android.websocket.api.util.EnvelopDeserializer
import com.difft.android.websocket.util.Base64
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.messages.EnvelopToMessageProcessor
import org.thoughtcrime.securesms.util.MessageNotificationUtil

@AndroidEntryPoint
class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        // Also if you intend on generating your own notifications as a result of a received FCM
        // message, here is where that should be initiated. See sendNotification method below.

        try {
//            L.d { "[fcm] onMessageReceived:${Gson().toJson(remoteMessage)}" }
            L.i { "[fcm] onMessageReceived notification:${remoteMessage.notification?.title} ${remoteMessage.notification?.body} data:${remoteMessage.data.keys.joinToString(",")}" }

            val title = remoteMessage.data["title"]
            val content = remoteMessage.data["body"]
            val customContent = remoteMessage.data["custom_content"]
            val entryPoint = EntryPointAccessors.fromApplication(baseContext, EntryPoint::class.java)
            if (!TextUtils.isEmpty(customContent)) {
                val pushCustomContent = Gson().fromJson(customContent, PushCustomContent::class.java)

                L.d { "[fcm] onMessageReceived pushCustomContent:${pushCustomContent}" }
                L.i { "[fcm] onMessageReceived locKey:${pushCustomContent?.locKey}" }

                when(pushCustomContent.critical) {
                    0 -> handleNormalMessage(entryPoint, pushCustomContent)
                    1 -> handleCriticalAlertMessage(entryPoint, pushCustomContent, title, content)
                    else -> {
                        L.w { "[fcm] Unknown critical value: ${pushCustomContent.critical}" }
                    }
                }
            } else {
                L.i { "[fcm] customContent is null, title:${title} content:${content}, ignore" }
            }

            // 主动拉取和处理消息
            entryPoint.pendingMessageHelper.schedulePendingMessageWork()
        } catch (e: Exception) {
            L.i { "[fcm] onMessageReceived error - $e , ${remoteMessage.data}" }
            e.printStackTrace()
        }
    }

    private fun handleCriticalAlertMessage(entryPoint: EntryPoint, pushCustomContent: PushCustomContent, title: String?, content: String?)  {
        if(entryPoint.messageNotificationUtil.isNotificationPolicyAccessGranted()) {
            pushCustomContent.uid?.let { uid ->
                val alertTitle = title ?: ResUtils.getString(R.string.notification_critical_alert_title_default)
                val alertContent = content ?: ResUtils.getString(R.string.notification_critical_alert_content_default)
                appScope.launch(Dispatchers.IO) {
                    entryPoint.messageNotificationUtil.showCriticalAlertNotification(For.Account(uid), alertTitle, alertContent)
                }
            }
        } else {
            L.i { "[fcm] Critical alert message is not shown because notification policy access is denied" }
        }
    }

    private fun handleNormalMessage(entryPoint: EntryPoint, pushCustomContent: PushCustomContent) {
        pushCustomContent.msg?.let {
            appScope.launch(Dispatchers.IO) {
                try {
                    L.i { "[fcm] Start processing message" }

                    val serviceEnvelope = EnvelopDeserializer.deserializeFrom(Base64.decode(it))

                    val envelopToMessageProcessor = entryPoint.envelopToMessageProcessor

                    val messageResult = serviceEnvelope?.let { envelopToMessageProcessor.process(it, "fcm") }
                    if (messageResult != null) {
                        L.i { "[fcm] Processing message success:${messageResult.message.timeStamp} shouldShowNotification:${messageResult.shouldShowNotification}" }
                        if (messageResult.shouldShowNotification) {
                            SharedPrefsUtil.getInt(SharedPrefsUtil.SP_UNREAD_MSG_NUM).let {
                                SharedPrefsUtil.putInt(SharedPrefsUtil.SP_UNREAD_MSG_NUM, it + 1)
                            }
                            entryPoint.messageNotificationUtil.showNotificationSuspend(baseContext, messageResult.message, messageResult.conversation)
                        }
                    } else {
                        L.w { "[fcm] Processing message result is null" }
                    }
                } catch (e: Exception) {
                    L.w { "[fcm] Processing message envelope error: ${e.stackTraceToString()}" }
                    handleMessageProcessingError(entryPoint, pushCustomContent)
                }
            }
        } ?: run {
            L.w { "[fcm] Processing message pushCustomContent.msg is null" }
            handleMessageProcessingError(entryPoint, pushCustomContent)
        }
    }

    private fun handleMessageProcessingError(entryPoint: EntryPoint, pushCustomContent: PushCustomContent) {
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
        if (!TextUtils.isEmpty(SecureSharedPrefsUtil.getBasicAuth())) {
            sendRegistrationToServer(token)
        }
    }

    private fun sendRegistrationToServer(token: String) {
        PushUtil.sendRegistrationToServer(null, token)
    }

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface EntryPoint {
        val messageNotificationUtil: MessageNotificationUtil
        val envelopToMessageProcessor: EnvelopToMessageProcessor
        val pendingMessageHelper: PendingMessageHelper
    }
}