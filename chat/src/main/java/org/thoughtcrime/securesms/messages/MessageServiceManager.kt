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
 * - AlarmManager（5分钟间隔，Doze时系统延长到9-15分钟，进程死亡后能唤醒）
 * - BOOT_COMPLETED（静态注册，系统重启后恢复 AlarmManager 和 Service）
 *
 * 注意：WebSocket 重连由 WebSocketHealthMonitor 管理（网络监听、心跳检测等）
 */
@Singleton
class MessageServiceManager @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val userManager: UserManager
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        // 设置5分钟间隔：
        // - 非Doze模式（屏幕亮/充电/移动）：5分钟触发，更及时
        // - Doze模式：系统自动延长到9-15分钟，符合省电策略
        private const val ALARM_INTERVAL_MS = 5 * 60 * 1000L // 5分钟
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
     * ✅ 检查 Service 存活状态
     * 注意：调用前需要确保 keepAliveEnabled=true（即保活机制已启用）
     */
    fun checkAndRecover() {
        // 只检查 Service 存活状态
        if (!MessageForegroundService.isRunning) {
            L.w { "[MessageService] Service not running, trying to recover" }
            tryStartService()
        }
    }

    /**
     * 尝试启动Service（用于自动恢复场景）
     */
    private fun tryStartService() {
        doStartService()
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
     * ✅ 使用 setAndAllowWhileIdle（更省电）
     */
    fun scheduleAlarmCheck() {
        val intent = Intent(context, ServiceCheckReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS

        // 使用 setAndAllowWhileIdle（非精确但更省电）
        // minSdkVersion 24 >= Android M (API 23)，所以这个方法总是可用
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerTime,
            pendingIntent
        )

        L.d { "[MessageService] Alarm scheduled (5 min, Doze mode may extend to 9-15 min)" }
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
