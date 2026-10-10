package com.difft.android.chat.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * Periodic AlarmManager check that recovers [MessageForegroundService] and wakes the
 * WebSocket monitor. Fires every 5 min outside Doze; inside Doze the system stretches it to
 * ~15 min with the exact-alarm permission or 30 min - 2 h without. Each delivery re-arms the
 * next alarm, so the chain is one-shot + re-arm.
 */
class ServiceCheckReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun messageServiceManager(): MessageServiceManager

        fun userManager(): UserManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        L.i { "[ServiceCheck] alarm fired serviceRunning=${MessageForegroundService.isRunning}" }

        // goAsync() keeps the receiver alive until the coroutine below finishes.
        val pendingResult = goAsync()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, ReceiverEntryPoint::class.java
        )

        appScope.launch {
            try {
                // End the chain when keep-alive is disabled: no re-arm, no service start.
                // Otherwise any racily-armed orphan alarm would be re-armed here
                // unconditionally, permanently resurrecting the keep-alive chain.
                if (entryPoint.userManager().getUserData()?.keepAliveEnabled != true) {
                    L.w { "[ServiceCheck] Keep-alive disabled, ending alarm chain" }
                    return@launch
                }

                entryPoint.messageServiceManager().scheduleAlarmCheck()
                entryPoint.messageServiceManager().checkAndRecover()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
