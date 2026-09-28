package com.difft.android.call.manager

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ProximityNearRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether the call screen may hold the proximity screen-off lock right now.
 *
 * Same rule as Signal (`LockManager.IN_CALL` vs `IN_HANDS_FREE_CALL` / `IN_VIDEO`) and Telegram
 * (`VoIPService.onSensorChanged`): earpiece route only, no local camera, no screen share. On
 * speaker, wired or Bluetooth the phone is not at the ear, and a dark screen there is exactly the
 * state a user cannot tap their way out of. An unknown route (nothing enumerated yet) counts as
 * "not earpiece" — a few hundred ms without protection beats a dark screen on a wrong guess.
 */
internal fun shouldHoldProximityLock(
    route: AudioRouteSnapshot,
    cameraEnabled: Boolean,
    screenSharing: Boolean,
): Boolean = !cameraEnabled && !screenSharing && route.settledRoute?.kind == AudioDeviceKind.EARPIECE

/**
 * Turns the call screen off while the phone is at the ear by holding
 * [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK] — the mechanism the system dialer, Signal and
 * Telegram use.
 *
 * Why the system lock instead of reading the sensor and dimming the window ourselves:
 * - The system applies the OEM's own threshold (`min(maximumRange, 5cm)` in AOSP
 *   `DisplayPowerProximityStateController`), so this app behaves exactly like the phone's dialer
 *   on the same hardware. The in-app rule `value < maximumRange` is permanently NEAR on phones
 *   whose virtual (ultrasonic) proximity sensor never reports its advertised maximum, which left
 *   the screen dark and untouchable for the whole call.
 * - Proximity-positive keeps the device AWAKE (`PowerManagerService.isBeingKeptAwakeLocked`): no
 *   keyguard, no `ACTION_SCREEN_OFF`, the Activity stays RESUMED.
 * - Pressing the power key while covered turns the screen back on
 *   (`PowerManagerService.interceptPowerKeyDown`) — an escape hatch a window-flag dim never had.
 *
 * The lock is held only while the lifecycle is at least STARTED (call UI visible, including
 * picture-in-picture — Signal and Telegram hold it for the whole call, the system dialer only lets
 * go when the phone lies flat) and [shouldHold] is true. It is released
 * with [PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY] so the screen does not light up against
 * the user's face when the call ends or the route changes. While held, the sensor is also read
 * for diagnostics only (see [ProximityDiagnostics]); it never drives the screen.
 */
class ProximityScreenOffController(
    context: Context,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val shouldHold: Flow<Boolean>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext
    private var job: Job? = null

    /** Start observing; safe to call once per Activity instance, from the main thread. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            // isWakeLockLevelSupported is a Binder call — keep lock setup off the main thread.
            val lock = withContext(ioDispatcher) { createLock() } ?: return@launch
            val diagnostics = ProximityDiagnostics(appContext)
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    shouldHold.distinctUntilChanged().collect { hold ->
                        withContext(ioDispatcher) {
                            if (hold) {
                                acquire(lock)
                                diagnostics.start()
                            } else {
                                release(lock)
                                diagnostics.stop()
                            }
                        }
                    }
                } finally {
                    // Leaving STARTED (call UI fully hidden, destroy): drop the lock so a call UI
                    // that is no longer visible cannot keep the screen off. The wait-for-far flag
                    // still applies: if the phone is at the ear right now the screen lights up once
                    // it is moved away, not against the face (power key overrides either way).
                    withContext(NonCancellable + ioDispatcher) {
                        release(lock)
                        diagnostics.stop()
                    }
                }
            }
        }
    }

    /** Stop observing and release the lock. Called from Activity teardown. */
    fun release() {
        job?.cancel()
        job = null
    }

    private fun createLock(): PowerManager.WakeLock? {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            L.w { "[Call] ProximityScreenOff: PowerManager unavailable" }
            return null
        }
        return try {
            if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                L.i { "[Call] ProximityScreenOff: proximity wake lock not supported on this device" }
                null
            } else {
                powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { setReferenceCounted(false) }
                    .also { L.i { "[Call] ProximityScreenOff: proximity wake lock created" } }
            }
        } catch (e: Exception) {
            L.e { "[Call] ProximityScreenOff: creating wake lock failed: ${e.stackTraceToString()}" }
            null
        }
    }

    private fun acquire(lock: PowerManager.WakeLock) {
        if (lock.isHeld) return
        try {
            lock.acquire()
            L.i { "[Call] ProximityScreenOff: lock acquired" }
        } catch (e: Exception) {
            L.e { "[Call] ProximityScreenOff: acquire failed: ${e.stackTraceToString()}" }
        }
    }

    private fun release(lock: PowerManager.WakeLock) {
        if (!lock.isHeld) return
        try {
            lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            L.i { "[Call] ProximityScreenOff: lock released (wait for no proximity)" }
        } catch (e: Exception) {
            L.e { "[Call] ProximityScreenOff: release failed: ${e.stackTraceToString()}" }
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "quicall:call-proximity"
    }
}

/**
 * Field diagnostics only: logs the raw proximity reading and the near/far verdict the system
 * would reach (AOSP threshold `min(maximumRange, 5cm)`) on the first sample and on each
 * transition. Lets a device log confirm or rule out a misbehaving sensor. Must be started and
 * stopped from the same dispatcher; sensor callbacks arrive on the main looper.
 */
private class ProximityDiagnostics(private val context: Context) : SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var registered = false

    @Volatile
    private var lastNear: Boolean? = null

    fun start() {
        if (registered) return
        val manager = sensorManager
            ?: (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also { sensorManager = it }
            ?: return
        val proximity = sensor
            ?: manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.also { sensor = it }
            ?: run {
                L.i { "[Call] ProximityScreenOff: no proximity sensor for diagnostics" }
                return
            }
        lastNear = null
        registered = try {
            manager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: Exception) {
            L.w { "[Call] ProximityScreenOff: diagnostics register failed: ${e.message}" }
            false
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            L.w { "[Call] ProximityScreenOff: diagnostics unregister failed: ${e.message}" }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull() ?: return
        val maxRange = event.sensor.maximumRange
        val near = ProximityNearRule.isNear(value, maxRange)
        if (near == lastNear) return
        lastNear = near
        L.i { "[Call] ProximityScreenOff: sensor near=$near value=$value maxRange=$maxRange" }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
