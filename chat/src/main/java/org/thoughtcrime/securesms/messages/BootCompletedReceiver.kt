package org.thoughtcrime.securesms.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 系统启动广播接收器
 *
 * 监听系统重启事件
 * 如果保活机制已启用（keepAliveEnabled=true），则恢复 AlarmManager 和 Service
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var messageServiceManager: MessageServiceManager

    @Inject
    lateinit var userManager: UserManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        L.i { "[BootCompleted] Device rebooted" }

        // 使用 goAsync() 保证异步操作完成
        val pendingResult = goAsync()

        // 协程异步执行，避免阻塞主线程
        appScope.launch {
            try {
                val userData = userManager.getUserData()
                if (userData?.keepAliveEnabled == true) {
                    L.i { "[BootCompleted] Keep-alive enabled, recovering service" }

                    // 重新注册 AlarmManager（系统重启后 AlarmManager 会被清除）
                    messageServiceManager.scheduleAlarmCheck()

                    // 检查并恢复 Service
                    messageServiceManager.checkAndRecover()
                } else {
                    L.d { "[BootCompleted] Keep-alive not enabled, skip recovery" }
                }
            } finally {
                // 确保无论成功或失败都调用 finish()
                pendingResult.finish()
            }
        }
    }
}
