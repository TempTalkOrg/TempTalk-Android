package org.thoughtcrime.securesms.jobs

import android.content.Context
import android.os.Bundle
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.MD5Utils
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.data.ContactorUtil
import difft.android.messageserialization.For
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAttachmentMessage
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.util.DataMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.NotificationType
import com.difft.android.websocket.internal.push.OutgoingPushMessage
import com.difft.android.websocket.internal.push.OutgoingPushMessage.PassThrough
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import java.util.concurrent.Executors
import kotlin.properties.Delegates

class TextMessageSender @AssistedInject constructor(
    @Assisted
    private val textMessage: TextMessage,
    @Assisted
    private var notification: OutgoingPushMessage.Notification? = null,
    @ApplicationContext
    private val context: Context,
    private val gson: Gson,
    private val newSignalServiceMessageSender: NewSignalServiceMessageSender,
    private val dataMessageCreator: DataMessageCreator,
) {

    private val jobCreatedTime = System.currentTimeMillis()
    private var startExecuteTime by Delegates.notNull<Long>()

    fun send() {
        updateSendStatus(SendType.Sending.rawValue)
        coroutineScope.launch {
            run()
        }
    }

    private fun run() {
        startExecuteTime = System.currentTimeMillis()
        try {
            val dataMessage = dataMessageCreator.createFrom(textMessage)

            if (notification == null) {
                notification = createNotification()
            }

            L.i { "[Message] send text message to-> ${textMessage.forWhat.id}" }
            val result = newSignalServiceMessageSender.sendDataMessage(
                textMessage.forWhat,
                textMessage.forWhat,
                dataMessage,
                notification?.toNewNotification(),
            )

            result.success?.let {
                if (textMessage.recall == null) {
                    textMessage.systemShowTimestamp = it.systemShowTimestamp
                    textMessage.notifySequenceId = it.notifySequenceId
                    textMessage.sequenceId = it.sequenceId
                }
                updateSendStatus(SendType.Sent.rawValue)
            } ?: {
                updateSendStatus(SendType.SentFailed.rawValue)
            }
        } catch (e: Exception) {
            L.e { "[Message] send message exception -> ${e.stackTraceToString()}" }
            updateSendStatus(SendType.SentFailed.rawValue)
            if (e is NonSuccessfulResponseCodeException) {
                if (e.code == 430) {
//                    ContactorUtil.createBlockMessage(textMessage.forWhat)
                } else if (e.code == 432) {//非好友限制为每天最多发送三条消息
                    ContactorUtil.createNonFriendLimitMessage(textMessage.forWhat)
                } else if (e.code == 404) {
                    if (e is AccountOfflineException) {
                        if (e.status == 10105) {//对方离线
                            ContactorUtil.createOfflineMessage(
                                textMessage.forWhat,
                                TTNotifyMessage.NOTIFY_ACTION_TYPE_OFFLINE
                            )
                        } else if (e.status == 10110) {//对方账号注销
                            ContactorUtil.createOfflineMessage(
                                textMessage.forWhat,
                                TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED
                            )
                        }
                    } else {//账号不可用
                        ContactorUtil.createOfflineMessage(
                            textMessage.forWhat,
                            TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED
                        )
                    }
                }
            }
        }
        reportSendCostTime()
    }

    private fun createNotification(): OutgoingPushMessage.Notification {
        val collapseId = MD5Utils.md5AndHexStr(textMessage.timeStamp.toString() + textMessage.fromWho.id + DEFAULT_DEVICE_ID)
        val conversationId = if (textMessage.forWhat is For.Group) {
            Base64.encodeToString(textMessage.forWhat.id.toByteArray(), Base64.NO_WRAP)
        } else {
            textMessage.fromWho.id
        }
        val passThrough = PassThrough(conversationId)
        var mentionedPersons: Array<String>? = null
        val type: Int = if (textMessage.forWhat is For.Group) {
            if (textMessage.recall != null) {
                NotificationType.RECALL_MSG.code
            } else if (!textMessage.mentions.isNullOrEmpty()) {
                if (textMessage.mentions?.firstOrNull()?.uid == MENTIONS_ALL_ID) {
                    NotificationType.GROUP_MENTIONS_ALL.code
                } else {
                    mentionedPersons = textMessage.mentions?.mapNotNull { it.uid }?.toTypedArray()
                    NotificationType.GROUP_MENTIONS_DESTINATION.code
                }
            } else {
                if (textMessage.isAttachmentMessage()) {
                    NotificationType.GROUP_FILE.code
                } else {
                    NotificationType.GROUP_NORMAL.code
                }
            }
        } else {
            if (textMessage.recall != null) {
                NotificationType.RECALL_MSG.code
            } else {
                if (textMessage.isAttachmentMessage()) {
                    NotificationType.PERSONAL_FILE.code
                } else {
                    NotificationType.PERSONAL_NORMAL.code
                }
            }
        }

        return OutgoingPushMessage.Notification(
            OutgoingPushMessage.Args(if (textMessage.forWhat is For.Group) textMessage.forWhat.id else "", collapseId, gson.toJson(passThrough), mentionedPersons),
            type
        )
    }

    private fun updateSendStatus(status: Int) {
        if (textMessage.screenShot != null) return
        if (textMessage.reactions?.isNotEmpty() == true) return

        textMessage.sendType = status
        ApplicationDependencies.getMessageStore().putMessage(
            textMessage
        ).blockingAwait()
    }

    private fun reportSendCostTime() {
        L.i { "[Message] send text cost time totally: ${System.currentTimeMillis() - jobCreatedTime}, the actually cost time: ${System.currentTimeMillis() - startExecuteTime}" }
        Bundle().apply {
            putLong(MESSAGE_SENT_COST_TIME, System.currentTimeMillis() - jobCreatedTime)
            putLong(MESSAGE_SENT_COST_TIME_ACTUALLY, System.currentTimeMillis() - startExecuteTime)
        }.let {
            FirebaseAnalytics.getInstance(context).logEvent(MESSAGE_SENT_COST_TIME, it)
        }
    }

    companion object {
        const val KEY = "PushTextSendJob"
        private const val MESSAGE_SENT_COST_TIME = "message_sent_cost_time"
        private const val MESSAGE_SENT_COST_TIME_ACTUALLY = "message_sent_cost_time_actually"
        private val coroutineScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())
    }
}
