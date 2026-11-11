package com.difft.android.call.manager

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallVibrationManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val vibrator: Vibrator by lazy {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    

    enum class VibrationSource { NONE, CALL, CRITICAL_ALERT }

    private val vibrationLock = Any()

    @Volatile
    private var currentSource: VibrationSource = VibrationSource.NONE

    fun getVibratorService(): Vibrator {
        return vibrator
    }

    
    @SuppressLint("MissingPermission")
    fun startVibration() {
        synchronized(vibrationLock) {
            if (currentSource == VibrationSource.CRITICAL_ALERT) {
                L.i { "[Call] CallVibrationManager skip call vibration, critical alert is vibrating" }
                return
            }
            try {
                val pattern: LongArray = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(pattern, 1),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE)
                    )
                } else {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()

                    vibrator.vibrate(pattern, 1, audioAttributes)
                }
                currentSource = VibrationSource.CALL
            } catch (e: Exception) {
                L.e { "[Call] CallVibrationManager start Vibration fail: ${e.stackTraceToString()}" }
            }
        }
    }

    


    @SuppressLint("MissingPermission")
    fun startAlarmVibration(
        pattern: LongArray = longArrayOf(0, 1000, 1000),
        repeat: Int = 0
    ) {
        synchronized(vibrationLock) {
            try {
                if (!vibrator.hasVibrator()) {
                    L.i { "[Call] CallVibrationManager device has no vibrator, skip alarm vibration" }
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(pattern, repeat),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
                    )
                } else {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, repeat, audioAttributes)
                }
                currentSource = VibrationSource.CRITICAL_ALERT
            } catch (e: Exception) {
                L.e { "[Call] CallVibrationManager start alarm vibration fail: ${e.stackTraceToString()}" }
            }
        }
    }

    

    fun stopVibration(source: VibrationSource = VibrationSource.CALL) {
        synchronized(vibrationLock) {
            if (currentSource != VibrationSource.NONE && currentSource != source) {
                L.i { "[Call] CallVibrationManager skip stop by $source, current owner is $currentSource" }
                return
            }
            try {
                vibrator.cancel()
            } catch (e: Exception) {
                L.e { "[Call] CallVibrationManager stop Vibration fail: ${e.message}" }
            } finally {
                currentSource = VibrationSource.NONE
            }
        }
    }

    


    @SuppressLint("MissingPermission")
    fun vibrateOnce(durationMillis: Long = 200L, amplitude: Int = 200) {
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, amplitude))
        } catch (e: Exception) {
            L.e { "[Call] CallVibrationManager vibrateOnce fail: ${e.message}" }
        }
    }

    


    @SuppressLint("MissingPermission")
    fun vibratePattern(pattern: LongArray, repeat: Int = -1) {
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat))
        } catch (e: Exception) {
            L.e { "[Call] CallVibrationManager vibratePattern fail: ${e.message}" }
        }
    }
}

