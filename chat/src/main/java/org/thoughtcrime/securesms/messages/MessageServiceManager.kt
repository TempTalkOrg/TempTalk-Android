package org.thoughtcrime.securesms.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.util.DeviceProperties
import org.thoughtcrime.securesms.util.ForegroundServiceUtil
import org.thoughtcrime.securesms.websocket.monitor.WebSocketHealthMonitor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MessageForegroundService Manager
 *
 * Responsibilities:
 * 1. Manage foreground Service start/stop
 * 2. Manage keep-alive mechanism (AlarmManager) registration/cancellation
 * 3. Check and recover Service state
 *
 * Keep-alive lifecycle:
 * - startService() -> Register AlarmManager, set keepAliveEnabled=true
 * - stopService() -> Cancel AlarmManager, set keepAliveEnabled=false
 * - onFcmAvailable() -> Cancel AlarmManager, set keepAliveEnabled=false
 *
 * Keep-alive triggers:
 * - AlarmManager (auto-selects exact/inexact alarm, can wake process after death)
 *   - With exact alarm permission: 5 min interval (non-Doze) / ~15 min (Doze, system extended)
 *   - Without exact alarm permission: 5 min interval (non-Doze) / 30min-2hr (Doze, maintenance window)
 * - BOOT_COMPLETED (static registered, restores AlarmManager and Service after reboot)
 *
 * Note: WebSocket reconnection is managed by WebSocketHealthMonitor (network listener, heartbeat, etc.)
 */
