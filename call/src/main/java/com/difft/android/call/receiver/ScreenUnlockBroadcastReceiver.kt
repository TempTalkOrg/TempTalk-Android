package com.difft.android.call.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.state.OnGoingCallStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import util.ScreenLockUtil

class ScreenUnlockBroadcastReceiver(
    private val onBringCallActivityToFront: (Context) -> Unit,
    private val onGoingCallStateManager: OnGoingCallStateManager
) {
    private var isRegistered = false
    private var registeredContext: Context? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_USER_PRESENT == intent.action) {
                if (isNeedBringCallActivityToFront()) {
                    L.i { "[Call] ScreenUnlockBroadcastReceiver Device unlocked, start ForegroundService to bring CallActivity to front" }
                    ScreenLockUtil.temporarilyDisabled = true
                    onBringCallActivityToFront(context)
                }
            }
        }
    }

    /**
     * 仅在APP后台且非PIP模式下恢复Call页面
     */
    private fun isNeedBringCallActivityToFront(): Boolean {
        return onGoingCallStateManager.isInCalling()
                && onGoingCallStateManager.isInForeground().not()
                && onGoingCallStateManager.isInPipMode().not()
    }

    /**
     * 注册广播接收器
     *
     * 使用 applicationContext 注册，确保 register/unregister 使用相同的
     * Context identity（LoadedApk 以 Context 为 key 存储 ReceiverDispatcher），
     * 同时避免持有 Activity 引用。
     */
    fun register(context: Context) {
        if (isRegistered) {
            L.w { "[Call] ScreenUnlockBroadcastReceiver already registered" }
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }

        val appContext = context.applicationContext
        try {
            ContextCompat.registerReceiver(
                appContext,
                broadcastReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            registeredContext = appContext
            isRegistered = true
            L.i { "[Call] ScreenUnlockBroadcastReceiver registered" }
        } catch (e: Exception) {
            L.e { "[Call] ScreenUnlockBroadcastReceiver failed to register: ${e.message}" }
        }
    }

    /**
     * 注销广播接收器
     *
     * unregisterReceiver 底层执行 Binder IPC（→ AMS），当 system_server
     * 负载高时可能阻塞数百毫秒导致 ANR，因此将其移至 IO 线程异步执行。
     * onDestroy 场景下不需要等待注销完成——Activity 已不在前台，receiver
     * 不会再被触发。
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
                L.i { "[Call] ScreenUnlockBroadcastReceiver unregister" }
            } catch (e: Exception) {
                L.e { "[Call] ScreenUnlockBroadcastReceiver failed to unregister: ${e.stackTraceToString()}" }
            }
        }
    }

    /**
     * 释放资源
     * @param context 上下文
     */
    fun release(context: Context?) {
        context?.let { unregister(it) }
        L.i { "[Call] ScreenUnlockBroadcastReceiver released" }
    }
}