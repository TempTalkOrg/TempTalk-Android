package com.difft.android.chat.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import org.thoughtcrime.securesms.util.MessageNotificationUtil

class StopCriticalAlertSoundReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MessageNotificationUtil.STOP_CRITICAL_ALERT_SOUND) {
            return
        }

        if (intent.`package` != null && intent.`package` != context.packageName) {
            L.w { "Broadcast not targeted to this package: ${intent.`package`}" }
            return
        }

        val id = intent.getIntExtra("notification_id", -1)
        if (id != -1) {
            CriticalAlertSoundPlayer.stopIfMatch(id)
        }
    }
}