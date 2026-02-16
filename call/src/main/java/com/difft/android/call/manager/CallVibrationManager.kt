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

/**
 * 通话震动管理器
 * 负责管理通话相关的震动功能
 * 
 * 主要职责：
 * - 管理来电时的震动提醒
 * - 提供自定义震动模式
 * - 管理震动资源的生命周期
 * - 提供线程安全的震动操作
 */
@Singleton
class CallVibrationManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val vibrator: Vibrator by lazy {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /**
     * 获取 Vibrator 服务实例
     * 用于需要自定义震动模式的场景
     * 
     * @return Vibrator 实例
     */
    fun getVibratorService(): Vibrator {
        return vibrator
    }

    /**
     * 开始来电震动提醒
     * 使用标准模式：0ms 延迟，1000ms 震动，1000ms 间隔（循环）
     */
    @SuppressLint("MissingPermission")
    fun startVibration() {
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
        } catch (e: Exception) {
            L.e { "[Call] CallVibrationManager start Vibration fail: ${e.stackTraceToString()}" }
        }
    }

    /**
     * 停止震动
     * 取消所有正在进行的震动
     */
    fun stopVibration() {
        try {
            vibrator.cancel()
        } catch (e: Exception) {
            L.e { "[Call] CallVibrationManager stop Vibration fail: ${e.message}" }
        }
    }

    /**
     * 执行单次短震动
     * 用于用户交互反馈（如点击按钮）
     * 
     * @param durationMillis 震动持续时间（毫秒），默认 200ms
     * @param amplitude 震动强度（0-255），默认 200
     */
    @SuppressLint("MissingPermission")
    fun vibrateOnce(durationMillis: Long = 200L, amplitude: Int = 200) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val pattern = longArrayOf(durationMillis)
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            L.e { "[Call] CallVibrationManager vibrateOnce fail: ${e.message}" }
        }
    }

    /**
     * 执行自定义震动模式
     * 
     * @param pattern 震动模式数组，格式：[延迟, 震动时长, 间隔, 震动时长, 间隔, ...]
     * @param repeat 重复索引，-1 表示不重复，0 表示从索引 0 开始重复
     */
    @SuppressLint("MissingPermission")
    fun vibratePattern(pattern: LongArray, repeat: Int = -1) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, repeat)
            }
        } catch (e: Exception) {
            L.e { "[Call] CallVibrationManager vibratePattern fail: ${e.message}" }
        }
    }
}

