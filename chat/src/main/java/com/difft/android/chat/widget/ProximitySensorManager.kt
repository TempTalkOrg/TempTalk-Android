package com.difft.android.chat.widget

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.util.ServiceUtil

object ProximitySensorManager : SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var isNear = false
    private val audioManager: AudioManager by lazy {
        ServiceUtil.getAudioManager(application)
    }
    
    // 添加防抖机制，避免频繁切换
    private var switchJob: Job? = null
    private var initJob: Job? = null
    private const val DEBOUNCE_DELAY = 300L // 300ms防抖延迟
    
    interface AudioDeviceChangeListener {
        fun onAudioDeviceChanged(isNear: Boolean)
    }

    private var deviceChangeListener: AudioDeviceChangeListener? = null

    fun setAudioDeviceChangeListener(listener: AudioDeviceChangeListener) {
        deviceChangeListener = listener
    }

    fun start() {
        sensorManager = ServiceUtil.getSensorManager(application)
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (proximitySensor != null) {
            sensorManager?.registerListener(
                this,
                proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            L.d { "[ProximitySensorManager][AudioMessageManager] Proximity sensor started" }
        } else {
            L.e { "[ProximitySensorManager][AudioMessageManager] No proximity sensor found" }
        }

        switchJob?.cancel()
        
        switchJob = appScope.launch {
            switchAudioDevice()
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        proximitySensor = null
        deviceChangeListener = null
        
        // 取消正在进行的切换操作
        switchJob?.cancel()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = event.sensor.maximumRange
            val isNearNow = distance < maxRange

            if (isNearNow != isNear) {
                isNear = isNearNow
                
                switchJob?.cancel()
                
                switchJob = appScope.launch {
                    delay(DEBOUNCE_DELAY)
                    switchAudioDevice()
                }
                
                deviceChangeListener?.onAudioDeviceChanged(isNear)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    private fun switchAudioDevice() {
        try {
            if (isNear) {
                // 靠近时使用听筒，需要设置为通信模式
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val earpiece = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (earpiece != null) {
                        audioManager.setCommunicationDevice(earpiece)
                        L.d { "[ProximitySensorManager][AudioMessageManager] Switched to earpiece mode" }
                    }
                } else {
                    // 旧版本上使用 speakerphone 开关
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    L.d { "[ProximitySensorManager][AudioMessageManager] Switched to earpiece mode (legacy)" }
                }
            } else {
                // 远离时使用扬声器，恢复正常模式
                audioManager.mode = AudioManager.MODE_NORMAL
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                        L.d { "[ProximitySensorManager][AudioMessageManager] Switched to speaker mode" }
                    }
                } else {
                    // 旧版本上使用 speakerphone 开关
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                    L.d { "[ProximitySensorManager][AudioMessageManager] Switched to speaker mode (legacy)" }
                }
            }
        } catch (e: Exception) {
            L.e { "[ProximitySensorManager][AudioMessageManager] Error switching audio device: ${e.message}" }
        }
    }
} 