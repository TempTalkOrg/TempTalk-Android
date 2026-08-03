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
     * 震动归属方。系统 Vibrator 是全 App 共享的单一资源，[stopVibration] 底层 vibrator.cancel()
     * 会取消所有震动，无法按来源选择性取消（cancel(usageFilter) 仅 API 34+）。
     * 用归属方标记实现优先级隔离：某一方的停止请求不会取消属于其他方的震动。
     */
    enum class VibrationSource { NONE, CALL, CRITICAL_ALERT }

    private val vibrationLock = Any()

    @Volatile
    private var currentSource: VibrationSource = VibrationSource.NONE

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
     *
     * 紧急联络优先：若当前震动归属紧急联络（[VibrationSource.CRITICAL_ALERT]），则跳过来电震动，
     * 不抢占其正在进行的告警震动，避免来电结束时误停/丢失紧急联络震动。
     */
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

    /**
     * 开始 Critical Alert 循环震动。
     *
     * 与紧急联络铃声一样使用 USAGE_ALARM，使其在静音/勿扰下也能随铃声一起震动，
     * 从而与铃声播放保持同步。
     *
     * @param pattern 震动波形，格式：[延迟, 震动时长, 间隔, ...]，默认 震1s / 停1s
     * @param repeat 循环起点索引，0 表示从头循环，-1 表示不循环
     */
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

    /**
     * 停止震动。
     *
     * 由于系统 Vibrator 全 App 共享，取消操作无法按来源选择，因此按归属方隔离：
     * 仅当当前震动无归属方（[VibrationSource.NONE]）或正属于 [source] 时才真正取消，
     * 避免一方（如来电结束）误停另一方（如仍在进行的紧急联络）的震动。
     *
     * @param source 发起停止请求的归属方，默认 [VibrationSource.CALL]（兼容既有来电调用点）
     */
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
            vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, amplitude))
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
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat))
        } catch (e: Exception) {
            L.e { "[Call] CallVibrationManager vibratePattern fail: ${e.message}" }
        }
    }
}

