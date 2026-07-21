package com.difft.android.call.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.R
import com.difft.android.call.state.CriticalAlertStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Critical Alert 的声音与震动播放控制器。
 *
 * 从 [CriticalAlertManager] 中抽离，专注于铃声循环播放、与铃声同步的循环震动，
 * 以及 30s 安全兜底自动停止的生命周期管理。
 *
 * 并发设计：
 * - [soundLock] 只保护状态变更（微秒级），绝不在持锁状态下执行 Ringtone 的 IPC
 * - 所有 Ringtone.play()/stop() 等音频 binder IPC 串行投递到单线程 [ringtoneExecutor]
 * - 通过 CriticalAlertStateManager 的 currentPlayToken 防止陈旧的 play 覆盖新的告警
 */
@Singleton
class CriticalAlertSoundPlayer @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val criticalAlertStateManager: CriticalAlertStateManager,
    private val callVibrationManager: CallVibrationManager,
) {
    // JVM 锁替代协程 Mutex，使 stop 操作可同步调用而无需 launch 协程。
    // 严格只用于状态变更（微秒级），永不在持锁状态下执行 Ringtone 的 IPC。
    private val soundLock = Any()

    // 循环重播任务会在 executor 线程读取该引用判断铃声是否已被取代，故用 @Volatile 保证可见性
    @Volatile
    private var currentRingtone: Ringtone? = null

    // 告警（铃声 + 震动）30s 自动停止任务，防止后台无交互时无限播放/震动
    @Volatile
    private var alertAutoStopJob: Job? = null

    // 铃声重播循环任务（仅 API < 28 使用，因 Ringtone.isLooping 需要 API 28+）
    @Volatile
    private var ringtoneLoopJob: Job? = null

    // 循环震动波形：延迟0ms → 震动1000ms → 停顿1000ms，循环播放
    private val vibrationPattern = longArrayOf(0, 1000, 1000)

    // API < 28 铃声重播循环的轮询间隔
    private val ringtoneLoopCheckInterval = 300L

    /**
     * 专用单线程 executor，串行执行 Ringtone.play() / stop() 这类音频 binder IPC。
     *
     * - 使用普通 Thread（非协程池），避免主线程 dispatch 时触发
     *   CoroutineScheduler.tryUnpark → Object.notifyAll，复现原 ANR
     * - 单线程保证 play/stop 提交顺序即执行顺序，避免 stop 先于 play 处理导致铃声"漏停"
     * - 调用方在持 soundLock 时投递（execute 仅入队，开销极低），保证与状态变更的全局有序
     */
    private val ringtoneExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "CriticalAlert-Ringtone").apply { isDaemon = true }
        }
    }

    private fun postRingtoneAction(action: () -> Unit) {
        try {
            ringtoneExecutor.execute(action)
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertSoundPlayer Failed to post ringtone action: ${e.message}" }
        }
    }

    private fun safeStopRingtone(ringtone: Ringtone?) {
        if (ringtone == null) return
        try {
            ringtone.stop()
        } catch (e: IllegalStateException) {
            L.e { "[Call] CriticalAlertSoundPlayer Ringtone is in an illegal state: ${e.message}" }
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertSoundPlayer Stop failed: ${e.message}" }
        }
    }

    companion object {
        // 告警（铃声 + 震动）安全兜底时长，与闪光灯 30s 保持一致，防止后台无交互时无限播放/震动
        private const val CRITICAL_ALERT_VIBRATION_DURATION_MS = 30_000L
    }

    /**
     * 循环播放铃声（与震动同步）。
     *
     * - API >= 28：使用 [Ringtone.isLooping] 原生循环，播放请求仍走串行 executor
     * - API < 28：无 isLooping，用协程轮询「播放完成则重播」实现循环（参考 CallRingtoneManager，
     *   但去掉「仅正常响铃模式才播放」的限制，保证静音/勿扰下也随 USAGE_ALARM 播放）
     */
    private fun startRingtoneLoop(ringtone: Ringtone) {
        ringtoneLoopJob?.cancel()
        ringtoneLoopJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 在串行 executor 上设置 isLooping 并播放，避免在持锁状态下做 Ringtone IPC
            postRingtoneAction {
                try {
                    ringtone.isLooping = true
                } catch (e: Exception) {
                    L.e { "[Call] CriticalAlertSoundPlayer set isLooping failed: ${e.message}" }
                }
                safePlayRingtone(ringtone)
            }
        } else {
            ringtoneLoopJob = appScope.launch(Dispatchers.IO) {
                // currentRingtone 被替换或置空即退出，避免重播已停止/被取代的铃声
                while (isActive && currentRingtone === ringtone) {
                    // 将 isPlaying 检查与重播都放到串行 executor 上执行，
                    // 与 play()/stop() 同线程，避免跨线程访问同一 MediaPlayer
                    postRingtoneAction {
                        if (currentRingtone === ringtone) {
                            val playing = try {
                                ringtone.isPlaying
                            } catch (e: Exception) {
                                false
                            }
                            if (!playing) {
                                safePlayRingtone(ringtone)
                            }
                        }
                    }
                    delay(ringtoneLoopCheckInterval)
                }
            }
        }
    }

    private fun safePlayRingtone(ringtone: Ringtone) {
        try {
            ringtone.play()
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertSoundPlayer Play failed: ${e.message}" }
        }
    }

    /**
     * 启动与铃声同步的循环震动。
     *
     * - 与 ringtone.play() 同样投递到串行 executor，保证与停止操作的提交顺序一致，避免"孤儿"震动
     * - 使用 USAGE_ALARM，与铃声一致，可在静音/勿扰下随铃声一起震动
     */
    private fun startVibration() {
        postRingtoneAction { callVibrationManager.startAlarmVibration(vibrationPattern, repeat = 0) }
    }

    /**
     * 停止循环震动（通过串行 executor 取消震动）。
     * 传入 CRITICAL_ALERT 归属方，避免误停来电震动，也确保来电停止不会误停本震动。
     */
    private fun stopVibration() {
        postRingtoneAction { callVibrationManager.stopVibration(CallVibrationManager.VibrationSource.CRITICAL_ALERT) }
    }

    /**
     * 安排告警（铃声 + 震动）在 [CRITICAL_ALERT_VIBRATION_DURATION_MS] 后自动停止，
     * 与闪光灯 30s 一致，防止后台无交互时铃声/震动无限持续。
     *
     * 到点后用 [stopSoundIfMatch] 按 notificationId 匹配停止：若此时已被新的告警取代，
     * 则不会误停新告警（新告警有自己的定时器）。
     */
    private fun scheduleAlertAutoStop(notificationId: Int) {
        alertAutoStopJob?.cancel()
        alertAutoStopJob = appScope.launch {
            delay(CRITICAL_ALERT_VIBRATION_DURATION_MS)
            L.i { "[Call] CriticalAlertSoundPlayer alert auto-stop after ${CRITICAL_ALERT_VIBRATION_DURATION_MS}ms, id=$notificationId" }
            stopSoundIfMatch(notificationId)
        }
    }

    /**
     * 播放 Critical Alert 声音（循环）+ 同步循环震动，并安排 30s 自动停止。
     * @param conversationId 会话ID
     * @param notificationId 通知ID
     */
    fun playSound(conversationId: String, notificationId: Int) {
        val token = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) {
            // Phase 1: 锁内仅做状态变更与 stop 提交，不持锁做 IPC
            synchronized(soundLock) {
                criticalAlertStateManager.setCurrentPlayToken(token)
                val old = currentRingtone
                currentRingtone = null
                if (old != null) {
                    // 取消旧的铃声循环与自动停止任务；若本次 play 在 Phase 3 校验通过会重新启动
                    ringtoneLoopJob?.cancel()
                    ringtoneLoopJob = null
                    alertAutoStopJob?.cancel()
                    alertAutoStopJob = null
                    postRingtoneAction { safeStopRingtone(old) }
                    stopVibration()
                }
            }

            try {
                val ringtoneUri =
                    "android.resource://${context.packageName}/${R.raw.critical_alert}".toUri()
                val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }

                // Phase 3: 锁内做 token 校验、状态安装与 play 提交，不持锁做 play() IPC
                synchronized(soundLock) {
                    if (token != criticalAlertStateManager.getCurrentPlayToken()) {
                        L.i { "[Call] CriticalAlertSoundPlayer Ignored old play call for id=$notificationId (token=$token)" }
                        if (ringtone != null) {
                            postRingtoneAction { safeStopRingtone(ringtone) }
                        }
                        return@synchronized
                    }

                    // ringtone 为 null 时（RingtoneManager.getRingtone 失败）不安装"播放中"状态，
                    // 否则会让 isCriticalAlertShowing/isPlayingSound 等门控误判为"正在响铃"
                    if (ringtone == null) {
                        L.w { "[Call] CriticalAlertSoundPlayer getRingtone returned null for notification $notificationId, skip play" }
                        return@synchronized
                    }

                    currentRingtone = ringtone
                    criticalAlertStateManager.setCurrentNotificationId(notificationId)
                    criticalAlertStateManager.setConversationId(conversationId)
                    criticalAlertStateManager.setIsPlayingSound(true)
                    L.i { "[Call] CriticalAlertSoundPlayer Playing ringtone for notification $notificationId" }
                    // 循环播放铃声 + 同步循环震动，并安排 30s 后自动停止（与闪光灯一致）
                    startRingtoneLoop(ringtone)
                    startVibration()
                    scheduleAlertAutoStop(notificationId)
                }

            } catch (e: Exception) {
                L.e { "[Call] CriticalAlertSoundPlayer Play failed: ${e.message}" }
            }
        }
    }

    /**
     * 停止播放 Critical Alert 声音（同步非阻塞，可安全在主线程调用）。
     *
     * - 锁仅保护状态变更（微秒级），不在持锁状态下执行 Ringtone.stop() 等音频 binder IPC
     * - stop() 投递到专用单线程 executor，play/stop 提交顺序即执行顺序
     * - 不 launch 协程，避免 CoroutineScheduler.dispatch 在主线程触发 notifyAll 导致 ANR
     * - 主动失效 currentPlayToken，让进行中的 playSound 在 Phase 3 校验时落入失效分支
     */
    fun stopSound() {
        synchronized(soundLock) {
            criticalAlertStateManager.setCurrentPlayToken(0L)
            // 取消 30s 自动停止与铃声循环任务
            alertAutoStopJob?.cancel()
            alertAutoStopJob = null
            ringtoneLoopJob?.cancel()
            ringtoneLoopJob = null
            val rt = currentRingtone
            currentRingtone = null
            criticalAlertStateManager.resetSoundState()
            if (rt != null) {
                postRingtoneAction { safeStopRingtone(rt) }
            }
            // 停止与铃声同步的循环震动
            stopVibration()
        }
    }

    /**
     * 如果通知ID匹配，则停止播放声音（同步调用）
     */
    fun stopSoundIfMatch(notificationId: Int) {
        synchronized(soundLock) {
            if (notificationId == criticalAlertStateManager.getCurrentNotificationId()) {
                criticalAlertStateManager.setCurrentPlayToken(0L)
                // 取消 30s 自动停止与铃声循环任务
                alertAutoStopJob?.cancel()
                alertAutoStopJob = null
                ringtoneLoopJob?.cancel()
                ringtoneLoopJob = null
                val rt = currentRingtone
                currentRingtone = null
                criticalAlertStateManager.resetSoundState()
                L.i { "[Call] CriticalAlertSoundPlayer Stopped ringtone for $notificationId" }
                if (rt != null) {
                    postRingtoneAction { safeStopRingtone(rt) }
                }
                // 停止与铃声同步的循环震动
                stopVibration()
            }
        }
    }
}
