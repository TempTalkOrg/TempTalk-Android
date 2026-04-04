package com.difft.android.call.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.state.OnGoingCallStateManager
import util.ScreenLockUtil

class ScreenUnlockBroadcastReceiver(
    private val onBringCallActivityToFront: (Context) -> Unit,
    private val onGoingCallStateManager: OnGoingCallStateManager
) {
    private var isRegistered = false

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
     * @param context 上下文
     */
    fun register(context: Context) {
        if (isRegistered) {
            L.w { "[Call] ScreenUnlockBroadcastReceiver already registered" }
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }

        try {
            ContextCompat.registerReceiver(
                context,
                broadcastReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isRegistered = true
            L.i { "[Call] ScreenUnlockBroadcastReceiver registered" }
        } catch (e: Exception) {
            L.e { "[Call] ScreenUnlockBroadcastReceiver failed to register: ${e.message}" }
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
            L.i { "[Call] ScreenUnlockBroadcastReceiver unregister" }
        } catch (e: Exception) {
            L.e { "[Call] ScreenUnlockBroadcastReceiver failed to unregister: ${e.message}" }
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
        L.i { "[Call] ScreenUnlockBroadcastReceiver released" }
    }
}