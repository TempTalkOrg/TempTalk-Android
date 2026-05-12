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

/**
 * Schedules tasks using the [AlarmManager].
 *
 * Given that this scheduler is only used when [KeepAliveService] is also used (which keeps
 * all of the [ConstraintObserver]s running), this only needs to schedule future runs in
 * situations where all constraints are already met. Otherwise, the [ConstraintObserver]s will
 * trigger future runs when the constraints are met.
 *
 * For the same reason, this class also doesn't have to schedule jobs that don't have delays.
 *
 * Important: Only use on API < 26.
 */
class AlarmManagerScheduler(
    private val application: Application
) : Scheduler {

    override fun schedule(delay: Long, constraints: List<Constraint>) {
        if (delay > 0 && constraints.all { it.isMet() }) {
            setUniqueAlarm(application, System.currentTimeMillis() + delay)
        }
    }

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
