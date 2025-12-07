package com.difft.android.chat.common

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CriticalAlertSoundPlayer {
    private var currentRingtone: Ringtone? = null
    private var currentNotificationId: Int? = null
    private var currentPlayToken: Long = 0L // 用于防止并发竞争
    private val mutex = Mutex()

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 对外暴露播放状态，可供 UI 观察
    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    fun play(context: Context, id: Int) {
        val token = System.currentTimeMillis()
        playerScope.launch {
            mutex.withLock {
                // 更新当前 token，清除旧播放
                currentPlayToken = token
                stopInternal()
            }

            try {
                val ringtoneUri =
                    Uri.parse("android.resource://${context.packageName}/${R.raw.critical_alert}")
                val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }

                mutex.withLock {
                    // 若此时已有更新的播放任务，则放弃当前任务
                    if (token != currentPlayToken) {
                        L.i { "CriticalAlertPlayer Ignored old play call for id=$id (token=$token)" }
                        return@withLock
                    }

                    currentRingtone = ringtone
                    currentNotificationId = id
                    _isPlayingFlow.value = true
                    L.i { "CriticalAlertPlayer Playing ringtone for notification $id" }
                    ringtone?.play()
                }

            } catch (e: Exception) {
                L.e { "CriticalAlertPlayer Play failed: ${e.message}" }
            }
        }
    }

    fun stop() {
        playerScope.launch {
            mutex.withLock {
                stopInternal()
            }
        }
    }

    fun stopIfMatch(id: Int) {
        playerScope.launch {
            mutex.withLock {
                if (id == currentNotificationId) {
                    stopInternal()
                    L.i { "CriticalAlertPlayer Stopped ringtone for $id" }
                }
            }
        }
    }

    private fun stopInternal() {
        try {
            currentRingtone?.let {
                if (it.isPlaying) it.stop()
            }
        } catch (e: Exception) {
            L.e { "CriticalAlertPlayer Stop failed: ${e.message}" }
        } finally {
            currentRingtone = null
            currentNotificationId = null
            _isPlayingFlow.value = false
        }
    }
}