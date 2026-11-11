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











object ProximitySensorManager : SensorEventListener {
    private const val DEBOUNCE_DELAY = 300L

    private val registrationMutex = Mutex()

    
    private var sensorManager: SensorManager? = null

    
    private var registered = false

    
    @Volatile
    private var desiredRegistered = false

    






    @Volatile
    private var restartPending = false

    
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

    




    private fun unregisterSensor() {
        try {
            sensorManager?.unregisterListener(this) 
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
        
        
        if (!desiredRegistered) return
        val value = event.values[0]
        val maxRange = event.sensor.maximumRange
        val isNearNow = ProximityNearRule.isNear(value, maxRange)
        if (isNearNow == isNear) return

        val wasUnknown = isNear == null
        isNear = isNearNow
        switchJob?.cancel()
        switchJob = appScope.launch {
            
            
            if (!wasUnknown) delay(DEBOUNCE_DELAY)
            if (!desiredRegistered) return@launch 
            L.i { "[ProximitySensorManager] proximity applied isNear=$isNearNow value=$value maxRange=$maxRange" }
            deviceChangeListener?.onAudioDeviceChanged(isNearNow)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        
    }
}
