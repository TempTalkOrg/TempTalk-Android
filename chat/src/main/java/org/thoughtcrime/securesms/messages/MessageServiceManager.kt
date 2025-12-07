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
 * MessageForegroundService 管理器
 *
 * 职责：
 * 1. 管理前台Service的启动和停止
 * 2. 管理保活机制（AlarmManager）的注册和取消
 * 3. 检查并恢复Service状态
 *
 * 保活机制生命周期：
 * - startService() -> 注册 AlarmManager，设置 keepAliveEnabled=true
 * - stopService() -> 取消 AlarmManager，设置 keepAliveEnabled=false
 * - onFcmAvailable() -> 取消 AlarmManager，设置 keepAliveEnabled=false
 *
 * 保活触发点：
 * - AlarmManager（自动选择精确/非精确闹钟，进程死亡后能唤醒）
 *   - 有精确闹钟权限：3分钟间隔（非Doze）/~9分钟（Doze，系统延长）
 *   - 无精确闹钟权限：3分钟间隔（非Doze）/30分钟-2小时（Doze，系统维护窗口）
 * - BOOT_COMPLETED（静态注册，系统重启后恢复 AlarmManager 和 Service）
 *
 * 注意：WebSocket 重连由 WebSocketHealthMonitor 管理（网络监听、心跳检测等）
 */
