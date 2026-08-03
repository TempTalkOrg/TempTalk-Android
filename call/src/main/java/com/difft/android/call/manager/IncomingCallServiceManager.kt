package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.VoiceRecordingTracker
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.util.ScreenDeviceUtil
import util.ScreenLockUtil
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 来电服务管理器
 * 负责处理来电相关的所有业务逻辑，包括：
 * - 解析来电 Intent
 * - 显示来电界面或通知
 * - 管理铃声、震动
 * - 管理超时检测
 * - 设备唤醒和锁屏控制
 */
@Singleton
class IncomingCallServiceManager @Inject constructor(
    private val callToChatController: LCallToChatController,
    private val criticalAlertManager: CriticalAlertManager,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callDataManager: CallDataManager,
    private val ringtoneManager: CallRingtoneManager,
    private val vibrationManager: CallVibrationManager,
    private val callTimeoutManager: CallTimeoutManager
) {

    /**
     * 来电信息数据类
     * 封装从 Intent 中解析出的来电相关信息
     */
    data class IncomingCallInfo(
        val roomId: String,
        val callType: CallType,
        val callerId: String,
        val conversationId: String?,
        val callName: String,
        val intent: Intent
    )

    /**
     * 启动来电服务
     * 处理来电请求，显示来电界面或通知，播放铃声和震动，启动超时检测
     * 
     * @param context 上下文
     * @param intent 包含来电信息的 Intent，需要包含房间ID、通话类型、呼叫者ID等信息
     */
    fun startIncomingCallService(context: Context, intent: Intent) {
        L.i { "[call] IncomingCallServiceManager startIncomingCallService start Service" }
        
        val callInfo = parseIncomingCallIntent(intent) ?: return
        if (shouldSkipIncomingCall(callInfo)) return
        
        val shownAsActivity = showIncomingCallUI(context, callInfo)
        startIncomingCallNotifications(callInfo)
        enableIncomingCallTimeout(context, callInfo.roomId)
        // Only temporarily bypass the app lock when a foreground incoming-call Activity is actually
        // shown (the user is already past the lock). On the notification-only / background path there
        // is no Activity to protect, and leaving the flag set would leak into the next app entry and
        // let the home screen skip the app lock (e.g. cold start from a call notification).
        wakeUpDevice(disableScreenLockTemporarily = shownAsActivity)
    }

    /**
     * 停止来电服务
     * 停止指定房间的来电通知、铃声、震动等，并发送销毁广播
     * 
     * @param context 上下文
     * @param roomId 房间ID
     * @param tag 停止原因标签，用于日志记录，可为 null
     */
    fun stopIncomingCallService(context: Context, roomId: String, tag: String? = null) {
        L.i { "[call] IncomingCallServiceManager stopIncomingCallService stop Service, tag=$tag" }
        callTimeoutManager.cancelCallWithTimeout(roomId)
        callToChatController.cancelNotificationById(roomId.hashCode())
        // Clear this call's notifying flag first, then stop the shared ringtone/vibration ONLY when no
        // other incoming call is still notifying. Ringtone/vibration are global singletons shared by all
        // concurrent incoming calls (only the first one starts them; see startIncomingCallNotifications).
        // Stopping them unconditionally here would silence still-active concurrent calls when the first
        // one ends. Keeping them running lets the remaining call(s) stay audible; the last one to stop
        // finally stops them.
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

    /**
     * 解析来电 Intent，提取来电信息
     * 
     * @param intent 包含来电信息的 Intent
     * @return 解析后的来电信息，如果解析失败则返回 null
     */
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

    /**
     * 判断是否应该跳过来电处理
     * 如果已有 critical alert 正在显示，则不再显示来电页面或通知
     * 
     * @param callInfo 来电信息
     * @return true 表示应该跳过，false 表示继续处理
     */
    private fun shouldSkipIncomingCall(callInfo: IncomingCallInfo): Boolean {
        return criticalAlertManager.isCriticalAlertShowing(callInfo.conversationId)
    }

    /**
     * 显示来电界面
     * 根据应用状态决定显示 Activity 还是 Notification
     * 
     * @param context 上下文
     * @param callInfo 来电信息
     * @return true 表示以前台来电 Activity 形式展示；false 表示以通知形式展示
     */
    private fun showIncomingCallUI(context: Context, callInfo: IncomingCallInfo): Boolean {
        val application = context.applicationContext
        
        if (!callToChatController.isIncomingCallActivityShowing()
            && !onGoingCallStateManager.isInCalling()
            && callToChatController.isAppForegrounded()
            && !VoiceRecordingTracker.isRecording) {
            // Show incoming call Activity
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
            // Show notification
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

    /**
     * 启动来电通知（铃声和震动）
     *
     * 铃声/震动只为"第一路"来电启动（已有来电在响铃时不再重复响铃），但每一路来电都会被标记为
     * notifying（活跃来电）。这样即使存在多路并发来电，回前台恢复逻辑也能据此挑出最新一路仍活跃的
     * 来电，而不会因为后到的来电从未置位 notifying 而选错或漏选。
     *
     * @param callInfo 来电信息
     */
    private fun startIncomingCallNotifications(callInfo: IncomingCallInfo) {
        val shouldStartRinging = !callDataManager.hasCallDataNotifying()
        callDataManager.setCallNotifyStatus(callInfo.roomId, true)
        if (shouldStartRinging) {
            ringtoneManager.startRingTone(callInfo.intent)
            vibrationManager.startVibration()
        }
    }

    /**
     * 启用来电超时检测
     * 当超时后会自动停止来电服务
     * 
     * @param context 上下文
     * @param roomId 房间ID
     */
    private fun enableIncomingCallTimeout(context: Context, roomId: String) {
        L.i { "[call] IncomingCallServiceManager enable incoming call timeout detection" }
        callTimeoutManager.checkCallWithTimeout(
            CallTimeoutManager.CallState.INCOMING_CALL,
            CallTimeoutManager.DEF_INCOMING_CALL_TIMEOUT,
            roomId,
            callBack = { stopIncomingCallService(context, roomId) }
        )
    }

    /**
     * 唤醒设备
     *
     * 始终唤醒屏幕；仅当来电以前台 Activity 形式展示时才临时禁用应用锁。通知/后台路径不禁用，
     * 避免该内存标志泄漏到下一次 APP 入口而绕过主页应用锁。
     *
     * @param disableScreenLockTemporarily 是否临时禁用应用锁（仅前台来电 Activity 场景为 true）
     */
    private fun wakeUpDevice(disableScreenLockTemporarily: Boolean) {
        ScreenDeviceUtil.wakeUpDevice()
        if (disableScreenLockTemporarily) {
            ScreenLockUtil.temporarilyDisabled = true
        }
    }
}

