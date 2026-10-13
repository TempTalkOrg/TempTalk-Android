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

    /**
     * Display and keyguard state at one instant, read when the ring screen is created.
     *
     * [wokenByCall] is true when the incoming-call screen brought itself up — display off or keyguard
     * up — rather than being opened by a person already using an unlocked phone. Only that path can
     * put live accept/reject controls under a pocket or palm.
     */
    data class ScreenState(val interactive: Boolean, val keyguardLocked: Boolean) {
        val wokenByCall: Boolean get() = !interactive || keyguardLocked
    }

    /**
     * Two Binder getters on the caller's thread. This deliberately differs from [wakeUpDevice],
     * which moved off the main thread because acquiring a wake lock is heavy: `isInteractive` and
     * `isKeyguardLocked` are cheap reads, and the ring screen needs them synchronously in onCreate
     * to pick its controls before the first layout. The other keyguard reader, [isScreenLocked], is
     * no precedent either way: its only callers are LCallToChatControllerImpl's incoming-call
     * restore paths, which already run on Dispatchers.IO and need no synchronous answer.
     */
    fun readScreenState(context: Context): ScreenState {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return ScreenState(
            interactive = powerManager.isInteractive,
            keyguardLocked = keyguardManager.isKeyguardLocked,
        ).also { L.i { "[Call] ScreenUtil screenState interactive=${it.interactive} keyguardLocked=${it.keyguardLocked}" } }
    }

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
            val interactive = powerManager.isInteractive
            L.i { "[Call] ScreenUtil wakeUpDevice interactive=$interactive acquire=${!interactive}" }
            if (!interactive) {
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
