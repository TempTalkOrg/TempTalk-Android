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








class ServiceCheckReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun messageServiceManager(): MessageServiceManager

        fun userManager(): UserManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        L.i { "[ServiceCheck] alarm fired serviceRunning=${MessageForegroundService.isRunning}" }

        
        val pendingResult = goAsync()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, ReceiverEntryPoint::class.java
        )

        appScope.launch {
            try {
                
                
                
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
