package com.difft.android.call.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallActivity

/**
 * 管理通话 Activity 的广播接收器
 * 负责处理通话相关的广播事件，提高可测试性
 * 
 * 使用方式：
 * ```
 * val receiver = CallActivityBroadcastReceiver(
 *     onPushStreamLimit = { showPushStreamLimitTip() },
 *     onOngoingTimeout = { roomId -> handleOngoingTimeout(roomId) },
 *     onCallControl = { actionType, roomId -> handleCallAction(actionType, roomId) }
 * )
 * 
 * // 注册广播接收器
 * receiver.register(context)
 * 
 * // 注销广播接收器
 * receiver.unregister(context)
 * ```
 */
class CallActivityBroadcastReceiver(
    private val onPushStreamLimit: () -> Unit,
    private val onOngoingTimeout: (String) -> Unit,
    private val onCallControl: (String, String) -> Unit
) {
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT -> {
                    L.i { "[Call] CallActivityBroadcastReceiver CALL_NOTIFICATION_PUSH_STREAM_LIMIT" }
                    onPushStreamLimit()
                }

                LCallConstants.CALL_ONGOING_TIMEOUT -> {
                    L.i { "[Call] CallActivityBroadcastReceiver CALL_ONGOING_TIMEOUT" }
                    val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_ROOM_ID)
                    roomId?.let { onOngoingTimeout(it) }
                }

                LCallActivity.ACTION_IN_CALLING_CONTROL -> {
                    val controlType = intent.getStringExtra(LCallActivity.EXTRA_CONTROL_TYPE) ?: ""
                    val roomId = intent.getStringExtra(LCallActivity.EXTRA_PARAM_ROOM_ID) ?: ""
                    L.i { "[Call] CallActivityBroadcastReceiver ACTION_IN_CALLING_CONTROL controlType:$controlType roomId:$roomId" }
                    onCallControl(controlType, roomId)
                }
            }
        }
    }

    private var isRegistered = false

    /**
     * 注册广播接收器
     * @param context 上下文
     */
    @android.annotation.SuppressLint("WrongConstant")
    fun register(context: Context) {
        if (isRegistered) {
            L.w { "[Call] CallActivityBroadcastReceiver already registered" }
            return
        }

        val filter = IntentFilter().apply {
            addAction(LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT)
            addAction(LCallConstants.CALL_ONGOING_TIMEOUT)
            addAction(LCallActivity.ACTION_IN_CALLING_CONTROL)
        }

        try {
            ContextCompat.registerReceiver(
                context,
                broadcastReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isRegistered = true
            L.i { "[Call] CallActivityBroadcastReceiver registered" }
        } catch (e: Exception) {
            L.e { "[Call] CallActivityBroadcastReceiver failed to register: ${e.message}" }
        }
    }

    /**
     * 注销广播接收器
     * @param context 上下文
     */
    fun unregister(context: Context) {
        if (!isRegistered) {
            return
        }

        try {
            context.unregisterReceiver(broadcastReceiver)
            L.i { "[Call] CallActivityBroadcastReceiver unregister" }
        } catch (e: Exception) {
            L.e { "[Call] CallActivityBroadcastReceiver failed to unregister: ${e.message}" }
        } finally {
            // 无论成功或失败，都重置注册状态
            isRegistered = false
        }
    }

    /**
     * 释放资源
     * @param context 上下文
     */
    fun release(context: Context?) {
        context?.let { unregister(it) }
        L.i { "[Call] CallActivityBroadcastReceiver released" }
    }

}

