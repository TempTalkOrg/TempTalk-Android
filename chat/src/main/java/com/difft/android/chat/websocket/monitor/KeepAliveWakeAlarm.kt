package com.difft.android.chat.websocket.monitor

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Elapsed-realtime wake source for the WebSocket keep-alive loop (issue #1325).
 *
 * The loop waits with `withTimeout`, which runs on the uptime clock. That clock stops while the
 * CPU is suspended (screen off, no wakelock held), so in the background the 30 s cadence never
 * elapses and the server closes the socket at its 60 s idle timeout; the close wakes the process,
 * which reconnects and goes back to sleep, once a minute all day. A one-shot
 * `ELAPSED_REALTIME_WAKEUP` alarm keeps counting through suspend and wakes the CPU; the send
 * follows within milliseconds on the loop's dispatcher (the same hand-off the 5-minute service
 * alarm already relies on, verified on production logs).
 *
 * Listener alarms are used on purpose: they need no `SCHEDULE_EXACT_ALARM`
 * (`AlarmManagerService.setImpl` skips the permission check for `directReceiver != null`), they
 * inherit `FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED` when the app is exempt from battery optimization,
 * and the platform drops them once the uid is frozen, so a chain can never outlive the process
 * state it was armed in. The 5-minute `ServiceCheckReceiver` chain stays the process-level wake.
 *
 * Armed only while the user has enabled the background connection (`keepAliveEnabled`): in FCM
 * mode the app never opted into periodic wakes and the cached-process freezer ends the loop anyway.
 */
@Singleton
class KeepAliveWakeAlarm @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: UserManager,
) {
    private val alarmManager: AlarmManager? by lazy { context.getSystemService(AlarmManager::class.java) }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // Guarded by synchronized(this).
    private var armedListener: AlarmManager.OnAlarmListener? = null

    /** True when the user-enabled background connection (keep-alive mode) is active. */
    fun isEnabled(): Boolean = userManager.getUserData()?.keepAliveEnabled == true

    /**
     * Arms a one-shot wake [CADENCE_MS] from now, replacing any pending one; [onFire] runs on the
     * main thread. Returns false when keep-alive mode is off or the alarm could not be set, so
     * the caller knows the wait is covered by the uptime timeout alone.
     */
    @Synchronized
    fun arm(onFire: () -> Unit): Boolean {
        if (!isEnabled()) return false
        val manager = alarmManager ?: return false
        cancelLocked()
        // Cancelling an already-fired one-shot is a no-op, so the fired listener may stay
        // referenced until the next arm or cancel.
        val listener = AlarmManager.OnAlarmListener { onFire() }
        return try {
            manager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + CADENCE_MS,
                TAG,
                listener,
                handler,
            )
            armedListener = listener
            true
        } catch (e: Exception) {
            L.w { "[ws]monitor: keep-alive wake alarm arm failed: ${e.message}" }
            false
        }
    }

    /** Disarms the pending wake, if any. */
    @Synchronized
    fun cancel() {
        cancelLocked()
    }

    private fun cancelLocked() {
        val listener = armedListener ?: return
        armedListener = null
        runCatching { alarmManager?.cancel(listener) }
    }

    companion object {
        /**
         * Background send cadence. Must stay under the server's 60 s WebSocket idle timeout with
         * margin for alarm delivery and the send itself; 45 s leaves 15 s. Should the server
         * timeout ever exceed the 5-minute `ServiceCheckReceiver` interval, that chain alone
         * suffices and this wake can be retired.
         */
        const val CADENCE_MS = 45_000L

        private const val TAG = "TempTalk:ws-keep-alive"
    }
}
