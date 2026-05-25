package com.difft.android.call.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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
    private var registeredContext: Context? = null

    /**
     * 注册广播接收器
     *
     * 使用 applicationContext 注册，确保 register/unregister 使用相同的
     * Context identity（LoadedApk 以 Context 为 key 存储 ReceiverDispatcher），
     * 同时避免持有 Activity 引用。
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

        val appContext = context.applicationContext
        try {
            ContextCompat.registerReceiver(
                appContext,
                broadcastReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registeredContext = appContext
            isRegistered = true
            L.i { "[Call] CallActivityBroadcastReceiver registered" }
        } catch (e: Exception) {
            L.e { "[Call] CallActivityBroadcastReceiver failed to register: ${e.message}" }
        }
    }

    /**
     * 注销广播接收器
     *
     * unregisterReceiver 底层执行 Binder IPC（→ AMS），当 system_server
     * 负载高时可能阻塞数百毫秒导致 ANR，因此将其移至 IO 线程异步执行。
     */
    fun unregister(context: Context) {
        if (!isRegistered) {
            return
        }
        isRegistered = false

        val ctx = registeredContext ?: context.applicationContext
        registeredContext = null
        val receiver = broadcastReceiver
        // GlobalScope is deliberate: called from onDestroy where no lifecycle scope
        // survives; this is a fire-and-forget Binder IPC cleanup.
        @Suppress("GlobalCoroutineUsage", "OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                ctx.unregisterReceiver(receiver)
                L.i { "[Call] CallActivityBroadcastReceiver unregister" }
            } catch (e: Exception) {
                L.e { "[Call] CallActivityBroadcastReceiver failed to unregister: ${e.stackTraceToString()}" }
            }
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

