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

/**
 * Turns the screen off while a voice message is listened to at the ear, by holding
 * [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK] — the same thing Signal
 * (`VoiceNoteProximityWakeLockManager`) and Telegram (`MediaController`) do. At the ear the face
 * rests on the chat screen, where a touch can seek, pause, scroll or open another conversation.
 *
 * Driven by [AudioMessageManager] together with the earpiece route: held exactly while playback
 * is pinned to the built-in earpiece, released when the phone moves away, on pause, completion or
 * player release. The system decides the actual screen state from its own sensor reading; this
 * object only says whether screen-off is allowed right now. Never touches audio state.
 *
 * Threading: callers only record the desired state ([requestHeld], [enable], [disable]) — all
 * synchronous, plain field writes — and the Binder work (`isWakeLockLevelSupported`, `acquire`,
 * `release`) runs in [sync] on `appScope` (IO) behind a mutex that re-reads the LIVE desired
 * state. `appScope` is a multi-threaded pool with no ordering guarantee between two launches, so
 * a captured argument could let a stale "release" overtake a fresh "acquire"; re-reading inside
 * the lock makes every sync converge on whatever was requested last. [enabled] is the session
 * gate: a debounced "near" that lands after stop can no longer acquire.
 */
internal object VoiceMessageProximityLock {
    private const val WAKE_LOCK_TAG = "quicall:voice-message-proximity"

    /** Safety net in case a release path is ever missed; Signal uses the same bound. */
    private val ACQUIRE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30)

    private val mutex = Mutex()

    /** Guarded by [mutex]. */
    private var lock: PowerManager.WakeLock? = null

    /** Guarded by [mutex]. */
    private var resolved = false

    @Volatile
    private var enabled = false

    @Volatile
    private var desiredHeld = false

    /** Playback (re)started: hold requests are honoured from now on. */
    fun enable() {
        enabled = true
    }

    /** Playback paused/stopped: refuse later hold requests and drop the lock. */
    fun disable() {
        enabled = false
        desiredHeld = false
        appScope.launch { sync() }
    }

    /** The route decision changed: hold while listening at the ear, release otherwise. */
    fun requestHeld(held: Boolean) {
        desiredHeld = held
        appScope.launch { sync() }
    }

    @VisibleForTesting
    internal suspend fun sync() = mutex.withLock {
        val wanted = enabled && desiredHeld
        if (!wanted && !resolved) return@withLock // nothing was ever created, nothing to release — no Binder call
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

    /** Robolectric creates a fresh Application per test; drop the lock cached from the previous one. */
    @VisibleForTesting
    internal fun resetForTest() {
        lock = null
        resolved = false
        enabled = false
        desiredHeld = false
    }
}
