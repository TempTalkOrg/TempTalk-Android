package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.VoiceRecordingTracker
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IMessageNotificationUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.util.ScreenDeviceUtil
import util.ScreenLockUtil
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomingCallServiceManager @Inject constructor(
    private val callToChatController: LCallToChatController,
    private val criticalAlertManager: CriticalAlertManager,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callDataManager: CallDataManager,
    private val ringtoneManager: CallRingtoneManager,
    private val vibrationManager: CallVibrationManager,
    private val callTimeoutManager: CallTimeoutManager,
    private val ringtoneSource: CallRingtoneSource,
    private val messageNotificationUtil: IMessageNotificationUtil,
) {

    data class IncomingCallInfo(
        val roomId: String,
        val callType: CallType,
        val callerId: String,
        val conversationId: String?,
        val callName: String,
        val intent: Intent
    )

    fun startIncomingCallService(context: Context, intent: Intent) {
        L.i { "[call] IncomingCallServiceManager startIncomingCallService start Service" }

        val callInfo = parseIncomingCallIntent(intent) ?: return
        if (shouldSkipIncomingCall(callInfo)) return

        val shownAsActivity = showIncomingCallUI(context, callInfo)
        startIncomingCallNotifications(callInfo, notificationPosted = !shownAsActivity)
        enableIncomingCallTimeout(context, callInfo.roomId)
        
        
        
        
        wakeUpDevice(disableScreenLockTemporarily = shownAsActivity)
    }

    fun stopIncomingCallService(context: Context, roomId: String, tag: String? = null) {
        L.i { "[call] IncomingCallServiceManager stopIncomingCallService stop Service, tag=$tag" }
        callTimeoutManager.cancelCallWithTimeout(roomId)
        callToChatController.cancelNotificationById(roomId.hashCode())
        
        
        
        
        
        
        callDataManager.setCallNotifyStatus(roomId, false)
        if (!callDataManager.hasCallDataNotifying()) {
            ringtoneManager.stopRingTone(tag)
            vibrationManager.stopVibration()
        }
        val application = context.applicationContext
        application.sendBroadcast(
            Intent(LCallConstants.CALL_OPERATION_INVITED_DESTROY)
                .setPackage(application.packageName)
                .putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, roomId)
        )
    }

    

    private fun parseIncomingCallIntent(intent: Intent): IncomingCallInfo? {
        val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_ROOM_ID) ?: return null
        if (roomId.isEmpty()) return null

        val callType: CallType =
            intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE)?.let {
                CallType.fromString(it)
            } ?: CallType.ONE_ON_ONE

        val callerId: String = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALLER_ID) ?: ""
        val conversationId: String? = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CONVERSATION_ID)
        val callName: String = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_NAME) ?: ""

        return IncomingCallInfo(
            roomId = roomId,
            callType = callType,
            callerId = callerId,
            conversationId = conversationId,
            callName = callName,
            intent = intent
        )
    }

    private fun shouldSkipIncomingCall(callInfo: IncomingCallInfo): Boolean {
        return criticalAlertManager.isCriticalAlertShowing(callInfo.conversationId)
    }

    



    private fun showIncomingCallUI(context: Context, callInfo: IncomingCallInfo): Boolean {
        val application = context.applicationContext

        if (!callToChatController.isIncomingCallActivityShowing()
            && !onGoingCallStateManager.isInCalling()
            && callToChatController.isAppForegrounded()
            && !VoiceRecordingTracker.isRecording) {
            
            val intentActivity = CallIntent.Builder(application, globalServices.activityProvider.getActivityClass(ActivityType.L_INCOMING_CALL))
                .withAction(CallIntent.Action.INCOMING_CALL)
                .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .withRoomId(callInfo.roomId)
                .withRoomName(callInfo.callName)
                .withCallType(callInfo.callType.type)
                .withCallerId(callInfo.callerId)
                .withConversationId(callInfo.conversationId)
                .withCallRole(CallRole.CALLEE.type)
                .withNeedAppLock(false)
                .build()
            application.startActivity(intentActivity)
            return true
        } else {
            
            if (VoiceRecordingTracker.isRecording) {
                L.i { "[call] IncomingCallServiceManager suppress incoming-call Activity due to voice recording, fall back to notification roomId=${callInfo.roomId}" }
            }
            L.i { "[call] IncomingCallServiceManager startIncomingCallService showCallNotification roomId:${callInfo.roomId}" }
            callToChatController.showCallNotification(
                callInfo.roomId,
                callInfo.callName,
                callInfo.callerId,
                callInfo.conversationId,
                callInfo.callType,
                true
            )
            return false
        }
    }

    


    private fun startIncomingCallNotifications(callInfo: IncomingCallInfo, notificationPosted: Boolean) {
        val shouldStartRinging = !callDataManager.hasCallDataNotifying()
        callDataManager.setCallNotifyStatus(callInfo.roomId, true)
        if (shouldStartRinging) {
            if (userChannelSoundWillRing(notificationPosted)) {
                L.i { "[call] IncomingCallServiceManager skip app ringtone, user call-channel sound rings instead" }
            } else {
                ringtoneManager.startRingTone(callInfo.intent)
            }
            vibrationManager.startVibration()
        }
    }

    





    private fun userChannelSoundWillRing(notificationPosted: Boolean): Boolean {
        if (!notificationPosted) return false
        val policy = ringtoneSource.channelSoundPolicy(messageNotificationUtil.getNotificationChannelName())
        return policy == ChannelSoundPolicy.CUSTOM
    }

    private fun enableIncomingCallTimeout(context: Context, roomId: String) {
        L.i { "[call] IncomingCallServiceManager enable incoming call timeout detection" }
        callTimeoutManager.checkCallWithTimeout(
            CallTimeoutManager.CallState.INCOMING_CALL,
            CallTimeoutManager.DEF_INCOMING_CALL_TIMEOUT,
            roomId,
            callBack = { stopIncomingCallService(context, roomId) }
        )
    }

    


    private fun wakeUpDevice(disableScreenLockTemporarily: Boolean) {
        ScreenDeviceUtil.wakeUpDevice()
        if (disableScreenLockTemporarily) {
            ScreenLockUtil.temporarilyDisabled = true
        }
    }
}

