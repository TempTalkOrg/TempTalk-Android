package com.difft.android.call.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.session.command.CallSessionCommands
import com.difft.android.call.session.command.HangupReason
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent










class CallNotificationActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun callSessionCommands(): CallSessionCommands
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_HANG_UP) return
        L.i { "[Call][Cmd] notification hang-up tapped" }
        EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
            .callSessionCommands()
            .hangup(HangupReason.NOTIFICATION)
    }

    companion object {
        const val ACTION_HANG_UP = "com.difft.android.call.action.NOTIFICATION_HANG_UP"
    }
}
