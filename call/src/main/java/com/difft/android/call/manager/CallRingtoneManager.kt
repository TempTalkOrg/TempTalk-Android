package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话铃声管理器
 * 负责管理通话相关的铃声播放、停止和循环播放
 */
@Singleton
class CallRingtoneManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var currentRingtone: Ringtone? = null
    private var isLooping = false
    private var loopingJob: Job? = null
    
    // 检查播放状态的轮询间隔（毫秒）
    private val playbackCheckInterval = 100L
    // 设备静音/震动时的重试间隔（毫秒），避免紧密循环
    private val silentRetryInterval = 1000L

    /**
     * 播放通话铃声
     * @param callType 通话类型（1on1、group、instant）
     * @param roleType 角色类型（caller、callee）
     * @param tag 日志标签，用于调试
     */
    fun playRingTone(callType: String?, roleType: String?, tag: String? = null) {
        L.d { "[Call] CallRingtoneManager playRingTone: callType=$callType, roleType=$roleType, tag=$tag" }
        appScope.launch {
            // 如果已经有铃声在播放，先停止
            if (currentRingtone != null) {
                L.d { "[Call] CallRingtoneManager Ringtone already playing, stopping first" }
                stopRingToneInternal()
            }

            val ringtoneUri = getRingtoneUri(callType, roleType)
            if (ringtoneUri != null) {
                try {
                    currentRingtone = RingtoneManager.getRingtone(context, ringtoneUri)
                    L.d { "[Call] CallRingtoneManager do playRingTone, isLooping=$isLooping" }

                    if (isLooping) {
                        startLoopingRingtone()
                    } else {
                        currentRingtone?.play()
                    }
                } catch (e: Exception) {
                    L.e { "[Call] CallRingtoneManager Failed to play ringtone: ${e.message}" }
                }
            } else {
                L.w { "[Call] CallRingtoneManager No ringtone URI found for callType=$callType, roleType=$roleType" }
            }
        }
    }

    /**
     * 从 Intent 中提取信息并播放铃声
     * @param intent 包含通话类型和角色信息的 Intent
     */
    fun startRingTone(intent: Intent) {
        val callType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE)
        val roleType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE)
        playRingTone(callType, roleType, tag = "showCallNotification")
    }

    /**
     * 播放挂断铃声
     * @param coroutineScope 协程作用域，用于异步播放
     */
    fun playHangupRingTone() {
        appScope.launch(Dispatchers.IO) {
            L.d { "[Call] CallRingtoneManager set ringTone call_hangup" }
            val ringtoneUri = Uri.parse("android.resource://${context.packageName}/${R.raw.new_call_hangup}")
            
            try {
                // 先停止当前播放的铃声
                stopRingToneInternal()
                
                currentRingtone = RingtoneManager.getRingtone(context, ringtoneUri)
                if (currentRingtone == null) {
                    L.d { "[Call] CallRingtoneManager call_hangup ringTone is null" }
                } else {
                    currentRingtone?.play()
                }
            } catch (e: Exception) {
                L.e { "[Call] CallRingtoneManager Failed to play hangup ringtone: ${e.message}" }
            }
        }
    }

    /**
     * 停止铃声播放
     * @param tag 日志标签，用于调试
     */
    fun stopRingTone(tag: String? = null) {
        L.d { "[Call] CallRingtoneManager stopRingTone, tag=$tag" }
        appScope.launch(Dispatchers.IO) {
            stopRingToneInternal()
        }
    }

    /**
     * 内部方法：停止铃声播放
     */
    private fun stopRingToneInternal() {
        try {
            isLooping = false
            
            // 取消循环播放协程
            loopingJob?.cancel()
            loopingJob = null
            
            // 停止当前播放的铃声（始终调用 stop 以确保底层 MediaPlayer 被释放，避免 finalize 超时崩溃）
            currentRingtone?.let {
                try {
                    it.stop()
                } catch (e: IllegalStateException) {
                    L.e { "[Call] CallRingtoneManager Ringtone is in an illegal state: ${e.message}" }
                } catch (e: NullPointerException) {
                    L.e { "[Call] CallRingtoneManager NullPointerException encountered while stopping ringtone: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            L.e { "[Call] CallRingtoneManager Error stopping ringtone: ${e.message}" }
        } finally {
            currentRingtone = null // 释放资源
        }
    }

    /**
     * 根据通话类型和角色获取铃声 URI
     * @param callType 通话类型
     * @param roleType 角色类型
     * @return 铃声 URI，如果找不到则返回 null
     */
    private fun getRingtoneUri(callType: String?, roleType: String?): Uri? {
        return when {
            callType == CallType.ONE_ON_ONE.type -> {
                if (roleType == CallRole.CALLER.type) {
                    L.d { "[Call] CallRingtoneManager set ringTone call_outgoing_1v1" }
                    isLooping = true // 1v1 去电需要循环播放
                    Uri.parse("android.resource://${context.packageName}/${R.raw.new_call_outgoing_1v1}")
                } else {
                    L.d { "[Call] CallRingtoneManager set ringTone new_call_incomming_1v1" }
                    isLooping = true // 1v1 来电铃声需要循环播放
                    Uri.parse("android.resource://${context.packageName}/${R.raw.new_call_incomming_1v1}")
                }
            }
            
            (callType == CallType.GROUP.type || callType == CallType.INSTANT.type) && roleType == CallRole.CALLEE.type -> {
                L.d { "[Call] CallRingtoneManager set ringTone call_incomming_group" }
                isLooping = false // 群组来电不循环播放
                Uri.parse("android.resource://${context.packageName}/${R.raw.new_call_incomming_group}")
            }
            
            else -> null
        }
    }

    /**
     * 启动循环播放铃声
     * 使用 Kotlin Coroutines 实现循环播放，等待铃声完整播放完成后再重新播放
     */
    private fun startLoopingRingtone() {
        // 取消之前的循环任务（如果存在）
        loopingJob?.cancel()
        loopingJob = null
        
        val ringtone = currentRingtone ?: run {
            L.w { "[Call] CallRingtoneManager Cannot start looping: ringtone is null" }
            return
        }
        
        // 启动循环播放协程
        loopingJob = appScope.launch(Dispatchers.IO) {
            try {
                // 循环播放：等待播放完成，然后重新播放
                while (shouldContinueLooping(ringtone)) {
                    // 静音/震动模式下避免播放与紧密循环
                    if (!isRingerAudible()) {
                        delay(silentRetryInterval)
                        continue
                    }

                    // 防止静音检查与播放之间状态变化
                    if (!shouldContinueLooping(ringtone)) break

                    // 播放铃声
                    playRingtoneSafely(ringtone) ?: break

                    // 等待铃声播放完成
                    waitForPlaybackComplete(ringtone)
                }
            } catch (e: Exception) {
                L.e { "[Call] CallRingtoneManager Unexpected error in looping: ${e.message}" }
            } finally {
                // 循环结束，清理 job 引用
                loopingJob = null
                L.d { "[Call] CallRingtoneManager Looping ringtone stopped" }
            }
        }
    }
    
    /**
     * 检查是否应该继续循环播放
     * @param originalRingtone 原始铃声实例，用于验证是否被替换
     * @return true 表示应该继续，false 表示应该停止
     */
    private fun shouldContinueLooping(originalRingtone: Ringtone): Boolean {
        if (loopingJob?.isActive == false || !isLooping) return false
        
        val current = currentRingtone
        if (current == null || current !== originalRingtone) {
            L.d { "[Call] CallRingtoneManager Ringtone changed, stopping loop" }
            return false
        }
        
        return true
    }
    
    /**
     * 安全地播放铃声
     * @param ringtone 要播放的铃声实例
     * @return 播放成功返回 ringtone，失败返回 null
     */
    private suspend fun playRingtoneSafely(ringtone: Ringtone): Ringtone? {
        return try {
            if (ringtone.isPlaying) {
                ringtone.stop()
            }
            ringtone.play()
            ringtone
        } catch (e: IllegalStateException) {
            L.e { "[Call] CallRingtoneManager Ringtone is in an illegal state: ${e.message}" }
            null
        } catch (e: Exception) {
            L.e { "[Call] CallRingtoneManager Error playing ringtone: ${e.message}" }
            null
        }
    }

    private fun isRingerAudible(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }
    
    /**
     * 等待铃声播放完成
     * 通过轮询检查播放状态，直到播放完成或超时
     * @param ringtone 要等待的铃声实例
     */
    private suspend fun waitForPlaybackComplete(ringtone: Ringtone) {
        val startTime = System.currentTimeMillis()
        
        while (shouldContinueLooping(ringtone)) {
            // 检查播放状态
            val isPlaying = try {
                ringtone.isPlaying
            } catch (e: Exception) {
                L.e { "[Call] CallRingtoneManager Error checking playback state: ${e.message}" }
                return
            }
            
            // 如果不在播放，说明播放完成
            if (!isPlaying) {
                val duration = System.currentTimeMillis() - startTime
                L.d { "[Call] CallRingtoneManager Ringtone playback completed after ${duration}ms" }
                return
            }
            
            // 等待一段时间后再次检查
            delay(playbackCheckInterval)
        }
    }

    /**
     * 清理资源
     * 在不再需要时调用，释放所有资源
     */
    fun cleanup() {
        stopRingToneInternal()
    }
}
