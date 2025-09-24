package com.difft.android.chat.widget

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.common.SendType
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.isAudioMessage
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.util.FileDecryptionUtil
import java.io.File

object AudioMessageManager {
    const val PLAY_STATUS_START = 1
    const val PLAY_STATUS_PAUSED = 2
    const val PLAY_STATUS_COMPLETE = 3

    const val PLAY_STATUS_PLAYED = 0
    const val PLAY_STATUS_NOT_PLAY = 1

    private val mPlayStatusUpdateSubject = PublishSubject.create<Pair<TextChatMessage, Int>>()

    private fun emitPlayStatusUpdate(message: TextChatMessage, status: Int) = mPlayStatusUpdateSubject.onNext(message to status)

    val playStatusUpdate: Observable<Pair<TextChatMessage, Int>> = mPlayStatusUpdateSubject

    private val _progressUpdate = MutableSharedFlow<Pair<TextChatMessage, Float>>(replay = 0)
    val progressUpdate: SharedFlow<Pair<TextChatMessage, Float>> = _progressUpdate

    private var mediaPlayer: MediaPlayer? = null
    var currentPlayingMessage: TextChatMessage? = null
    var isPaused: Boolean = false // 标记是否暂停
    private var currentPlayPosition: Int = 0
    var currentProgress: Float = 0f
    private var currentFilePath: String? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null
    private var prepareTimeoutJob: kotlinx.coroutines.Job? = null

    init {
        ProximitySensorManager.setAudioDeviceChangeListener(object : ProximitySensorManager.AudioDeviceChangeListener {
            override fun onAudioDeviceChanged(isNear: Boolean) {}
        })
    }