@Singleton
class MessageServiceManager @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val userManager: UserManager,
    private val webSocketHealthMonitor: WebSocketHealthMonitor
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        // Non-exact alarm interval (setAndAllowWhileIdle)
        // - Non-Doze: triggers every 5 min
        // - Doze: triggers in maintenance window (initially 15-25 min, deep 30min-2hr)
        private const val NON_EXACT_ALARM_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        // Exact alarm interval (setExactAndAllowWhileIdle, requires SCHEDULE_EXACT_ALARM permission)
        // - Non-Doze: triggers every 5 min (timely check)
        // - Doze: system extends to ~15 min (meets system minimum, won't extend to hours)
        private const val EXACT_ALARM_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        private const val ALARM_REQUEST_CODE = 10001
    }

    /**
     * Start Service
     *
     * Flow:
     * 1. Set keepAliveEnabled=true (service layer state)
     * 2. Register AlarmManager keep-alive mechanism
     * 3. Start Service
     **/
    fun startService() {
        // 1. Enable keep-alive (only modify service layer state)
        userManager.update { keepAliveEnabled = true }

        // 2. Register keep-alive mechanism (AlarmManager)
        scheduleAlarmCheck()
        L.i { "[MessageService] Keep-alive mechanism registered" }

        // 3. Start Service with fallback strategy
        doStartService()
    }

    /**
     * Stop Service
     *
     * Flow:
     * 1. Stop Service
     * 2. Set keepAliveEnabled=false (service layer state)
     * 3. Cancel AlarmManager keep-alive mechanism
     **/
    fun stopService() {
        // 1. Stop Service
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)

        // 2. Disable keep-alive (only modify service layer state)
        userManager.update { keepAliveEnabled = false }

        // 3. Cancel keep-alive mechanism
        cancelAlarmCheck()
        L.i { "[MessageService] Stopped, keep-alive mechanism cancelled" }
    }

    /**
     * Auto-stop when FCM becomes available
     *
     * Flow:
     * 1. Stop Service
     * 2. Set keepAliveEnabled=false (don't modify autoStartMessageService, not user-initiated)
     * 3. Cancel AlarmManager keep-alive mechanism
     */
    fun onFcmAvailable() {
        L.i { "[MessageService] FCM available, stopping service" }

        // 1. Stop Service
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)

        // 2. Disable keep-alive (don't modify autoStartMessageService, preserve user intent)
        userManager.update { keepAliveEnabled = false }

        // 3. Cancel keep-alive mechanism
        cancelAlarmCheck()
        L.i { "[MessageService] Keep-alive mechanism cancelled due to FCM available" }
    }

    /**
     * Core check and recover logic
     *
     * Triggered by AlarmManager and BOOT_COMPLETED
     * Wakes up or recovers Service via Intent
     *
     * Note: Ensure keepAliveEnabled=true before calling (i.e., keep-alive is enabled)
     *
     * Handles all scenarios uniformly:
     * 1. Service running normally: triggers onStartCommand(), no side effects
     * 2. Service frozen (Doze mode): Intent wakes Service, resumes execution
     * 3. Service killed: recreates and starts Service
     *
     * startService() behavior:
     * - Service exists: only triggers onStartCommand(), won't recreate
     * - Service doesn't exist: creates new instance and starts
     */
    fun checkAndRecover() {
        // Record current state for log analysis
        val wasRunning = MessageForegroundService.isRunning

        if (!wasRunning) {
            L.w { "[MessageService] Service not running, recovering..." }
        } else {
            L.i { "[MessageService] Service running, sending wakeup/check intent (may be frozen in Doze)" }
        }

        // Unified start logic (with fallback retry strategy)
        // - If Service running or frozen: successfully triggers onStartCommand(), no exception
        // - If Service killed: recreates, retries via coroutine on failure
        doStartService()

        // Notify WebSocket health monitor: Alarm triggered
        // Effects:
        // 1. Reset retry count, next reconnect executes immediately (no backoff delay)
        // 2. If currently in backoff wait, interrupt and trigger reconnect
        webSocketHealthMonitor.onAlarmTriggered()
    }

    /**
     * Core start logic (fallback strategy)
     *
     * Step 1: Try direct start
     * Step 2: Retry via coroutine on failure (avoid blocking main thread)
     *
     * Note: Catches all exceptions to prevent crash from start failure
     * Possible exceptions: UnableToStartException, SecurityException, IllegalStateException, IllegalArgumentException, etc.
     */
    private fun doStartService() {
        val intent = Intent(context, MessageForegroundService::class.java)

        try {
            // Step 1: Try direct start
            ForegroundServiceUtil.start(context, intent)
            L.i { "[MessageService] Service started successfully" }
        } catch (e: Exception) {
            // Start failed, handle all exceptions uniformly
            // Possible exceptions: UnableToStartException, SecurityException, IllegalStateException, etc.
            L.w { "[MessageService] Direct start failed: ${e.stackTraceToString()}, deferring to background coroutine" }

            // Retry in background coroutine (avoid blocking main thread)
            appScope.launch {
                try {
                    ForegroundServiceUtil.startWhenCapable(context, intent)
                    L.i { "[MessageService] Service started after background retry" }
                } catch (e: Exception) {
                    // Background retry also failed, log it (keep-alive will continue trying)
                    L.e { "[MessageService] Unable to start service even after background retry: ${e.stackTraceToString()}" }
                }
            }
        }
    }

    /**
     * Schedule periodic check (persistent)
     *
     * Strategy:
     * - With exact alarm permission: use setExactAndAllowWhileIdle (5 min interval, Doze extends to ~15 min)
     * - Without exact alarm permission: use setAndAllowWhileIdle (5 min interval, Doze extends to 30min-2hr)
     */
    fun scheduleAlarmCheck() {
        val intent = Intent(context, ServiceCheckReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel any existing alarm first to ensure clean state
        // (Though FLAG_UPDATE_CURRENT should handle this, explicit cancel is clearer)
        alarmManager.cancel(pendingIntent)

        val hasExactAlarmPermission = hasExactAlarmPermission()
        val interval = if (hasExactAlarmPermission) EXACT_ALARM_INTERVAL_MS else NON_EXACT_ALARM_INTERVAL_MS
        val triggerTime = SystemClock.elapsedRealtime() + interval

        if (hasExactAlarmPermission) {
            // Has permission: use exact alarm
            // - Below Android 12: no permission needed, use directly
            // - Android 12+: requires SCHEDULE_EXACT_ALARM permission
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            L.i { "[MessageService] Exact alarm scheduled (5 min, Doze extends to ~15 min)" }
        } else {
            // No permission: use inexact alarm (only Android 12+ and user not authorized)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            L.i { "[MessageService] Non-exact alarm scheduled (5 min, Doze may extend to 30min-2hr)" }
        }
    }

    /**
     * Check if exact alarm permission is granted
     *
     * Android 12 (API 31)+ requires SCHEDULE_EXACT_ALARM permission
     */
    private fun hasExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            // Below Android 12: no permission needed
            true
        }
    }

    /**
     * Cancel periodic check
     */
    private fun cancelAlarmCheck() {
        val intent = Intent(context, ServiceCheckReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        L.i { "[MessageService] Alarm cancelled" }
    }

    /**
     * Check if background connection prerequisites are met
     *
     * Checks:
     * 1. Background restriction (Android 9+) - hard block, cannot start background service
     * 2. Battery optimization (Android 6+) - service frozen in Doze mode
     * 3. Background data (Android 7+) - WebSocket cannot connect, service alive but no messages
     *
     * @return true if requirements met, service can start
     */
    fun checkBackgroundConnectionRequirements(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. Check background restriction (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val isBackgroundRestricted = DeviceProperties.isBackgroundRestricted(context)
            if (isBackgroundRestricted) {
                L.w { "[MessageService] Background restricted" }
                return false
            }
        }

        // 2. Check battery optimization (Android 6+)
        val isIgnoringBatteryOpt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        if (!isIgnoringBatteryOpt) {
            L.w { "[MessageService] Battery optimization not ignored" }
            return false
        }

        // 3. Check background data (Android 7+)
        val dataSaverState = DeviceProperties.getDataSaverState(context)
        if (dataSaverState.isRestricted) {
            L.w { "[MessageService] Background data restricted" }
            return false
        }

        // 4. Check Chinese ROM auto-start management - no standard API, cannot get accurate info

        L.i { "[MessageService] All background connection requirements met" }
        return true
    }
}
