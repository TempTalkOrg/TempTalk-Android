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

    
    private fun isNeedBringCallActivityToFront(): Boolean {
        return onGoingCallStateManager.isInCalling()
                && onGoingCallStateManager.isInForeground().not()
                && onGoingCallStateManager.isInPipMode().not()
    }

    

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

    

    fun unregister(context: Context) {
        if (!isRegistered) {
            return
        }
        isRegistered = false

        val ctx = registeredContext ?: context.applicationContext
        registeredContext = null
        val receiver = broadcastReceiver
        
        
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

    fun release(context: Context?) {
        context?.let { unregister(it) }
        L.i { "[Call] ScreenUnlockBroadcastReceiver released" }
    }
}