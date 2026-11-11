package com.difft.android.chat.jobmanager

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import util.PendingIntentFlags
import java.util.UUID













class AlarmManagerScheduler(
    private val application: Application
) : Scheduler {

    override fun schedule(delay: Long, constraints: List<Constraint>) {
        if (delay > 0 && constraints.all { it.isMet() }) {
            setUniqueAlarm(application, System.currentTimeMillis() + delay)
        }
    }

    
    override fun cancelAllExcept(keep: Set<Int>) = Unit

    private fun setUniqueAlarm(context: Context, time: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RetryReceiver::class.java).apply {
            action = context.packageName + UUID.randomUUID().toString()
        }
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            time,
            PendingIntent.getBroadcast(context, 0, intent, PendingIntentFlags.mutable())
        )
        L.i { "Set an alarm to retry a job in ${time - System.currentTimeMillis()} ms." }
    }

    class RetryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            L.i { "Received an alarm to retry a job." }
            ApplicationDependencies.getJobManager().wakeUp()
        }
    }
}
