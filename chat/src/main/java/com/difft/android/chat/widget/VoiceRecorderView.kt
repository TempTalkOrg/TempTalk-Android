package com.difft.android.chat.widget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.audio.MediaRecorderWrapper
import org.thoughtcrime.securesms.providers.MyBlobProvider

class VoiceRecorderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val recordView: LinearLayout
    private val recordButton: AppCompatTextView
    private val waveformView: WaveformView
    private val cancelZone: AppCompatImageView
    private val tvTips: AppCompatTextView
    private val tvStop: AppCompatTextView

    // View 级别的协程作用域，与 View 生命周期绑定
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var startY = 0f
    private var isRecording = false
    private var isCancelled = false

    private var mediaRecorder: MediaRecorder? = null
    private var amplitudeUpdateJob: Job? = null
    private var countdownJob: Job? = null

    private var recordingStartTime: Long = 0 // 记录开始录制的时间

    var recordingCallback: ((RecordingState) -> Unit)? = null

    private var outputFilePath: String? = null

    private companion object {
        const val MIN_RECORDING_DURATION_MS = 1000L // 最短录制时间 1 秒（以毫秒为单位）
        const val MAX_RECORDING_DURATION_MS = 180000L // 最长录制时间 3 分钟（以毫秒为单位）
        const val COUNTDOWN_THRESHOLD_MS = 10000L // 倒计时阈值 10 秒（以毫秒为单位）
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_voice_recorder, this, true)
        recordView = findViewById(R.id.ll_record)
        recordButton = findViewById(R.id.record_button)
        waveformView = findViewById(R.id.waveform_view)
        cancelZone = findViewById(R.id.cancel_zone)
        tvTips = findViewById(R.id.tv_tips)
        tvStop = findViewById(R.id.tv_stop)
        tvStop.background = TooltipBackgroundDrawable()

        initListeners()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // View 被销毁时，取消所有协程，避免内存泄漏
        viewScope.cancel()
        amplitudeUpdateJob?.cancel()
        countdownJob?.cancel()

        // 同步停止 MediaRecorder，避免资源泄漏
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
        } catch (e: Exception) {
            L.i { "[VoiceRecorder] cleanup MediaRecorder failed: ${e.message}" }
        }

        releaseAudioFocus()
        L.i { "[VoiceRecorder] View detached, all resources cleaned up" }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        recordView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        recordingCallback?.invoke(RecordingState.RecordPermissionRequired)
                    } else {
                        startY = event.rawY
                        startRecordingIfPermissionGranted()
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isRecording) handleMove(event.rawX, event.rawY)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) stopRecording()
                }
            }
            true
        }
    }

    private fun startRecordingIfPermissionGranted() {
        isRecording = true
        isCancelled = false

        cancelZone.visibility = View.VISIBLE
        cancelZone.setBackgroundResource(R.drawable.chat_voice_cancle_bg) // 初始为灰色
        tvTips.visibility = View.GONE
        waveformView.visibility = View.VISIBLE
        waveformView.startAnimation()

        recordButton.text = context.getString(R.string.chat_voice_release_to_send)
        recordView.setBackgroundResource(R.drawable.chat_voice_record_blue_bg)
        recordButton.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary_night))

        recordingCallback?.invoke(RecordingState.Started)

        requestFocusAndRecord()
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob = viewScope.launch {
            while (isActive) {
                val elapsedTime = System.currentTimeMillis() - recordingStartTime
                val remainingTime = MAX_RECORDING_DURATION_MS - elapsedTime

                if (remainingTime in 1..COUNTDOWN_THRESHOLD_MS) {
                    val secondsRemaining = (remainingTime / 1000).toInt()
                    tvStop.visibility = View.VISIBLE
                    tvStop.text = context.getString(R.string.chat_voice_recording_will_stop, secondsRemaining)
                } else {
                    tvStop.visibility = View.GONE
                }

                if (elapsedTime >= MAX_RECORDING_DURATION_MS) {
                    stopRecording()
                    break
                }

                delay(100)
            }
        }
    }

    // 开始录音
    private fun startMediaRecorder() {
        recordingStartTime = System.currentTimeMillis()
        try {
            outputFilePath = FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY) + System.currentTimeMillis() + ".m4a"

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(MediaRecorderWrapper.SAMPLE_RATE)
                setAudioEncodingBitRate(MediaRecorderWrapper.BIT_RATE)
                setAudioChannels(MediaRecorderWrapper.CHANNELS)
                setOutputFile(outputFilePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            L.i { "[VoiceRecorder] start record failed:" + e.stackTraceToString() }
            isCancelled = true
            stopRecording()
        }
    }

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestFocusAndRecord() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener { focusChange ->
                    // ✅ 检查录音状态，避免在 MediaRecorder 已停止时操作
                    if (!isRecording) {
                        L.i { "[VoiceRecorder] Audio focus changed but not recording, ignore" }
                        return@setOnAudioFocusChangeListener
                    }

                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            // 丧失音频焦点，直接停止录音（不只是暂停）
                            L.i { "[VoiceRecorder] Audio focus lost, stop recording" }
                            try {
                                stopRecording()
                            } catch (e: Exception) {
                                L.i { "[VoiceRecorder] Stop recording failed: ${e.message}" }
                            }
                        }

                        AudioManager.AUDIOFOCUS_GAIN -> {
                            // 获取音频焦点，继续录音
                            L.i { "[VoiceRecorder] Audio focus gained" }
                            try {
                                mediaRecorder?.resume()
                            } catch (e: Exception) {
                                L.i { "[VoiceRecorder] Resume failed: ${e.message}" }
                            }
                        }

                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // 临时丧失音频焦点（短暂丧失），暂停录音
                            L.i { "[VoiceRecorder] Audio focus lost transient, pause recording" }
                            try {
                                mediaRecorder?.pause()
                            } catch (e: Exception) {
                                L.i { "[VoiceRecorder] Pause failed: ${e.message}" }
                            }
                        }
                    }
                }
                .build()

            audioFocusRequest?.let {
                val focusRequestResult = audioManager?.requestAudioFocus(it)
                if (focusRequestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    L.i { "[VoiceRecorder] Get audio focus, start Media Recorder" }
                    startMediaRecorder()
                    startAmplitudeUpdates()
                } else {
                    L.i { "[VoiceRecorder] Failed to gain audio focus." }
                }
            }
        } else {
            startMediaRecorder()
            startAmplitudeUpdates()
        }
    }


    private fun startAmplitudeUpdates() {
        amplitudeUpdateJob = viewScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val amplitude = mediaRecorder?.maxAmplitude?.toFloat() ?: 0f

                    withContext(Dispatchers.Main) {
                        waveformView.updateAmplitude(amplitude)
                    }
                    delay(100)
                } catch (e: Exception) {
                    L.w { "[VoiceRecorderView] error: ${e.stackTraceToString()}" }
                }
            }
        }
    }

    private fun handleMove(currentX: Float, currentY: Float) {
        // 获取 cancel_zone 的屏幕坐标
        val location = IntArray(2)
        cancelZone.getLocationOnScreen(location)
        val cancelZoneX = location[0]
        val cancelZoneY = location[1]

        // 获取当前的放大比例
        val scaleFactor = cancelZone.scaleX // 假设 scaleX 和 scaleY 始终相等

        // 计算放大后的 cancel_zone 范围
        val scaledWidth = cancelZone.width * scaleFactor
        val scaledHeight = cancelZone.height * scaleFactor
        val scaledCancelZoneX = cancelZoneX - (scaledWidth - cancelZone.width) / 2
        val scaledCancelZoneY = cancelZoneY - (scaledHeight - cancelZone.height) / 2

        // 判断触摸点是否在放大后的 cancel_zone 范围内
        val isInScaledCancelZone = currentX.toInt() in scaledCancelZoneX.toInt()..(scaledCancelZoneX + scaledWidth).toInt() &&
                currentY.toInt() in scaledCancelZoneY.toInt()..(scaledCancelZoneY + scaledHeight).toInt()

        if (isInScaledCancelZone) {
            if (!isCancelled) {
                isCancelled = true
                cancelZone.animate().scaleX(1.2f).scaleY(1.2f).setDuration(200).start()
            }
            recordButton.text = context.getString(R.string.chat_voice_release_to_send)
            recordButton.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_disable))
            waveformView.setBarColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_disable))
            recordView.setBackgroundResource(R.drawable.chat_msg_input_bg)
            cancelZone.setBackgroundResource(R.drawable.chat_voice_cancle_bg_red)
            tvTips.visibility = View.VISIBLE
            tvTips.text = context.getString(R.string.chat_voice_release_to_cancel)
        } else {
            if (isCancelled) {
                isCancelled = false
                cancelZone.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            recordButton.text = context.getString(R.string.chat_voice_release_to_send)
            recordButton.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary_night))
            waveformView.setBarColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary_night))
            recordView.setBackgroundResource(R.drawable.chat_voice_record_blue_bg)
            cancelZone.setBackgroundResource(R.drawable.chat_voice_cancle_bg)
            tvTips.visibility = View.GONE
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        // 计算录制时长
        val recordingDuration = System.currentTimeMillis() - recordingStartTime

        amplitudeUpdateJob?.cancel()
        countdownJob?.cancel()

        // 在后台线程停止 MediaRecorder，避免阻塞主线程
        viewScope.launch(Dispatchers.IO) {
            try {
                stopMediaRecorder()
                releaseAudioFocus()
            } catch (e: Exception) {
                L.i { "[VoiceRecorder] Error during stop: ${e.message}" }
            }

            // 在主线程更新 UI 和回调，确保无论是否发生异常都会执行
            withContext(Dispatchers.Main) {
                // 检查 View 是否还附加在窗口上，避免操作已销毁的 View
                if (!isAttachedToWindow) {
                    L.i { "[VoiceRecorder] View detached, skip UI update" }
                    return@withContext
                }

                when {
                    isCancelled -> {
                        L.i { "[VoiceRecorder] Recording cancelled, file deleted." }
                        deleteRecordingFile()
                        recordingCallback?.invoke(RecordingState.Cancelled)
                    }

                    recordingDuration < MIN_RECORDING_DURATION_MS -> {
                        L.i { "[VoiceRecorder] Recording too short, file deleted." }
                        deleteRecordingFile()
                        recordingCallback?.invoke(RecordingState.TooShort)
                    }

                    else -> {
                        L.i { "[VoiceRecorder] Recording saved. Duration:$recordingDuration  path:$outputFilePath" }
                        outputFilePath?.let { path ->
                            // 检查文件大小
                            val file = java.io.File(path)
                            if (file.exists() && file.length() > 10 * 1024 * 1024) { // 10MB
                                deleteRecordingFile()
                                recordingCallback?.invoke(RecordingState.TooLarge)
                            } else {
                                recordingCallback?.invoke(RecordingState.Stopped(filePath = path))
                            }
                        }
                    }
                }

                // 确保无论什么情况都重置 UI
                resetButton()
            }
        }
    }


    private fun stopMediaRecorder() {
        mediaRecorder?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                L.i { "[VoiceRecorder] stop failed:" + e.stackTraceToString() }
            }
        }
        mediaRecorder = null
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager?.abandonAudioFocusRequest(it)
            }
        }
    }

    private fun resetButton() {
        recordButton.text = context.getString(R.string.chat_voice_hold_to_talk)
        recordButton.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary))
        waveformView.setBarColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary_night))
        recordView.setBackgroundResource(R.drawable.chat_msg_input_bg)
        cancelZone.visibility = View.INVISIBLE
        cancelZone.setBackgroundResource(R.drawable.chat_voice_cancle_bg)
        cancelZone.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        tvTips.visibility = View.GONE
        tvStop.visibility = View.GONE
        waveformView.visibility = View.GONE
        waveformView.stopAnimation()
    }

    private fun deleteRecordingFile() {
        outputFilePath?.let {
            MyBlobProvider.getInstance().delete(it.toUri())
        }
    }
}

sealed class RecordingState {
    data object Started : RecordingState()
    data class Stopped(val filePath: String) : RecordingState()
    data object TooShort : RecordingState()
    data object Cancelled : RecordingState()
    data object RecordPermissionRequired : RecordingState()
    data object TooLarge : RecordingState()
}

