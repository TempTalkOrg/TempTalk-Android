package com.difft.android.chat.widget

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.difft.android.chat.util.ServiceUtil

/**
 * Proximity sensor source for voice-message playback. Reports near/far only.
 *
 * Must never write process-global audio state (AudioManager.mode, setCommunicationDevice,
 * isSpeakerphoneOn, startBluetoothSco): the call stack owns that state exclusively, and a second
 * writer on the same framework client silently tears down in-call Bluetooth routing. Output
 * selection for the voice-message player is player-scoped and lives in AudioMessageManager.
 * A future caller needing the global route changed must hand the request to the call module's
 * single owner instead of reintroducing a second writer here.
 */
object ProximitySensorManager : SensorEventListener {
    private const val DEBOUNCE_DELAY = 300L

    private var sensorManager: SensorManager? = null

    /** null = unknown; the first sensor event after (re)start always propagates. */
    private var isNear: Boolean? = null

    private var switchJob: Job? = null

    interface AudioDeviceChangeListener {
        fun onAudioDeviceChanged(isNear: Boolean)
    }

    private var deviceChangeListener: AudioDeviceChangeListener? = null

    fun setAudioDeviceChangeListener(listener: AudioDeviceChangeListener) {
        deviceChangeListener = listener
    }

    fun start() {
        unregisterSensor() // idempotent: never leaves a second registration behind
        val manager = ServiceUtil.getSensorManager(application)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (sensor == null) {
            L.w { "[ProximitySensorManager] no proximity sensor, keeping default route" }
            return
        }
        sensorManager = manager
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        L.i { "[ProximitySensorManager] started" }
    }

    fun stop() {
        unregisterSensor()
        L.i { "[ProximitySensorManager] stopped" }
    }

    /**
     * Drops the sensor registration, the pending debounce job and the cached proximity value.
     * The listener is App-lifetime wiring and must survive: clearing it here would silence every
     * later callback.
     */
    private fun unregisterSensor() {
        sensorManager?.unregisterListener(this) // all-sensors overload: full, idempotent teardown
        sensorManager = null
        switchJob?.cancel()
        switchJob = null
        isNear = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val isNearNow = event.values[0] < event.sensor.maximumRange
        if (isNearNow == isNear) return

        val wasUnknown = isNear == null
        isNear = isNearNow
        switchJob?.cancel()
        switchJob = appScope.launch {
            // The first value after (re)start applies immediately so playback never runs on a
            // route inherited from the previous session; later flips are debounced.
            if (!wasUnknown) delay(DEBOUNCE_DELAY)
            L.i { "[ProximitySensorManager] proximity applied isNear=$isNearNow" }
            deviceChangeListener?.onAudioDeviceChanged(isNearNow)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }
}
