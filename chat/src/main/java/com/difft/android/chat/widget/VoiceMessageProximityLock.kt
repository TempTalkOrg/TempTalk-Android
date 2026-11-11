package com.difft.android.chat.widget

import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.chat.util.ServiceUtil
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit




















internal object VoiceMessageProximityLock {
    private const val WAKE_LOCK_TAG = "quicall:voice-message-proximity"

    
    private val ACQUIRE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30)

    private val mutex = Mutex()

    
    private var lock: PowerManager.WakeLock? = null

    
    private var resolved = false

    @Volatile
    private var enabled = false

    @Volatile
    private var desiredHeld = false

    
    fun enable() {
        enabled = true
    }

    
    fun disable() {
        enabled = false
        desiredHeld = false
        appScope.launch { sync() }
    }

    
    fun requestHeld(held: Boolean) {
        desiredHeld = held
        appScope.launch { sync() }
    }

    @VisibleForTesting
    internal suspend fun sync() = mutex.withLock {
        val wanted = enabled && desiredHeld
        if (!wanted && !resolved) return@withLock 
        val wakeLock = resolveLock() ?: return@withLock
        try {
            if (wanted && !wakeLock.isHeld) {
                wakeLock.acquire(ACQUIRE_TIMEOUT_MS)
                L.i { "[VoiceMessageProximityLock] lock acquired" }
            } else if (!wanted && wakeLock.isHeld) {
                wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                L.i { "[VoiceMessageProximityLock] lock released (wait for no proximity)" }
            }
        } catch (e: Exception) {
            L.e { "[VoiceMessageProximityLock] lock ${if (wanted) "acquire" else "release"} failed: ${e.stackTraceToString()}" }
        }
    }

    private fun resolveLock(): PowerManager.WakeLock? {
        if (resolved) return lock
        resolved = true
        lock = try {
            val powerManager = ServiceUtil.getPowerManager(application)
            if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                L.i { "[VoiceMessageProximityLock] wake lock not supported on this device" }
                null
            } else {
                powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { setReferenceCounted(false) }
            }
        } catch (e: Exception) {
            L.e { "[VoiceMessageProximityLock] creating wake lock failed: ${e.stackTraceToString()}" }
            null
        }
        return lock
    }

    
    @VisibleForTesting
    internal fun resetForTest() {
        lock = null
        resolved = false
        enabled = false
        desiredHeld = false
    }
}
