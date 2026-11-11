package com.difft.android.chat.widget

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.chat.common.SendType
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.isAudioMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.util.FileDecryptionUtil
import com.difft.android.chat.util.ServiceUtil
import java.io.File

object AudioMessageManager {
    const val PLAY_STATUS_START = 1
    const val PLAY_STATUS_PAUSED = 2
    const val PLAY_STATUS_COMPLETE = 3

    const val PLAY_STATUS_PLAYED = 0
    const val PLAY_STATUS_NOT_PLAY = 1

    
    val PLAYBACK_SPEEDS = listOf(1.0f, 1.5f, 2.0f)

    private val _playStatusUpdate = MutableSharedFlow<Pair<TextChatMessage, Int>>(replay = 0, extraBufferCapacity = 64)
    val playStatusUpdate: SharedFlow<Pair<TextChatMessage, Int>> = _playStatusUpdate

    private fun emitPlayStatusUpdate(message: TextChatMessage, status: Int) {
        _playStatusUpdate.tryEmit(message to status)
    }

    private val _progressUpdate = MutableSharedFlow<Pair<TextChatMessage, Float>>(replay = 0, extraBufferCapacity = 64)
    val progressUpdate: SharedFlow<Pair<TextChatMessage, Float>> = _progressUpdate

    
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    var currentPlayingMessage: TextChatMessage? = null
    var isPaused: Boolean = false
    private var currentPlayPosition: Int = 0
    var currentProgress: Float = 0f
    private var currentFilePath: String? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null
    private var prepareTimeoutJob: kotlinx.coroutines.Job? = null

    





