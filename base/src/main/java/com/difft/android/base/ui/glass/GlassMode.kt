package com.difft.android.base.ui.glass

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.difft.android.base.BuildConfig
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.SystemProps







object GlassMode {
    @Volatile private var live: Boolean? = null
    @Volatile private var debugOverride: String? = null

    
    @VisibleForTesting
    @Volatile var libraryEffects: Boolean = true

    fun isLive(context: Context): Boolean = live ?: synchronized(this) {
        live ?: decide(context).also { live = it }
    }

    fun isLiquid(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && isLive(context) && override() != OVERRIDE_FROSTED

    private fun override(): String = debugOverride ?: (if (BuildConfig.DEBUG) SystemProps.get(DEBUG_PROPERTY) else "")
        .also { debugOverride = it }

    private fun decide(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val lowRam = activityManager?.isLowRamDevice ?: true
        val forcedOff = override() == OVERRIDE_OFF
        val enabled = !forcedOff && GlassMath.canRenderLive(Build.VERSION.SDK_INT, lowRam, memory.totalMem)
        val reason = when {
            forcedOff -> "debug_off"
            Build.VERSION.SDK_INT < 31 -> "api"
            lowRam -> "low_ram"
            memory.totalMem < GlassTuning.MIN_MEMORY_BYTES -> "memory"
            else -> "supported"
        }
        L.i { "[Glass] mode=${if (enabled) "live" else "fallback"} reason=$reason liquid=${enabled && Build.VERSION.SDK_INT >= 33 && override() != OVERRIDE_FROSTED}" }
        return enabled
    }

    private const val DEBUG_PROPERTY = "debug.tt.glass"
    private const val OVERRIDE_OFF = "off"
    private const val OVERRIDE_FROSTED = "frosted"
}
