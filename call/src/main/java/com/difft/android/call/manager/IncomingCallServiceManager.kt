package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
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
        
        showIncomingCallUI(context, callInfo)
        startIncomingCallNotifications(callInfo)
        enableIncomingCallTimeout(context, callInfo.roomId)
        wakeUpDevice()
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
        ringtoneManager.stopRingTone(tag)
        vibrationManager.stopVibration()
        callDataManager.setCallNotifyStatus(roomId, false)
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
     */
    private fun showIncomingCallUI(context: Context, callInfo: IncomingCallInfo) {
        val application = context.applicationContext
        
        if (!callToChatController.isIncomingCallActivityShowing() 
            && !onGoingCallStateManager.isInCalling() 
            && callToChatController.isAppForegrounded()) {
            // 显示来电 Activity
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
        } else {
            // 显示通知
            L.i { "[call] IncomingCallServiceManager startIncomingCallService showCallNotification roomId:${callInfo.roomId}" }
            callToChatController.showCallNotification(
                callInfo.roomId,
                callInfo.callName,
                callInfo.callerId,
                callInfo.conversationId,
                callInfo.callType,
                true
            )
        }
    }

    /**
     * 启动来电通知（铃声和震动）
     * 如果当前没有正在进行的通话且没有其他来电通知，则启动铃声和震动
     * 
     * @param callInfo 来电信息
     */
    private fun startIncomingCallNotifications(callInfo: IncomingCallInfo) {
        if (!callDataManager.hasCallDataNotifying()) {
            ringtoneManager.startRingTone(callInfo.intent)
            vibrationManager.startVibration()
            callDataManager.setCallNotifyStatus(callInfo.roomId, true)
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
     * 唤醒屏幕并临时禁用锁屏
     */
    private fun wakeUpDevice() {
        ScreenDeviceUtil.wakeUpDevice()
        ScreenLockUtil.temporarilyDisabled = true
    }
}

