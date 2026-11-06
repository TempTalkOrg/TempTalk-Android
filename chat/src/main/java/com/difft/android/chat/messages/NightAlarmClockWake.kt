package com.difft.android.chat.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.notification.OemBrand
import java.time.LocalTime

/**
 * Night mode of the [ServiceCheckReceiver] alarm chain on skins that freeze the app at night.
 *
 * HyperOS freezes the whole app process at night (greezer reason `nightDoze`) whatever the battery
 * policy, and OriginOS 6 does the same on battery (observed 00:37-06:38 with 「允许后台耗电」 on);
 * exact or allow-while-idle alarms are not delivered while frozen, an alarm-clock alarm
 * (`setAlarmClock`) still thaws the process on time. A pending alarm clock also keeps the device out
 * of deep idle (deviceidle `min_time_to_alarm`), so it is used only inside [NIGHT_START]..[NIGHT_END].
 * HyperOS shows no status-bar icon or lock-screen text for a third-party alarm clock while AOSP
 * SystemUI does, hence the brand gate in [isEligibleBrand].
 */
internal object NightAlarmClockWake {
    val NIGHT_START: LocalTime = LocalTime.of(22, 0)
    val NIGHT_END: LocalTime = LocalTime.of(10, 0)

    /** A thaw keeps the process running about 1-3 min, so 2 min bounds the night message delay. */
    const val INTERVAL_MS = 2 * 60 * 1000L

    private const val SHOW_REQUEST_CODE = 10002

    /** Skins with a proven night freeze that an alarm clock thaws: Xiaomi (HyperOS/MIUI) and vivo (OriginOS). */
    fun isEligibleBrand(brand: OemBrand): Boolean = brand == OemBrand.XIAOMI || brand == OemBrand.VIVO

    @VisibleForTesting
    @Volatile
    var isEligibleDevice: () -> Boolean = { isEligibleBrand(OemBrand.current()) }

    @VisibleForTesting
    @Volatile
    var now: () -> LocalTime = { LocalTime.now() }

    fun inNightWindow(time: LocalTime): Boolean = !time.isBefore(NIGHT_START) || time.isBefore(NIGHT_END)

    fun applies(): Boolean = isEligibleDevice() && inNightWindow(now())

    /**
     * Arms [operation] as an alarm clock [INTERVAL_MS] from now. Returns false, arming nothing, when
     * the app has no launcher intent to serve as the alarm's show intent. Throws what
     * [AlarmManager.setAlarmClock] throws (SecurityException without exact-alarm access).
     */
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
