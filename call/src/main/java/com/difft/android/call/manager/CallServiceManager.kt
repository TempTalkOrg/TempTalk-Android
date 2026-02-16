package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallToChatController
import com.difft.android.call.service.ForegroundService

/**
 * 统一管理通话前台服务的启动、停止和更新
 * 负责处理 ForegroundService 的生命周期和状态更新
 */
class CallServiceManager(
    private val context: Context,
    private val callToChatController: LCallToChatController
) {

    /**
     * 启动通话前台服务
     * 用于在后台保持通话连接
     */
    fun startOngoingCallService() {
        L.i { "[Call] CallServiceManager startOngoingCallService" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceIntent = Intent(context, ForegroundService::class.java)
            try {
                callToChatController.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                L.e { "[Call] CallServiceManager Failed to start foreground service: ${e.message}" }
            }
        } else {
            val serviceIntent = Intent(context, ForegroundService::class.java)
            context.startService(serviceIntent)
        }
    }

    /**
     * 停止通话前台服务
     * 在通话结束时清理服务资源
     */
    fun stopOngoingCallService() {
        if (!ForegroundService.isServiceRunning) {
            return
        }
        L.i { "[Call] CallServiceManager stopOngoingCallService" }
        try {
            val serviceIntent = Intent(context.applicationContext, ForegroundService::class.java)
            context.applicationContext.stopService(serviceIntent)
        } catch (e: Exception) {
            L.e { "[Call] CallServiceManager Failed to stop ongoing call service: ${e.message}" }
        }
    }

    /**
     * 更新通话通知
     * @param useCallStyle 是否使用通话样式通知（通常在后台时使用）
     */
    fun updateOngoingCallNotification(useCallStyle: Boolean) {
        L.i { "[Call] CallServiceManager updateOngoingCallNotification" }
        if (!ForegroundService.isServiceRunning) {
            L.i { "[Call] CallServiceManager updateOngoingCallNotification - ForegroundService is not running" }
            return
        }
        try {
            val intent = Intent(context, ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_UPDATE_NOTIFICATION
                putExtra(ForegroundService.EXTRA_USE_CALL_STYLE, useCallStyle)
            }
            callToChatController.startForegroundService(context, intent)
        } catch (e: Exception) {
            L.e { "[Call] CallServiceManager Failed to update ongoing call notification: ${e.message}" }
        }
    }

    /**
     * 更新前台服务类型
     * 当权限授予后更新服务类型，确保服务可以在后台使用麦克风/摄像头
     * 仅在 Android R (API 30) 及以上版本有效
     */
    fun updateForegroundServiceType() {
        L.i { "[Call] CallServiceManager updateForegroundServiceType" }
        if (!ForegroundService.isServiceRunning) {
            L.i { "[Call] CallServiceManager updateForegroundServiceType - ForegroundService is not running" }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(context, ForegroundService::class.java).apply {
                    action = ForegroundService.ACTION_UPDATE_SERVICE_TYPE
                }
                callToChatController.startForegroundService(context, intent)
            } catch (e: Exception) {
                L.e { "[Call] CallServiceManager Failed to update foreground service type: ${e.message}" }
            }
        }
    }
}

