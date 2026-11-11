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
import com.difft.android.call.session.CallSessionHolder
import com.difft.android.call.session.CallSessionLauncher
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.chat.messages.CriticalAlertHighlightWriter
import com.difft.android.chat.util.MessageNotificationUtil
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import util.ScreenLockUtil
import javax.inject.Inject
import javax.inject.Singleton






@Singleton
class CallJoinStarter @Inject constructor(
    private val messageNotificationUtil: MessageNotificationUtil,
    private val criticalAlertHighlightWriter: CriticalAlertHighlightWriter,
    private val criticalAlertStateManager: CriticalAlertStateManager,
    private val criticalAlertCallGate: CriticalAlertCallGate,
    private val callDataManagerLazy: Lazy<CallDataManager>,
    
    private val sessionLauncher: Lazy<CallSessionLauncher>,
    private val sessionHolder: Lazy<CallSessionHolder>,
) {
    private val mySelfId: String by lazy { globalServices.myId }
    private val callDataManager: CallDataManager by lazy { callDataManagerLazy.get() }

    @Suppress("LongParameterList") 
    suspend fun joinCall(
        context: Context,
        roomId: String,
        roomName: String?,
        callerId: String,
        callType: CallType,
        conversationId: String?,
        isNeedAppLock: Boolean,
    ): Boolean {
        
        val joinToken = criticalAlertCallGate.beginJoin(roomId, conversationId)
        try {
            return joinCallInternal(context, roomId, roomName, callType, conversationId, isNeedAppLock)
        } finally {
            criticalAlertCallGate.endJoin(joinToken)
        }
    }

    private suspend fun joinCallInternal(
        context: Context,
        roomId: String,
        roomName: String?,
        callType: CallType,
        conversationId: String?,
        isNeedAppLock: Boolean,
    ): Boolean {
        
        val waitToken = withContext(Dispatchers.Main) { CallWaitDialogUtil.show(context) }

        return try {
            withContext(Dispatchers.IO) {
                conversationId?.let { messageNotificationUtil.cancelCriticalAlertNotification(it) }
                dismissCriticalAlertIfActive()
                conversationId?.let { criticalAlertHighlightWriter.clearForJoin(it) }

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
                    .withRoomId(roomId)
                    .withRoomName(roomName)
                    .withCallType(callType.type)
                    .withCallRole(callRole.type)
                    .withCallerId(mySelfId)
                    .withConversationId(conversationId)
                    .withStartCallParams(startCallParams)
                    .withNeedAppLock(isNeedAppLock)
                    .withCallWaitDialogShown(true)
                    .build()

                startCallInternal(context, intent, waitToken, roomId)
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

    




    private suspend fun startCallInternal(context: Context, intent: Intent, waitToken: Int, roomId: String): Boolean =
        withContext(Dispatchers.Main) {
            val launched = sessionLauncher.get().launch(context.applicationContext, intent, waitToken)
            if (launched) {
                
                sessionHolder.get().current.value?.let { criticalAlertCallGate.bindSessionRoom(it.id, roomId) }
                ScreenLockUtil.temporarilyDisabled = true
            } else {
                CallWaitDialogUtil.dismiss(waitToken)
            }
            launched
        }
}
