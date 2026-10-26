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
 * CPU is suspended (screen off, no wakelock held), so in the background the uptime cadence never
 * elapses and the server closes the idle socket; the close wakes the process, which reconnects
 * and goes back to sleep, once per idle timeout all day. A one-shot
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
 *
 * It also owns the keep-alive response deadline: in keep-alive mode it replaces the monitor's
 * stale-connection probe (kept for FCM mode), whose timeout runs on the uptime clock and stalls
 * through suspend, so a socket dropped silently by a NAT would otherwise be noticed only after
 * several cadences. The deadline alarm is cancelled by the response, so a
 * healthy connection never fires it and it costs no extra wake-up.
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

    // Guarded by synchronized(this).
    private var deadlineListener: AlarmManager.OnAlarmListener? = null

    // Guarded by synchronized(this). elapsedRealtime the pending deadline was armed at.
    private var deadlineArmedAt = 0L

    /** True when the user-enabled background connection (keep-alive mode) is active. */
    fun isEnabled(): Boolean = userManager.getUserData()?.keepAliveEnabled == true

    /**
     * Arms a one-shot wake [CADENCE_MS] from now, replacing any pending one; [onFire] runs on the
     * main thread. Returns false when keep-alive mode is off or the alarm could not be set, so
     * the caller knows the wait is covered by the uptime timeout alone.
     */
    @Synchronized
    fun arm(onFire: () -> Unit): Boolean {
        cancelLocked()
        val listener = setLocked(CADENCE_MS, TAG, onFire) ?: return false
        armedListener = listener
        return true
    }

    /**
     * Arms a one-shot [RESPONSE_DEADLINE_MS] wake for the keep-alive just sent; [onFire] runs on
     * the main thread. A deadline still pending is kept rather than pushed back, so a burst of
     * sends cannot postpone the verdict on the earliest unanswered one; a pending deadline already
     * past due (an alarm a vendor swallowed) is replaced. Cancel it with
     * [cancelResponseDeadline] when the response arrives. Returns false in FCM mode or when the
     * alarm could not be set.
     */
    @Synchronized
    fun armResponseDeadline(onFire: () -> Unit): Boolean {
        if (deadlineListener != null) {
            if (SystemClock.elapsedRealtime() - deadlineArmedAt <= RESPONSE_DEADLINE_MS * 2) return true
            cancelDeadlineLocked()
        }
        var self: AlarmManager.OnAlarmListener? = null
        val listener = setLocked(RESPONSE_DEADLINE_MS, DEADLINE_TAG) {
            synchronized(this) { if (deadlineListener === self) deadlineListener = null }
            onFire()
        } ?: return false
        self = listener
        deadlineListener = listener
        deadlineArmedAt = SystemClock.elapsedRealtime()
        return true
    }

    /** Disarms the pending response deadline, if any. */
    @Synchronized
    fun cancelResponseDeadline() {
        cancelDeadlineLocked()
    }

    /** Disarms the pending cadence wake, if any, leaving the response deadline armed. */
    @Synchronized
    fun cancelWake() {
        cancelLocked()
    }

    /** Disarms both the pending cadence wake and the response deadline. */
    @Synchronized
    fun cancel() {
        cancelLocked()
        cancelDeadlineLocked()
    }

    private fun setLocked(delayMs: Long, tag: String, onFire: () -> Unit): AlarmManager.OnAlarmListener? {
        if (!isEnabled()) return null
        val manager = alarmManager ?: return null
        // Cancelling an already-fired one-shot is a no-op, so the fired listener may stay
        // referenced until the next arm or cancel.
        val listener = AlarmManager.OnAlarmListener { onFire() }
        return try {
            manager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                tag,
                listener,
                handler,
            )
            listener
        } catch (e: Exception) {
            L.w { "[ws]monitor: keep-alive alarm arm failed tag=$tag: ${e.message}" }
            null
        }
    }

    private fun cancelLocked() {
        val listener = armedListener ?: return
        armedListener = null
        runCatching { alarmManager?.cancel(listener) }
    }

    private fun cancelDeadlineLocked() {
        val listener = deadlineListener ?: return
        deadlineListener = null
        runCatching { alarmManager?.cancel(listener) }
    }

    companion object {
        /**
         * Background send cadence. Must stay under the server's 90 s WebSocket idle timeout
         * (Jetty; every proxy layer in front of it must allow more) with margin for alarm
         * delivery and the send itself; 60 s leaves 30 s, the same ratio as Signal. Should the
         * server timeout ever exceed the 5-minute `ServiceCheckReceiver` interval, that chain
         * alone suffices and this wake can be retired.
         */
        const val CADENCE_MS = 60_000L

        /**
         * How long a background keep-alive may go unanswered before the socket is treated as
         * dead: generous enough for a slow cellular round-trip, so worst-case detection is about
         * one cadence plus this (90 s), still under Signal-Android's two background cadences.
         */
        const val RESPONSE_DEADLINE_MS = 30_000L

        private const val TAG = "TempTalk:ws-keep-alive"
        private const val DEADLINE_TAG = "TempTalk:ws-keep-alive-deadline"
    }
}
