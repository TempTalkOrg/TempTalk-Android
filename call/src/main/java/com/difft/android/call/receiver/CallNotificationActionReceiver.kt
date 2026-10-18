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

/**
 * Target of the ongoing-call notification's hang-up action.
 *
 * Declared in the manifest with `exported="false"` and no intent filter; the notification's
 * `PendingIntent` names this component explicitly, so it is delivered whenever the process is
 * alive, independent of the call page's runtime receivers. `onReceive` runs on main, so the
 * command runs inline; it only starts async signaling, which the running foreground service keeps
 * the process alive for.
 */
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