    private val BUILTIN_OUTPUT_TYPES: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        add(AudioDeviceInfo.TYPE_TELEPHONY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
        }
    }

    




    private val audioManager: AudioManager
        get() = ServiceUtil.getAudioManager(application)

    init {
        ProximitySensorManager.setAudioDeviceChangeListener(object : ProximitySensorManager.AudioDeviceChangeListener {
            override fun onAudioDeviceChanged(isNear: Boolean) = applyProximityRoute(isNear)
        })
    }

    




    internal fun preferredOutputType(isNear: Boolean, availableTypes: Set<Int>): Int? {
        if (!isNear) return null
        if (availableTypes.any { it !in BUILTIN_OUTPUT_TYPES }) return null
        return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE.takeIf { it in availableTypes }
    }

    









    internal fun applyProximityRoute(isNear: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val player = mediaPlayer ?: return
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val targetType = preferredOutputType(isNear, outputs.mapTo(mutableSetOf()) { it.type })
            val target = targetType?.let { type -> outputs.firstOrNull { it.type == type } }
            val accepted = player.setPreferredDevice(target) 
            if (accepted) {
                L.i { "[AudioMessageManager] voice route applied isNear=$isNear target=${targetType ?: "default"} routed=${player.routedDevice?.type}" }
            } else {
                L.w { "[AudioMessageManager] voice route rejected isNear=$isNear target=${targetType ?: "default"} routed=${player.routedDevice?.type}" }
            }
            
            
            
            VoiceMessageProximityLock.requestHeld(held = accepted && targetType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] applyProximityRoute failed isNear=$isNear: ${e.stackTraceToString()}" }
        }
    }

    
    private fun startProximityTracking() {
        VoiceMessageProximityLock.enable()
        ProximitySensorManager.start()
    }

    private fun stopProximityTracking() {
        ProximitySensorManager.stop()
        VoiceMessageProximityLock.disable()
    }

    



    fun setPlaybackSpeed(speed: Float) {
        if (speed !in PLAYBACK_SPEEDS) return
        _playbackSpeed.value = speed
        applyPlaybackSpeed()
    }

    



    fun cyclePlaybackSpeed(): Float {
        val currentIndex = PLAYBACK_SPEEDS.indexOf(_playbackSpeed.value)
        val nextIndex = (currentIndex + 1) % PLAYBACK_SPEEDS.size
        val newSpeed = PLAYBACK_SPEEDS[nextIndex]
        setPlaybackSpeed(newSpeed)
        return newSpeed
    }

    


    private fun applyPlaybackSpeed() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying || isPaused) {
                    val params = PlaybackParams().setSpeed(_playbackSpeed.value)
                    player.playbackParams = params
                }
            }
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] applyPlaybackSpeed Exception: ${e.stackTraceToString()}" }
        }
    }

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

    fun playOrPauseAudio(message: TextChatMessage, filePath: String) {
        playJob?.cancel()
        playJob = appScope.launch(Dispatchers.IO) {
            try {
                val encryptFile = File("$filePath.encrypt")
                val decryptedBytes: ByteArray? = if (encryptFile.exists()) {
                    FileDecryptionUtil.decryptToBytes(encryptFile, message.attachment?.key)
                } else {
                    if (!File(filePath).exists()) {
                        L.e { "[AudioMessageManager] playOrPauseAudio: file not exists messageId=${message.id}" }
                        releasePlayer()
                        return@launch
                    }
                    null
                }

                withContext(Dispatchers.Main) {
                    if (mediaPlayer == null) {
                        initMediaPlayer()
                    }

                    if (currentPlayingMessage == null || currentPlayingMessage?.id != message.id) {
                        currentPlayingMessage?.let {
                            emitPlayStatusUpdate(it, PLAY_STATUS_PAUSED)
                            
                            
                            
                            stopProximityTracking()
                        }
                        currentPlayingMessage = message
                        currentFilePath = filePath
                        startPlaying(message, filePath, decryptedBytes)
                    } else {
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

    private fun startPlaying(message: TextChatMessage, filePath: String, decryptedBytes: ByteArray? = null) {
        if (decryptedBytes == null && (filePath.isEmpty() || !File(filePath).exists())) {
            L.e { "[AudioMessageManager] startPlaying: file not exists messageId=${message.id}" }
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

            
            prepareTimeoutJob?.cancel()
            prepareTimeoutJob = appScope.launch {
                delay(10000)
                L.w { "[AudioMessageManager] prepare timeout for message: ${message.id}" }
                releasePlayer()
            }

            mediaPlayer?.apply {
                if (decryptedBytes != null) {
                    setDataSource(ByteArrayMediaDataSource(decryptedBytes))
                } else {
                    setDataSource(filePath)
                }
                setOnCompletionListener {
                    onPlayComplete()
                }
                setOnPreparedListener { player ->
                    prepareTimeoutJob?.cancel()
                    try {
                        
                        val params = PlaybackParams().setSpeed(_playbackSpeed.value)
                        player.playbackParams = params
                        player.start()
                        emitPlayStatusUpdate(message, PLAY_STATUS_START)
                        startUpdatingProgress()
                        isPaused = false

                        
                        startProximityTracking()
                    } catch (e: Exception) {
                        L.e { "[AudioMessageManager] startPlaying onPrepared Exception: ${e.stackTraceToString()}" }
                        releasePlayer()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    prepareTimeoutJob?.cancel()
                    L.e { "[AudioMessageManager] MediaPlayer prepare error: $what, $extra" }
                    releasePlayer()
                    true
                }
                prepareAsync() 
            }
        } catch (e: Exception) {
            prepareTimeoutJob?.cancel()
            L.e { "[AudioMessageManager] startPlaying Exception: ${e.stackTraceToString()}" }
            releasePlayer()
        }
    }

    private fun pauseAudio() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    pause()
                    isPaused = true
                    currentPlayPosition = currentPosition
                    emitPlayStatusUpdate(currentPlayingMessage!!, PLAY_STATUS_PAUSED)

                    
                    stopProximityTracking()
                }
            }
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] pauseAudio Exception: ${e.stackTraceToString()}" }
            releasePlayer()
        }
    }

    private fun resumeAudio() {
        try {
            mediaPlayer?.apply {
                if (isPaused) {
                    seekTo(currentPlayPosition)
                    
                    val params = PlaybackParams().setSpeed(_playbackSpeed.value)
                    playbackParams = params
                    start()
                    isPaused = false
                    emitPlayStatusUpdate(currentPlayingMessage!!, PLAY_STATUS_START)

                    
                    
                    startProximityTracking()
                }
            }
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] resumeAudio IllegalStateException: ${e.stackTraceToString()}" }
            releasePlayer()
        }
    }

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

    private fun onPlayComplete() {
        val completedMessage = currentPlayingMessage
        completedMessage?.let {
            L.d { "[AudioMessageManager] COMPLETE: ${it.id}" }
            emitPlayStatusUpdate(it, PLAY_STATUS_COMPLETE)
            deleteCurrentFile(it)
        }

        
        currentFilePath = null
        currentPlayingMessage = null
        isPaused = false
        currentPlayPosition = 0
        currentProgress = 0f

        try {
            mediaPlayer?.reset()  
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] onPlayComplete reset error: ${e.message}" }
        }

        stopProximityTracking()
        progressJob?.cancel()
        progressJob = null
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = null
    }

    fun deleteCurrentFile(message: TextChatMessage) {
        if (message.attachment?.isAudioMessage() != true) return
        currentFilePath?.let { path ->
            deleteDecryptedFile(path, message.attachment?.key)
        }
    }

    




    fun deleteDecryptedFile(filePath: String, key: ByteArray?) {
        if (!EncryptedAttachmentAccess.isUsableKey(key) || !EncryptedAttachmentAccess.hasEncrypted(filePath)) return
        try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            }
            L.i { "[AudioMessageManager] delete decrypted audio file success" }
        } catch (e: Exception) {
            L.e { "[AudioMessageManager] Failed to delete file: ${e.message}" }
        }
    }

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

        
        stopProximityTracking()

        progressJob?.cancel()
        progressJob = null
        playJob?.cancel()
        playJob = null
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = null
    }
}