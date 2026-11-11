package com.difft.android.chat.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.notification.OemBrand
import java.time.LocalTime












internal object NightAlarmClockWake {
    val NIGHT_START: LocalTime = LocalTime.of(22, 0)
    val NIGHT_END: LocalTime = LocalTime.of(10, 0)

    
    const val INTERVAL_MS = 2 * 60 * 1000L

    private const val SHOW_REQUEST_CODE = 10002

    
    fun isEligibleBrand(brand: OemBrand): Boolean = brand == OemBrand.XIAOMI || brand == OemBrand.VIVO

    @VisibleForTesting
    @Volatile
    var isEligibleDevice: () -> Boolean = { isEligibleBrand(OemBrand.current()) }

    @VisibleForTesting
    @Volatile
    var now: () -> LocalTime = { LocalTime.now() }

    fun inNightWindow(time: LocalTime): Boolean = !time.isBefore(NIGHT_START) || time.isBefore(NIGHT_END)

    fun applies(): Boolean = isEligibleDevice() && inNightWindow(now())

    




    fun arm(context: Context, alarmManager: AlarmManager, operation: PendingIntent): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        val show = PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(System.currentTimeMillis() + INTERVAL_MS, show),
            operation
        )
        return true
    }
}
