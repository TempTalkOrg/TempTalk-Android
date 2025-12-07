package org.thoughtcrime.securesms.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 定时检查广播接收器
 *
 * 由 AlarmManager 每 5 分钟触发一次（Doze 模式下系统延长到 9-15 分钟）
 */
@AndroidEntryPoint
class ServiceCheckReceiver : BroadcastReceiver() {

    @Inject
    lateinit var messageServiceManager: MessageServiceManager

    override fun onReceive(context: Context, intent: Intent) {
        L.i { "[ServiceCheck] Alarm triggered" }

        // 使用 goAsync() 保证异步操作完成
        val pendingResult = goAsync()

        // 协程异步执行，避免阻塞主线程
        appScope.launch {
            try {
                // 重新调度下一次闹钟（保持常驻）
                messageServiceManager.scheduleAlarmCheck()

                // 检查并恢复
                messageServiceManager.checkAndRecover()
            } finally {
                // 确保无论成功或失败都调用 finish()
                pendingResult.finish()
            }
        }
    }
}
