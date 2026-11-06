package com.difft.android.push

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.Base64
import com.difft.android.base.utils.NotificationRepostGuard
import com.difft.android.base.utils.appScope
import com.difft.android.call.manager.CallDataManager
import com.difft.android.chat.data.NOTIFY_TYPE_CALL_HANGUP
import com.difft.android.chat.data.PushCustomContent
import com.difft.android.chat.messages.ENVELOPE_TAG_FCM
import com.difft.android.chat.messages.EnvelopToMessageProcessor
import com.difft.android.chat.messages.EnvelopeProcessResult
import com.difft.android.chat.messages.reportPermanentDrop
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.NotificationDelivery
import com.difft.android.websocket.api.util.EnvelopDeserializer
import dagger.hilt.android.qualifiers.ApplicationContext
import difft.android.messageserialization.For
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes the envelope carried by a normal (`critical == 0`) FCM push on its own coroutine.
 *
 * That coroutine owns its receive ticket (#1382): it is acquired as the first statement and
 * closed after processing and the notification bookkeeping, so a pause that begins after the push
 * arrived either waits for this work or turns it into a no-op.
 */
@Singleton
class FcmNormalMessageHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messagingGate: MessagingGate,
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val userManager: UserManager,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val callDataManager: CallDataManager,
    private val notificationRepostGuard: NotificationRepostGuard,
) {

    /** @param scope must run on an IO dispatcher; [appScope] does. */
    fun handle(pushCustomContent: PushCustomContent, scope: CoroutineScope = appScope): Job {
        val msg = pushCustomContent.msg
        if (msg == null) {
            L.w { "[fcm] Processing message pushCustomContent.msg is null" }
            return scope.launch { handleMessageProcessingError(pushCustomContent) }
        }
        return scope.launch {
            val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "fcm")
            if (admission == null) {
                L.i { "[MigrationPause] event=receive_dropped source=fcm count=1" }
                return@launch
            }
            admission.use { processAdmitted(msg, pushCustomContent, it) }
        }
    }

    private suspend fun processAdmitted(msg: String, pushCustomContent: PushCustomContent, admission: MessagingTicket) {
        // #1347: notifying invites this push may duplicate, snapshotted before processing so
        // a notification this very push creates is never re-posted over its full-screen intent.
        val showingInviteIds = snapshotShowingInviteNotificationIds(pushCustomContent)
        try {
            L.i { "[fcm] Start processing message" }

            val serviceEnvelope = EnvelopDeserializer.deserializeFrom(Base64.decode(msg))

            val processRes = serviceEnvelope?.let { envelopToMessageProcessor.process(it, ENVELOPE_TAG_FCM, admission) }
            when (processRes) {
                is EnvelopeProcessResult.Success -> {
                    val messageResult = processRes.result
                    if (messageResult != null) {
                        L.i { "[fcm] Processing message success:${messageResult.message.timeStamp} shouldShowNotification:${messageResult.shouldShowNotification}" }
                        if (messageResult.shouldShowNotification) {
                            // Atomic increment (issue #725 §9.6): UserManager.update serializes
                            // the read-modify-write under its writeMutex, so concurrent FCM
                            // pushes can't race the way the legacy `getInt`+`putInt` pair did.
                            userManager.update { unreadMsgNum += 1 }
                            messageNotificationUtil.showNotificationSuspend(
                                context,
                                messageResult.message,
                                messageResult.conversation,
                                delivery = NotificationDelivery.FCM,
                            )
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
                // Unreachable while this coroutine holds its ticket; nothing was stored.
                EnvelopeProcessResult.Paused -> Unit
                null -> {
                    L.w { "[fcm] Processing message result is null (envelope deserialization failed)" }
                }
            }
        } catch (e: Exception) {
            L.w { "[fcm] Processing message envelope error: ${e.stackTraceToString()}" }
            handleMessageProcessingError(pushCustomContent)
        } finally {
            showingInviteIds.forEach { notificationRepostGuard.repostIfActive(it) }
        }
    }

    /** Notification ids of the notifying call invites this push may duplicate that are still showing. */
    private fun snapshotShowingInviteNotificationIds(pushCustomContent: PushCustomContent): List<Int> =
        try {
            matchNotifyingCallRoomIds(
                callDataManager.getCallListData().values,
                pushCustomContent.gid,
                pushCustomContent.uid,
            ).map { it.hashCode() }
                .filter { messageNotificationUtil.isNotificationShowing(it) }
        } catch (e: Exception) {
            L.w { "[fcm] invite snapshot failed error=${e.javaClass.simpleName}" }
            emptyList()
        }

    private suspend fun handleMessageProcessingError(pushCustomContent: PushCustomContent) {
        // Server notify pushes carry no envelope; the generic fallback would be misleading (#1347).
        if (isServerNotifyPush(pushCustomContent.uid, pushCustomContent.notifyType)) {
            L.i { "[fcm] skip fallback notification reason=serverPush notifyType=${pushCustomContent.notifyType}" }
            return
        }

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
                messageNotificationUtil.showNotificationOfPush(context, target)
                L.i { "[fcm] Successfully showed fallback notification for target: $target" }
            } ?: L.w { "[fcm] Invalid target type, cannot show notification" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[fcm] Failed to show fallback notification: ${e.stackTraceToString()}" }
        }
    }
}
