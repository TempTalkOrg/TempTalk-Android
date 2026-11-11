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


class BootCompletedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun messageServiceManager(): MessageServiceManager
        fun userManager(): UserManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        L.i { "[BootCompleted] Device rebooted" }

        
        val pendingResult = goAsync()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, ReceiverEntryPoint::class.java
        )

        appScope.launch {
            try {
                val userData = entryPoint.userManager().getUserData()
                if (userData?.keepAliveEnabled == true) {
                    L.i { "[BootCompleted] Keep-alive enabled, recovering service" }

                    
                    entryPoint.messageServiceManager().scheduleAlarmCheck()

                    entryPoint.messageServiceManager().checkAndRecover()
                } else {
                    L.d { "[BootCompleted] Keep-alive not enabled, skip recovery" }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
