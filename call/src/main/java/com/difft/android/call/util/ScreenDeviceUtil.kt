package com.difft.android.call.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ScreenDeviceUtil {

    private const val WAKE_LOCK_TIMEOUT_MS = 3000L

    fun isScreenLocked(context: Context): Boolean {
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        // 检查设备是否处于锁定状态
        val isLocked = keyguardManager.isKeyguardLocked
        // 检查设备是否处于安全锁定状态，即需要PIN码、图案或密码才能解锁
        val isSecureLocked = keyguardManager.isKeyguardSecure
        L.d { "[Call] ScreenUtil isScreenLocked: isLocked=$isLocked, isSecureLocked=$isSecureLocked" }
        return isLocked
    }

    fun wakeUpDevice() {
        // 避免在主线程上执行 Binder 调用导致 ANR
        appScope.launch(Dispatchers.IO) {
            wakeUpDeviceInternal()
        }
    }

    private fun wakeUpDeviceInternal() {
        try {
            val context = application.applicationContext
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isInteractive) {
                L.d { "[Call] ScreenUtil wakeUpDevice (async)" }
                // if screen is not already on, turn it on (get wake_lock)
                val wl: PowerManager.WakeLock = powerManager.newWakeLock(
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE or
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "id:wakeUpDevice"
                )
                wl.setReferenceCounted(false)
                wl.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            L.e(e) { "[Call] ScreenUtil wakeUpDevice failed: ${e.message}" }
        }
    }
}