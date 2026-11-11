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

    






    data class ScreenState(val interactive: Boolean, val keyguardLocked: Boolean) {
        val wokenByCall: Boolean get() = !interactive || keyguardLocked
    }

    



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
        val isLocked = keyguardManager.isKeyguardLocked
        val isSecureLocked = keyguardManager.isKeyguardSecure
        L.d { "[Call] ScreenUtil isScreenLocked: isLocked=$isLocked, isSecureLocked=$isSecureLocked" }
        return isLocked
    }

    fun wakeUpDevice() {
        
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
