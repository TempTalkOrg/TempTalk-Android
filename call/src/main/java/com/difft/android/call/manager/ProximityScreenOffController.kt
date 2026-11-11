package com.difft.android.call.manager

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
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










internal fun shouldHoldProximityLock(
    route: AudioRouteSnapshot,
    cameraEnabled: Boolean,
    screenSharing: Boolean,
): Boolean = !cameraEnabled && !screenSharing && route.settledRoute?.kind == AudioDeviceKind.EARPIECE























class ProximityScreenOffController(
    context: Context,
    private val scope: CoroutineScope,
    private val shouldHold: Flow<Boolean>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext
    private var job: Job? = null

    
    fun start() {
        job?.cancel()
        job = scope.launch {
            
            val lock = withContext(ioDispatcher) { createLock() } ?: return@launch
            val diagnostics = ProximityDiagnostics(appContext)
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
                
                
                
                withContext(NonCancellable + ioDispatcher) {
                    release(lock)
                    diagnostics.stop()
                }
            }
        }
    }

    
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