@Singleton
class MessageServiceManager @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val userManager: UserManager,
    private val webSocketHealthMonitor: WebSocketHealthMonitor
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        // 非精确闹钟间隔（setAndAllowWhileIdle）
        // - 非Doze模式：3分钟触发
        // - Doze模式：系统维护窗口触发（初期9-15分钟，深度30分钟-2小时）
        private const val NON_EXACT_ALARM_INTERVAL_MS = 3 * 60 * 1000L // 3分钟

        // 精确闹钟间隔（setExactAndAllowWhileIdle，需要 SCHEDULE_EXACT_ALARM 权限）
        // - 非Doze模式：3分钟触发（及时检查）
        // - Doze模式：系统自动延长到 ~9分钟（符合系统最小限制，不会被延长到几十分钟）
        private const val EXACT_ALARM_INTERVAL_MS = 3 * 60 * 1000L // 3分钟

        private const val ALARM_REQUEST_CODE = 10001
    }

    /**
     * 启动 Service
     *
     * 流程：
     * 1. 设置 keepAliveEnabled=true（服务层状态）
     * 2. 注册 AlarmManager 保活机制
     * 3. 启动 Service
     **/
    fun startService() {
        // 1. 启用保活机制（只修改服务层状态）
        userManager.update { keepAliveEnabled = true }

        // 2. 注册保活机制（AlarmManager）
        scheduleAlarmCheck()
        L.i { "[MessageService] Keep-alive mechanism registered" }

        // 3. 使用降级策略启动 Service
        doStartService()
    }

    /**
     * 停止 Service
     *
     * 流程：
     * 1. 停止 Service
     * 2. 设置 keepAliveEnabled=false（服务层状态）
     * 3. 取消 AlarmManager 保活机制
     **/
    fun stopService() {
        // 1. 停止Service
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)

        // 2. 禁用保活机制（只修改服务层状态）
        userManager.update { keepAliveEnabled = false }

        // 3. 取消保活机制
        cancelAlarmCheck()
        L.i { "[MessageService] Stopped, keep-alive mechanism cancelled" }
    }

    /**
     * FCM可用时自动停止
     *
     * 流程：
     * 1. 停止 Service
     * 2. 设置 keepAliveEnabled=false（不修改 autoStartMessageService，不是用户主动关闭）
     * 3. 取消 AlarmManager 保活机制
     */
    fun onFcmAvailable() {
        L.i { "[MessageService] FCM available, stopping service" }

        // 1. 停止Service
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)

        // 2. 禁用保活机制（不修改 autoStartMessageService，保留用户意图）
        userManager.update { keepAliveEnabled = false }

        // 3. 取消保活机制
        cancelAlarmCheck()
        L.i { "[MessageService] Keep-alive mechanism cancelled due to FCM available" }
    }

    /**
     * 核心检查和恢复逻辑
     *
     * 由 AlarmManager 和 BOOT_COMPLETED 触发
     * ✅ 通过 Intent 唤醒或恢复 Service
     *
     * 注意：调用前需要确保 keepAliveEnabled=true（即保活机制已启用）
     *
     * 统一处理所有场景：
     * 1. Service 正常运行：触发 onStartCommand()，无副作用
     * 2. Service 被冻结（Doze模式）：Intent 唤醒 Service，恢复执行
     * 3. Service 被杀死：重新创建并启动 Service
     *
     * startService() 的行为：
     * - Service 存在：只触发 onStartCommand()，不会重新创建
     * - Service 不存在：创建新实例并启动
     */
    fun checkAndRecover() {
        // 记录当前状态用于日志分析
        val wasRunning = MessageForegroundService.isRunning

        if (!wasRunning) {
            L.w { "[MessageService] Service not running, recovering..." }
        } else {
            L.i { "[MessageService] Service running, sending wakeup/check intent (may be frozen in Doze)" }
        }

        // 统一调用启动逻辑（包含降级重试策略）
        // - 如果 Service 正常运行或被冻结：成功触发 onStartCommand()，无异常
        // - 如果 Service 被杀死：重新创建，失败时协程异步重试
        doStartService()

        // ✅ 通知 WebSocket 健康监控器：Alarm 已触发
        // 作用：
        // 1. 重置重试次数，下次重连立即执行（无 backoff delay）
        // 2. 如果当前正在 backoff 等待中，立即打断并触发重连
        webSocketHealthMonitor.onAlarmTriggered()
    }

    /**
     * 核心启动逻辑（降级策略）
     *
     * 第一步：尝试直接启动
     * 第二步：失败后协程异步重试（避免阻塞主线程）
     *
     * 注意：捕获所有异常，确保不会因为启动失败导致崩溃
     * 可能的异常：UnableToStartException, SecurityException, IllegalStateException, IllegalArgumentException 等
     */
    private fun doStartService() {
        val intent = Intent(context, MessageForegroundService::class.java)

        try {
            // 第一步：尝试直接启动
            ForegroundServiceUtil.start(context, intent)
            L.i { "[MessageService] Service started successfully" }
        } catch (e: Exception) {
            // 启动失败，统一处理所有异常
            // 可能的异常：UnableToStartException, SecurityException, IllegalStateException 等
            L.w { "[MessageService] Direct start failed: ${e.stackTraceToString()}, deferring to background coroutine" }

            // 在后台协程重试（避免阻塞主线程）
            appScope.launch {
                try {
                    ForegroundServiceUtil.startWhenCapable(context, intent)
                    L.i { "[MessageService] Service started after background retry" }
                } catch (e: Exception) {
                    // 后台重试也失败，记录日志（保活机制会继续尝试）
                    L.e { "[MessageService] Unable to start service even after background retry: ${e.stackTraceToString()}" }
                }
            }
        }
    }

    /**
     * 调度定时检查（常驻）
     *
     * 策略：
     * - 有精确闹钟权限：使用 setExactAndAllowWhileIdle（3分钟间隔，Doze下系统延长到~9分钟）
     * - 无精确闹钟权限：使用 setAndAllowWhileIdle（3分钟间隔，Doze下延长到30分钟-2小时）
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
            // 有权限：使用精确闹钟
            // - Android 12 以下：不需要权限，直接使用
            // - Android 12+：需要 SCHEDULE_EXACT_ALARM 权限
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            L.i { "[MessageService] Exact alarm scheduled (3 min, Doze extends to ~9 min)" }
        } else {
            // 无权限：使用非精确闹钟（仅 Android 12+ 且用户未授权）
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            L.i { "[MessageService] Non-exact alarm scheduled (3 min, Doze may extend to 30min-2hr)" }
        }
    }

    /**
     * 检查是否有精确闹钟权限
     *
     * Android 12 (API 31) 开始需要 SCHEDULE_EXACT_ALARM 权限
     */
    private fun hasExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            // Android 12 以下不需要权限
            true
        }
    }

    /**
     * 取消定时检查
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
     * 检查后台连接的前置条件是否满足
     *
     * 检查项：
     * 1. 后台限制（Android 9+）- 硬性阻止，无法启动后台服务
     * 2. 电池优化（Android 6+）- Doze 模式下服务被冻结
     * 3. 后台数据（Android 7+）- WebSocket 无法连接，服务虽然活着但收不到消息
     *
     * @return true 表示条件满足，可以启动服务
     */
    fun checkBackgroundConnectionRequirements(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. 检查后台限制（Android 9+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val isBackgroundRestricted = DeviceProperties.isBackgroundRestricted(context)
            if (isBackgroundRestricted) {
                L.w { "[MessageService] Background restricted" }
                return false
            }
        }

        // 2. 检查电池优化（Android 6+）
        val isIgnoringBatteryOpt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        if (!isIgnoringBatteryOpt) {
            L.w { "[MessageService] Battery optimization not ignored" }
            return false
        }

        // 3. 检查后台数据（Android 7+）
        val dataSaverState = DeviceProperties.getDataSaverState(context)
        if (dataSaverState.isRestricted) {
            L.w { "[MessageService] Background data restricted" }
            return false
        }

        // 4. 检查国产rom自启管理，没有标准api，所以无法获取准确信息

        L.i { "[MessageService] All background connection requirements met" }
        return true
    }
}