    // 初始化 MediaPlayer，并准备播放
    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        }
    }

    // 播放或暂停指定的音频文件
    fun playOrPauseAudio(message: TextChatMessage, filePath: String) {
        playJob?.cancel()
        playJob = appScope.launch(Dispatchers.IO) {
            try {
                // 检查是否需要解密文件
                decryptIfNeeded(filePath, message)

                if (!File(filePath).exists()) {
                    L.e { "[AudioMessageManager] playOrPauseAudio: file not exists $filePath" }
                    releasePlayer()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (mediaPlayer == null) {
                        initMediaPlayer()
                    }

                    if (currentPlayingMessage == null || currentPlayingMessage?.id != message.id) {
                        // 如果当前没有播放任何音频或播放的是其他音频文件，则重新开始播放
                        currentPlayingMessage?.let {
                            // 如果有正在播放的音频，则发出完成事件
                            emitPlayStatusUpdate(it, PLAY_STATUS_PAUSED)
                        }
                        currentPlayingMessage = message
                        currentFilePath = filePath
                        startPlaying(message, filePath)
                    } else {
                        // 如果是同一个音频，则暂停或恢复播放
                        if (mediaPlayer?.isPlaying == true) {
                            pauseAudio()
                        } else {
                            resumeAudio()
                        }
                    }
                }
            } catch (e: Exception) {
                L.e { "[AudioMessageManager] playOrPauseAudio Exception: ${e.stackTraceToString()}" }
                releasePlayer()
            }
        }
    }

    // 开始播放音频
    private fun startPlaying(message: TextChatMessage, filePath: String) {
        if (filePath.isEmpty() || !File(filePath).exists()) {
            L.e { "[AudioMessageManager] startPlaying: file not exists $filePath" }
            releasePlayer()
            return
        }
        L.d { "[AudioMessageManager] startPlaying: ${message.id} $filePath" }
        try {
            if (mediaPlayer == null) {
                initMediaPlayer()
            } else {
                mediaPlayer?.reset()
            }
            
            // 设置准备超时，防止无限等待
            prepareTimeoutJob?.cancel()
            prepareTimeoutJob = appScope.launch {
                delay(10000) // 10秒超时
                L.w { "[AudioMessageManager] prepare timeout for message: ${message.id}" }
                releasePlayer()
            }
            
            mediaPlayer?.apply {
                setDataSource(filePath) // 设置音频文件的 URL
                setOnCompletionListener {
                    onPlayComplete()
                }
                setOnPreparedListener { player ->
                    prepareTimeoutJob?.cancel() // 取消超时任务
                    try {
                        player.start() // 开始播放
                        emitPlayStatusUpdate(message, PLAY_STATUS_START)
                        startUpdatingProgress() // 开始更新进度
                        isPaused = false

                        // Start proximity sensor when audio starts playing
                        ProximitySensorManager.start()
                    } catch (e: Exception) {
                        L.e { "[AudioMessageManager] startPlaying onPrepared Exception: ${e.stackTraceToString()}" }
                        releasePlayer()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    prepareTimeoutJob?.cancel() // 取消超时任务
                    L.e { "[AudioMessageManager] MediaPlayer prepare error: $what, $extra" }
                    releasePlayer()
                    true
                }
                prepareAsync() // 异步准备播放器，避免ANR
            }
        } catch (e: Exception) {
            prepareTimeoutJob?.cancel() // 取消超时任务
            L.e { "[AudioMessageManager] startPlaying Exception: ${e.stackTraceToString()}" }
            releasePlayer()
        }
    }

    // 暂停音频
    private fun pauseAudio() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    pause()
                    isPaused = true
                    currentPlayPosition = currentPosition // 保存当前播放进度
                    emitPlayStatusUpdate(currentPlayingMessage!!, PLAY_STATUS_PAUSED)
                }
            }
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] pauseAudio Exception: ${e.stackTraceToString()}" }
            releasePlayer()
        }
    }

    // 恢复播放音频
    private fun resumeAudio() {
        try {
            mediaPlayer?.apply {
                if (isPaused) {
                    seekTo(currentPlayPosition) // 恢复到暂停时的进度
                    start()
                    isPaused = false
                    emitPlayStatusUpdate(currentPlayingMessage!!, PLAY_STATUS_START)
                }
            }
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] resumeAudio IllegalStateException: ${e.stackTraceToString()}" }
            releasePlayer()
        }
    }

    // 开始更新播放进度
    private fun startUpdatingProgress() {
        progressJob?.cancel()
        progressJob = appScope.launch {
            while (isActive) {
                mediaPlayer?.let { player ->
                    currentPlayingMessage?.let { msg ->
                        try {
                            val duration = player.duration
                            if (duration > 0) {
                                val currentPosition = player.currentPosition.coerceIn(0, duration)
                                val progress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                currentProgress = progress
                                _progressUpdate.emit(msg to progress)
                            }
                        } catch (e: Exception) {
                            L.e { "[AudioMessageManager] Error updating progress: ${e.message}" }
                        }
                    }
                }
                delay(500)
            }
        }
    }

    // 播放完成后调用
    private fun onPlayComplete() {
        currentPlayingMessage?.let {
            L.d { "[AudioMessageManager] COMPLETE: ${it.id}" }
            emitPlayStatusUpdate(it, PLAY_STATUS_COMPLETE)
        }
        stopAudio()
    }

    // 停止播放当前音频
    private fun stopAudio() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    pause()
                    currentPlayPosition = currentPosition // 保存当前播放进度
                }
                stop() // 停止播放
                reset() // 重置播放器
            }
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] stopAudio IllegalStateException: ${e.stackTraceToString()}" }
        } finally {
            releasePlayer()
        }
    }

    // 删除当前播放的文件
    fun deleteCurrentFile(message: TextChatMessage) {
        val canDeleteOriginFile = message.attachment?.isAudioMessage() == true && message.sendStatus == SendType.Sent.rawValue
        if (!canDeleteOriginFile) return
        currentFilePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
                L.i { "[AudioMessageManager] delete decrypted audio file success" }
            } catch (e: Exception) {
                L.e { "[AudioMessageManager] Failed to delete file: ${e.message}" }
            }
        }
    }

    // 释放播放器资源
    fun releasePlayer() {
        currentPlayingMessage?.let {
            emitPlayStatusUpdate(it, PLAY_STATUS_COMPLETE)
        }
        currentPlayingMessage?.let {
            deleteCurrentFile(it)
        }
        currentFilePath = null
        currentPlayingMessage = null
        isPaused = false
        currentPlayPosition = 0
        currentProgress = 0f
        mediaPlayer?.release()
        mediaPlayer = null

        // Stop proximity sensor when player is released
        ProximitySensorManager.stop()

        progressJob?.cancel()
        progressJob = null
        playJob?.cancel()
        playJob = null
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = null
    }

    fun decryptIfNeeded(attachmentPath: String, message: TextChatMessage) {
        val encryptedFile = File("$attachmentPath.encrypt")
        // 如果加密文件存在，且原文件不存在，才需要解密
        if (encryptedFile.exists() && !File(attachmentPath).exists()) {
            FileDecryptionUtil.decryptFile(encryptedFile, File(attachmentPath), message.attachment?.key)
        }
    }
}