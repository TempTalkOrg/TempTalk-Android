package com.difft.android.chat.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.monitor.FgsStartFailure
import com.difft.android.base.monitor.ForegroundServiceFailureRecorder
import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.base.monitor.StallDayTracker
import com.difft.android.base.monitor.StallRule
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import com.difft.android.chat.util.DeviceProperties
import com.difft.android.chat.util.ForegroundServiceUtil
import com.difft.android.chat.util.UnableToStartException
import com.difft.android.chat.websocket.monitor.WebSocketHealthMonitor
import util.AppForegroundObserver
import javax.inject.Inject
import javax.inject.Singleton






















@Singleton
class MessageServiceManager @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val userManager: UserManager,
    private val webSocketHealthMonitor: WebSocketHealthMonitor,
    private val fgsFailureRecorder: ForegroundServiceFailureRecorder,
    private val healthCounters: KeepAliveHealthCounters,
    private val stallDayTracker: StallDayTracker,
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        
        
        
        private const val NON_EXACT_ALARM_INTERVAL_MS = 5 * 60 * 1000L 

        
        
        
        private const val EXACT_ALARM_INTERVAL_MS = 5 * 60 * 1000L 

        
        
        
        
        
        private const val EXACT_ALARM_CHAIN_STALE_MS = 4 * EXACT_ALARM_INTERVAL_MS

        
        
        
        private const val NON_EXACT_ALARM_CHAIN_STALE_MS =
            2 * 60 * 60 * 1000L + NON_EXACT_ALARM_INTERVAL_MS

        private const val ALARM_REQUEST_CODE = 10001
    }

    
    
    @Volatile
    private var lastAlarmChainActivityElapsed: Long = SystemClock.elapsedRealtime()

    
    
    private var stallCountedForActivity: Long? = null

    
    
    
    private var gapBaselineValid = false
    private var lastArmExact = false

    init {
        
        
        
        
        
        appScope.launch {
            webSocketHealthMonitor.monitorWakeTicks.collect {
                runCatching { ensureAlarmChainAlive("monitor-tick") }
                    .onFailure { L.e { "[MessageService] Alarm-chain heal failed: ${it.stackTraceToString()}" } }
            }
        }
        AppForegroundObserver.addListener(object : AppForegroundObserver.Listener {
            override fun onForeground() {
                runCatching { ensureAlarmChainAlive("foreground") }
                    .onFailure { L.e { "[MessageService] Alarm-chain heal failed: ${it.stackTraceToString()}" } }
            }
        })
    }

    






    @Synchronized
    fun ensureAlarmChainAlive(source: String) {
        if (userManager.getUserData()?.keepAliveEnabled != true) return
        val sinceLast = SystemClock.elapsedRealtime() - lastAlarmChainActivityElapsed
        
        
        if (sinceLast <= EXACT_ALARM_CHAIN_STALE_MS) return
        if (!hasExactAlarmPermission() && sinceLast <= NON_EXACT_ALARM_CHAIN_STALE_MS) return
        L.w { "[MessageService] Alarm chain stale (${sinceLast / 1000}s since last activity), re-arming from source=$source" }
        
        if (stallCountedForActivity != lastAlarmChainActivityElapsed) {
            stallCountedForActivity = lastAlarmChainActivityElapsed
            runCatching { healthCounters.onAppStall(sinceLast) }
        }
        scheduleAlarmCheck()
    }

    
    fun startService() {
        setKeepAlive(true)

        scheduleAlarmCheck()
        L.i { "[MessageService] Keep-alive mechanism registered" }

        doStartService()
    }

    
    fun stopService() {
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)

        setKeepAlive(false)

        cancelAlarmCheck()
        L.i { "[MessageService] Stopped, keep-alive mechanism cancelled" }
    }

    
    fun onFcmAvailable() {
        L.i { "[MessageService] FCM available, stopping service" }

        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)

        setKeepAlive(false)

        cancelAlarmCheck()
        L.i { "[MessageService] Keep-alive mechanism cancelled due to FCM available" }
    }

    
    private fun setKeepAlive(enabled: Boolean) {
        val wasEnabled = userManager.getUserData()?.keepAliveEnabled == true
        userManager.update { keepAliveEnabled = enabled }
        
        if (enabled && !wasEnabled) runCatching { stallDayTracker.onKeepAliveEnabled() }
        
        if (enabled) healthCounters.collecting = true
    }

    
    fun checkAndRecover() {
        val wasRunning = MessageForegroundService.isRunning

        if (!wasRunning) {
            L.w { "[MessageService] Service not running, recovering..." }
        } else {
            L.d { "[MessageService] Service running, sending wakeup/check intent (may be frozen in Doze)" }
        }

        
        doStartService()

        
        webSocketHealthMonitor.onAlarmTriggered()

        
        healthCounters.onAlarmTick()
    }

    
    private fun doStartService() {
        val intent = Intent(context, MessageForegroundService::class.java)

        try {
            if (!ForegroundServiceUtil.start(context, intent)) {
                L.w { "[MessageService] start skipped reason=no_channel" }
                fgsFailureRecorder.record(FgsStartFailure.NO_CHANNEL)
                return
            }
            L.d { "[MessageService] Service started successfully" }
        } catch (e: Exception) {
            
            L.w { "[MessageService] Direct start failed: ${e.stackTraceToString()}, deferring to background coroutine" }
            fgsFailureRecorder.record(directFailureType(e))

            appScope.launch {
                try {
                    if (ForegroundServiceUtil.startWhenCapable(context, intent)) {
                        L.i { "[MessageService] Service started after background retry" }
                    } else {
                        L.w { "[MessageService] background retry skipped reason=no_channel" }
                        fgsFailureRecorder.record(FgsStartFailure.NO_CHANNEL)
                    }
                } catch (e: Exception) {
                    
                    L.e { "[MessageService] Unable to start service even after background retry: ${e.stackTraceToString()}" }
                    fgsFailureRecorder.record(FgsStartFailure.RETRY_FAILED)
                }
            }
        }
    }

    
    private fun directFailureType(e: Exception): FgsStartFailure = when {
        e is UnableToStartException -> FgsStartFailure.DIRECT_NOT_ALLOWED
        e is SecurityException -> FgsStartFailure.DIRECT_SECURITY
        else -> FgsStartFailure.DIRECT_OTHER
    }

    







    @Synchronized
    fun scheduleAlarmCheck() {
        val intent = Intent(context, ServiceCheckReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        
        
        
        
        try {
            alarmManager.cancel(pendingIntent)

            val hasExactAlarmPermission = hasExactAlarmPermission()
            var interval = if (hasExactAlarmPermission) EXACT_ALARM_INTERVAL_MS else NON_EXACT_ALARM_INTERVAL_MS
            val triggerTime = SystemClock.elapsedRealtime() + interval
            var armedExact = hasExactAlarmPermission
            var mode = if (hasExactAlarmPermission) "exact" else "inexact"

            if (hasExactAlarmPermission) {
                
                
                
                try {
                    if (NightAlarmClockWake.applies() && armNightAlarmClock(pendingIntent)) {
                        interval = NightAlarmClockWake.INTERVAL_MS
                        mode = "alarm_clock"
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    }
                } catch (e: SecurityException) {
                    
                    
                    L.w { "[MessageService] Exact alarm rejected (permission revoked?), falling back to inexact: ${e.message}" }
                    armedExact = false
                    interval = NON_EXACT_ALARM_INTERVAL_MS
                    mode = "inexact"
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                L.d { "[MessageService] Non-exact alarm scheduled (5 min, Doze may extend to 30min-2hr)" }
            }
            
            L.i { "[MessageService] alarm armed exact=$armedExact mode=$mode intervalMs=$interval" }
            val now = SystemClock.elapsedRealtime()
            recordStallGap(now, armedExact)
            lastAlarmChainActivityElapsed = now
        } catch (e: Exception) {
            L.e { "[MessageService] Failed to arm keep-alive alarm: ${e.stackTraceToString()}" }
        }
    }

    





    private fun recordStallGap(now: Long, armedExact: Boolean) {
        val keepAlive = runCatching { userManager.getUserData()?.keepAliveEnabled == true }.getOrDefault(false)
        val gap = now - lastAlarmChainActivityElapsed
        if (gapBaselineValid && lastArmExact && keepAlive && gap > StallRule.STALL_AFTER_MS) {
            L.w { "[MessageService] stall day recorded gapMin=${gap / 60_000}" }
            runCatching { stallDayTracker.onStall() }
        }
        gapBaselineValid = keepAlive
        lastArmExact = armedExact
    }

    



    private fun armNightAlarmClock(pendingIntent: PendingIntent): Boolean = try {
        NightAlarmClockWake.arm(context, alarmManager, pendingIntent)
    } catch (e: Exception) {
        L.w { "[MessageService] alarm clock rejected, falling back to exact: ${e.javaClass.simpleName} ${e.message}" }
        false
    }

    
    private fun hasExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            
            true
        }
    }

    


    @Synchronized
    private fun cancelAlarmCheck() {
        gapBaselineValid = false
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

    









    fun checkBackgroundConnectionRequirements(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val isBackgroundRestricted = DeviceProperties.isBackgroundRestricted(context)
            if (isBackgroundRestricted) {
                L.w { "[MessageService] Background restricted" }
                return false
            }
        }

        
        val isIgnoringBatteryOpt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        if (!isIgnoringBatteryOpt) {
            L.w { "[MessageService] Battery optimization not ignored" }
            return false
        }

        
        val dataSaverState = DeviceProperties.getDataSaverState(context)
        if (dataSaverState.isRestricted) {
            L.w { "[MessageService] Background data restricted" }
            return false
        }

        

        L.i { "[MessageService] All background connection requirements met" }
        return true
    }
}
