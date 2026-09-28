package com.difft.android.chat.widget

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ProximityNearRule
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.chat.util.ServiceUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Proximity sensor source for voice-message playback. Reports near/far only.
 *
 * Must never write process-global audio state (AudioManager.mode, setCommunicationDevice,
 * isSpeakerphoneOn, startBluetoothSco): the call stack owns that state exclusively, and a second
 * writer on the same framework client silently tears down in-call Bluetooth routing. Output
 * selection for the voice-message player is player-scoped and lives in AudioMessageManager.
 * A future caller needing the global route changed must hand the request to the call module's
 * single owner instead of reintroducing a second writer here.
 *
 * Registration runs on `appScope` (IO), never on the caller's thread: `getSystemService(SENSOR_SERVICE)`
 * enumerates sensors natively on first use (hundreds of ms on some devices) and `registerListener`
 * is a Binder call — the call module's sensor manager collected four main-thread ANR fixes for
 * exactly this. [start]/[stop] only record the desired state; [syncRegistration] serialises the
 * actual work behind a mutex, so a stop that overtakes a start cannot leave a stale registration.
 * Sensor callbacks still arrive on the main looper (the no-Handler `registerListener` overload).
 */
object ProximitySensorManager : SensorEventListener {
    private const val DEBOUNCE_DELAY = 300L

    private val registrationMutex = Mutex()

    /** Guarded by [registrationMutex]. */
    private var sensorManager: SensorManager? = null

    /** Guarded by [registrationMutex]. */
    private var registered = false

    /** Last [start]/[stop] request; the sync applies whichever was requested most recently. */
    @Volatile
    private var desiredRegistered = false

    /**
     * Set by every [start]: a start while already registered must still cycle the registration.
     * Re-registering makes the framework redeliver the sensor's current value, which is how a new
     * playback session (auto-play of the next message, resume) gets its route applied even though
     * the phone never moved. A stop that only flips [desiredRegistered] and is immediately followed
     * by a start would otherwise leave both syncs as no-ops and the cached [isNear] stale.
     */
    @Volatile
    private var restartPending = false

    /** null = unknown; the first sensor event after (re)start always propagates. */
    @Volatile
    private var isNear: Boolean? = null

    @Volatile
    private var switchJob: Job? = null

    interface AudioDeviceChangeListener {
        fun onAudioDeviceChanged(isNear: Boolean)
    }

    private var deviceChangeListener: AudioDeviceChangeListener? = null

    fun setAudioDeviceChangeListener(listener: AudioDeviceChangeListener) {
        deviceChangeListener = listener
    }

    /** (Re)start: never leaves a second registration behind, always redelivers the current value. */
    fun start() {
        desiredRegistered = true
        restartPending = true
        appScope.launch { syncRegistration() }
    }

    fun stop() {
        desiredRegistered = false
        appScope.launch { syncRegistration() }
    }

    private suspend fun syncRegistration() = registrationMutex.withLock {
        val wanted = desiredRegistered
        when {
            !wanted && registered -> unregisterSensor()
            wanted && (!registered || restartPending) -> {
                restartPending = false
                if (registered) unregisterSensor()
                registerSensor()
            }
        }
    }

    private fun registerSensor() {
        val manager = ServiceUtil.getSensorManager(application)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (sensor == null) {
            L.w { "[ProximitySensorManager] no proximity sensor, keeping default route" }
            return
        }
        resetProximityState()
        registered = try {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: Exception) {
            L.e { "[ProximitySensorManager] register failed: ${e.stackTraceToString()}" }
            false
        }
        if (registered) {
            sensorManager = manager
            L.i { "[ProximitySensorManager] started maxRange=${sensor.maximumRange}" }
        }
    }

    /**
     * Drops the sensor registration, the pending debounce job and the cached proximity value.
     * The listener is App-lifetime wiring and must survive: clearing it here would silence every
     * later callback. The manager reference is dropped too so a later start re-resolves it.
     */
    private fun unregisterSensor() {
        try {
            sensorManager?.unregisterListener(this) // all-sensors overload: full, idempotent teardown
        } catch (e: Exception) {
            L.e { "[ProximitySensorManager] unregister failed: ${e.stackTraceToString()}" }
        }
        sensorManager = null
        registered = false
        resetProximityState()
        L.i { "[ProximitySensorManager] stopped" }
    }

    private fun resetProximityState() {
        switchJob?.cancel()
        switchJob = null
        isNear = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        // An event already queued on the main looper can still land after unregisterListener();
        // without this guard it would spawn a debounce job nothing cancels.
        if (!desiredRegistered) return
        val value = event.values[0]
        val maxRange = event.sensor.maximumRange
        val isNearNow = ProximityNearRule.isNear(value, maxRange)
        if (isNearNow == isNear) return

        val wasUnknown = isNear == null
        isNear = isNearNow
        switchJob?.cancel()
        switchJob = appScope.launch {
            // The first value after (re)start applies immediately so playback never runs on a
            // route inherited from the previous session; later flips are debounced.
            if (!wasUnknown) delay(DEBOUNCE_DELAY)
            if (!desiredRegistered) return@launch // stopped during the debounce
            L.i { "[ProximitySensorManager] proximity applied isNear=$isNearNow value=$value maxRange=$maxRange" }
            deviceChangeListener?.onAudioDeviceChanged(isNearNow)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }
}
