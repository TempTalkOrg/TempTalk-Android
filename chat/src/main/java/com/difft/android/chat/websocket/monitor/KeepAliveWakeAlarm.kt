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



























@Singleton
class KeepAliveWakeAlarm @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: UserManager,
) {
    private val alarmManager: AlarmManager? by lazy { context.getSystemService(AlarmManager::class.java) }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    
    private var armedListener: AlarmManager.OnAlarmListener? = null

    
    private var deadlineListener: AlarmManager.OnAlarmListener? = null

    
    private var deadlineArmedAt = 0L

    
    fun isEnabled(): Boolean = userManager.getUserData()?.keepAliveEnabled == true

    




    @Synchronized
    fun arm(onFire: () -> Unit): Boolean {
        cancelLocked()
        val listener = setLocked(CADENCE_MS, TAG, onFire) ?: return false
        armedListener = listener
        return true
    }

    







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

    
    @Synchronized
    fun cancelResponseDeadline() {
        cancelDeadlineLocked()
    }

    
    @Synchronized
    fun cancelWake() {
        cancelLocked()
    }

    
    @Synchronized
    fun cancel() {
        cancelLocked()
        cancelDeadlineLocked()
    }

    private fun setLocked(delayMs: Long, tag: String, onFire: () -> Unit): AlarmManager.OnAlarmListener? {
        if (!isEnabled()) return null
        val manager = alarmManager ?: return null
        
        
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
        






        const val CADENCE_MS = 60_000L

        




        const val RESPONSE_DEADLINE_MS = 30_000L

        private const val TAG = "TempTalk:ws-keep-alive"
        private const val DEADLINE_TAG = "TempTalk:ws-keep-alive-deadline"
    }
}
