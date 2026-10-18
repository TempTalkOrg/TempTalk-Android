package com.difft.android.call

import android.content.Context
import android.content.Intent
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.StartCallRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.call.data.createStartCallParams
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.session.CallSessionLauncher
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.messageserialization.db.store.DBRoomStore
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import util.ScreenLockUtil
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Joins an existing call (incoming accept, critical-alert join, chat join): clears the call's
 * critical alert, resolves this device's role from the call data, then launches the call page with
 * the JOIN intent and starts its session ([CallSessionLauncher]). Backs [LCallToChatController.joinCall].
 */
@Singleton
class CallJoinStarter @Inject constructor(
    private val messageNotificationUtil: MessageNotificationUtil,
    private val dbRoomStore: DBRoomStore,
    private val criticalAlertStateManager: CriticalAlertStateManager,
    private val callDataManagerLazy: Lazy<CallDataManager>,
    // Lazy: the launcher's session graph depends back on the call-to-chat controller.
    private val sessionLauncher: Lazy<CallSessionLauncher>,
) {
    private val mySelfId: String by lazy { globalServices.myId }
    private val callDataManager: CallDataManager by lazy { callDataManagerLazy.get() }

    @Suppress("LongParameterList") // mirrors LCallToChatController.joinCall
    suspend fun joinCall(
        context: Context,
        roomId: String,
        roomName: String?,
        callerId: String,
        callType: CallType,
        conversationId: String?,
        isNeedAppLock: Boolean,
    ): Boolean {
        // This join's own share of the wait dialog: every failure path releases only it.
        val waitToken = withContext(Dispatchers.Main) { CallWaitDialogUtil.show(context) }

        return try {
            withContext(Dispatchers.IO) {
                conversationId?.let { messageNotificationUtil.cancelCriticalAlertNotification(it) }
                dismissCriticalAlertIfActive()
                conversationId?.let { dbRoomStore.clearCriticalAlert(it) }

                val body = StartCallRequestBody(
                    callType.type, LCallConstants.CALL_VERSION,
                    System.currentTimeMillis(),
                    conversation = conversationId, roomId = roomId,
                )

                val callData = callDataManager.getCallData(roomId)
                val callRole = if (callData?.caller?.uid == mySelfId && callData.caller.did == DEFAULT_DEVICE_ID)
                    CallRole.CALLER else CallRole.CALLEE

                val startCallParams = createStartCallParams(body)
                LCallManager.checkQuicFeatureGrayStatus()

                val intent = CallIntent.Builder(application, LCallActivity::class.java)
                    .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .withAction(CallIntent.Action.JOIN_CALL)
                    .withRoomName(roomName)
                    .withCallType(callType.type)
                    .withCallRole(callRole.type)
                    .withCallerId(mySelfId)
                    .withConversationId(conversationId)
                    .withStartCallParams(startCallParams)
                    .withNeedAppLock(isNeedAppLock)
                    .withCallWaitDialogShown(true)
                    .build()

                startCallInternal(context, intent, waitToken)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e(e) { "[Call] joinCall unexpected error:" }
            withContext(Dispatchers.Main) { CallWaitDialogUtil.dismiss(waitToken) }
            false
        }
    }

    fun dismissCriticalAlertIfActive() {
        if (criticalAlertStateManager.isShowing()) {
            application.sendBroadcast(Intent(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS).apply {
                `package` = application.packageName
            })
        } else {
            messageNotificationUtil.cancelCriticalAlertNotification()
        }
    }

    /**
     * Launches the page and starts the session in one main-thread message. The app-lock exemption is
     * set only for a launch that went through, still in that message, so it is in place before any
     * callback of the new page; a refused launch leaves it alone and releases only this join's claim.
     */
    private suspend fun startCallInternal(context: Context, intent: Intent, waitToken: Int): Boolean =
        withContext(Dispatchers.Main) {
            val launched = sessionLauncher.get().launch(context.applicationContext, intent, waitToken)
            if (launched) ScreenLockUtil.temporarilyDisabled = true else CallWaitDialogUtil.dismiss(waitToken)
            launched
        }
}
