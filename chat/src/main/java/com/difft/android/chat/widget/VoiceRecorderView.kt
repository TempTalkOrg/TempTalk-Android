package com.difft.android.chat.widget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.difft.android.base.call.VoiceRecordingTracker
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.appScope
import com.difft.android.chat.R
import com.difft.android.chat.providers.MyBlobProvider
import com.difft.android.chat.voice.DualCandidateVoiceRecorder
import com.difft.android.chat.voice.VoiceMessageRecipes
import com.difft.android.chat.voice.VoiceMessageRecordingCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class VoiceRecorderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val recordView: LinearLayout
    private val recordButton: AppCompatTextView
    private val waveformView: WaveformView
    private val tvStop: AppCompatTextView

    private val llActionButtons: LinearLayout
    private val btnCancel: LinearLayout
    private val btnAddEffect: LinearLayout
    private val tvCancelHint: AppCompatTextView
    private val tvEffectHint: AppCompatTextView
    private val tvEffectLabel: AppCompatTextView

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var isRecording = false

    private enum class GestureTarget { NONE, CANCEL, ADD_EFFECT }

    @Volatile private var gestureTarget = GestureTarget.NONE

    private var voiceRecorder: DualCandidateVoiceRecorder? = null
    private var amplitudeUpdateJob: Job? = null
    private var countdownJob: Job? = null
    private var startJob: Job? = null
    private var stopJob: Job? = null

    @Volatile private var recordingStartTime: Long = 0

    var recordingCallback: ((RecordingState) -> Unit)? = null

    /**
     * Fired synchronously on the main thread the instant the user lifts
     * their finger — before any async recording finalization.  Use this to
     * hide the recording overlay / background immediately.
     */
    var onRecordingDismissed: (() -> Unit)? = null

    @Volatile private var outputDir: File? = null

    @Volatile var lastCandidates: List<VoiceMessageRecordingCandidate> = emptyList()
        private set

    private companion object {
        const val MIN_RECORDING_DURATION_MS = 1000L
        const val MAX_RECORDING_DURATION_MS = 180000L
        const val COUNTDOWN_THRESHOLD_MS = 10000L
        const val RECORDER_STOP_TIMEOUT_MS = 5_000L
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_voice_recorder, this, true)
        recordView = findViewById(R.id.ll_record)
        recordButton = findViewById(R.id.record_button)
        waveformView = findViewById(R.id.waveform_view)
        tvStop = findViewById(R.id.tv_stop)
        tvStop.background = TooltipBackgroundDrawable()

        llActionButtons = findViewById(R.id.ll_action_buttons)
        btnCancel = findViewById(R.id.btn_cancel)
        btnAddEffect = findViewById(R.id.btn_add_effect)
        tvCancelHint = findViewById(R.id.tv_cancel_hint)
        tvEffectHint = findViewById(R.id.tv_effect_hint)
        tvEffectLabel = findViewById(R.id.tv_effect_label)

        initListeners()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        isRecording = false
        VoiceRecordingTracker.setRecording(false, "detach")
        amplitudeUpdateJob?.cancel()
        countdownJob?.cancel()

        val recorderToCleanup = voiceRecorder
        voiceRecorder = null
        if (recorderToCleanup != null) {
            appScope.launch {
                try {
                    recorderToCleanup.release()
                } catch (e: Exception) {
                    L.w(e) { "[VoiceRecorder] dual-candidate release failed on detach" }
                }
            }
        }

        releaseAudioFocus()
        viewScope.cancel()
        L.i { "[VoiceRecorder] View detached, cleanup dispatched to appScope" }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        recordView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        recordingCallback?.invoke(RecordingState.RecordPermissionRequired)
                    } else {
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
        VoiceRecordingTracker.setRecording(true, "start")
        gestureTarget = GestureTarget.NONE

        recordingStartTime = System.currentTimeMillis()
        outputDir = File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY))

        tvEffectLabel.text = "✨ ${context.getString(R.string.chat_voice_add_effect)}"

        llActionButtons.visibility = View.VISIBLE
        btnCancel.setBackgroundResource(R.drawable.chat_voice_pill_bg_gray)
        btnAddEffect.setBackgroundResource(R.drawable.chat_voice_pill_bg_gray)
        tvCancelHint.visibility = View.INVISIBLE
        tvEffectHint.visibility = View.INVISIBLE

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
            while (isActive && isRecording) {
                val elapsedTime = System.currentTimeMillis() - recordingStartTime
                val remainingTime = MAX_RECORDING_DURATION_MS - elapsedTime

                if (remainingTime in 1..COUNTDOWN_THRESHOLD_MS) {
                    val secondsRemaining = (remainingTime / 1000).toInt()
                    tvStop.visibility = View.VISIBLE
                    tvStop.text = context.resources.getQuantityString(R.plurals.chat_voice_recording_will_stop, secondsRemaining, secondsRemaining)
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

    private fun launchMediaRecorder() {
        startJob = viewScope.launch {
            val started = withContext(Dispatchers.IO) { startDualCandidateRecorder() }
            if (started && isRecording) {
                startAmplitudeUpdates()
            }
        }
    }

    private fun startDualCandidateRecorder(): Boolean {
        try {
            if (!isRecording) {
                L.i { "[VoiceRecorder] startDualCandidateRecorder skipped, no longer recording" }
                return false
            }
            val dir = outputDir ?: run {
                L.w { "[VoiceRecorder] outputDir null at start, aborting" }
                return false
            }
            val recipes = VoiceMessageRecipes.buildRandomRecipes()
            val recorder = DualCandidateVoiceRecorder(
                context = context.applicationContext,
                outputDir = dir,
                recipes = recipes,
                denoiseModel = VoiceMessageRecipes.DEFAULT_DENOISE_MODEL,
                fileDeleter = ::deleteCandidateFile,
                callbacks = object : DualCandidateVoiceRecorder.Callbacks {
                    override fun onStarted() {}
                    override fun onStopRequested() {}
                    override fun onStopped(candidates: List<VoiceMessageRecordingCandidate>) {}
                    override fun onCancelled() {}
                    override fun onError(message: String) {
                        L.w { "[VoiceRecorder] dual-candidate recorder error: $message" }
                    }
                },
            )
            voiceRecorder = recorder
            recorder.start()
            return true
        } catch (e: Exception) {
            L.w(e) { "[VoiceRecorder] start record failed" }
            gestureTarget = GestureTarget.CANCEL
            viewScope.launch { stopRecording() }
            return false
        }
    }

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestFocusAndRecord() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (!isRecording) {
                        L.i { "[VoiceRecorder] Audio focus changed but not recording, ignore" }
                        return@setOnAudioFocusChangeListener
                    }
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            L.i { "[VoiceRecorder] Audio focus lost ($focusChange), cancel recording" }
                            gestureTarget = GestureTarget.CANCEL
                            try {
                                stopRecording()
                            } catch (e: Exception) {
                                L.i { "[VoiceRecorder] Stop recording failed: ${e.message}" }
                            }
                        }
                    }
                }
                .build()

            audioFocusRequest?.let {
                val focusRequestResult = audioManager?.requestAudioFocus(it)
                if (focusRequestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    L.i { "[VoiceRecorder] Got audio focus, start dual-candidate recorder" }
                    launchMediaRecorder()
                } else {
                    L.w { "[VoiceRecorder] Audio focus denied (result=$focusRequestResult), abort recording" }
                    abortRecording(RecordingState.Reason.AUDIO_FOCUS_DENIED)
                }
            }
        } else {
            launchMediaRecorder()
        }
    }

    private fun startAmplitudeUpdates() {
        amplitudeUpdateJob = viewScope.launch(Dispatchers.IO) {
            while (isActive && isRecording) {
                try {
                    val amplitude = voiceRecorder?.readPeakAmplitude()?.toFloat() ?: 0f
                    withContext(Dispatchers.Main) {
                        waveformView.updateAmplitude(amplitude)
                    }
                } catch (e: Exception) {
                    L.w { "[VoiceRecorder] amplitude update failed: ${e.stackTraceToString()}" }
                    break
                }
                delay(100)
            }
        }
    }

    private fun isPointInsideView(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX.toInt() in location[0]..(location[0] + view.width) &&
            rawY.toInt() in location[1]..(location[1] + view.height)
    }

    private fun handleMove(currentX: Float, currentY: Float) {
        val previousTarget = gestureTarget

        gestureTarget = when {
            isPointInsideView(btnCancel, currentX, currentY) -> GestureTarget.CANCEL
            isPointInsideView(btnAddEffect, currentX, currentY) -> GestureTarget.ADD_EFFECT
            else -> GestureTarget.NONE
        }

        if (gestureTarget == previousTarget) return

        when (gestureTarget) {
            GestureTarget.CANCEL -> {
                btnCancel.setBackgroundResource(R.drawable.chat_voice_pill_bg_red)
                btnAddEffect.setBackgroundResource(R.drawable.chat_voice_pill_bg_gray)
                tvCancelHint.visibility = View.VISIBLE
                tvEffectHint.visibility = View.INVISIBLE
                recordButton.text = context.getString(R.string.chat_voice_release_to_send)
                recordButton.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_disable))
                waveformView.setBarColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_disable))
                recordView.setBackgroundResource(R.drawable.chat_msg_input_bg)
            }
            GestureTarget.ADD_EFFECT -> {
                btnCancel.setBackgroundResource(R.drawable.chat_voice_pill_bg_gray)
                btnAddEffect.setBackgroundResource(R.drawable.chat_voice_pill_bg_gradient)
                tvCancelHint.visibility = View.INVISIBLE
                tvEffectHint.visibility = View.VISIBLE
                recordButton.text = context.getString(R.string.chat_voice_release_to_send)
                recordButton.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_disable))
                waveformView.setBarColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_disable))
                recordView.setBackgroundResource(R.drawable.chat_msg_input_bg)
            }
            GestureTarget.NONE -> {
                btnCancel.setBackgroundResource(R.drawable.chat_voice_pill_bg_gray)
                btnAddEffect.setBackgroundResource(R.drawable.chat_voice_pill_bg_gray)
                tvCancelHint.visibility = View.INVISIBLE
                tvEffectHint.visibility = View.INVISIBLE
                recordButton.text = context.getString(R.string.chat_voice_release_to_send)
                recordButton.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary_night))
                waveformView.setBarColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary_night))
                recordView.setBackgroundResource(R.drawable.chat_voice_record_blue_bg)
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        val gesture = gestureTarget
        VoiceRecordingTracker.setRecording(false, if (gesture == GestureTarget.CANCEL) "cancel" else "stop")

        val recordingDuration = System.currentTimeMillis() - recordingStartTime

        amplitudeUpdateJob?.cancel()
        countdownJob?.cancel()

        // Reset UI immediately so the user gets instant visual feedback on
        // finger release. Recording finalization (encoder EOS drain, pipeline
        // release) can take 1-3 s and must not block the UI.
        resetButton()
        onRecordingDismissed?.invoke()

        stopJob = viewScope.launch(Dispatchers.IO) {
            startJob?.join()

            val rec = voiceRecorder
            val candidates: List<VoiceMessageRecordingCandidate>? = try {
                if (rec == null) {
                    null
                } else if (gesture == GestureTarget.CANCEL) {
                    rec.cancel()
                    withTimeoutOrNull(RECORDER_STOP_TIMEOUT_MS) { rec.awaitResult() }
                    null
                } else {
                    rec.stop()
                    val result = withTimeoutOrNull(RECORDER_STOP_TIMEOUT_MS) { rec.awaitResult() }
                    if (result == null) {
                        L.w { "[VoiceRecorder] recorder.awaitResult() timed out after ${RECORDER_STOP_TIMEOUT_MS} ms" }
                    }
                    result
                }
            } catch (e: Exception) {
                L.w(e) { "[VoiceRecorder] Error awaiting recorder result" }
                null
            } finally {
                voiceRecorder = null
                // Ensure the recorder's internal scope is cancelled so the
                // AudioRecord is released even if awaitResult() timed out.
                // releaseAndJoin() is idempotent: if cleanup() already ran
                // (normal path), the join returns immediately and cancel is a
                // no-op on an already-idle scope.
                try { rec?.releaseAndJoin() } catch (_: Exception) {}
                try { releaseAudioFocus() } catch (e: Exception) {
                    L.i { "[VoiceRecorder] release audio focus failed: ${e.message}" }
                }
            }

            val terminalState: RecordingState = when {
                gesture == GestureTarget.CANCEL -> {
                    L.i { "[VoiceRecorder] Recording cancelled, files deleted." }
                    RecordingState.Cancelled
                }

                recordingDuration < MIN_RECORDING_DURATION_MS -> {
                    L.i { "[VoiceRecorder] Recording too short, files deleted." }
                    candidates?.forEach { it.file?.let(::deleteCandidateFile) }
                    RecordingState.TooShort
                }

                candidates == null || candidates.all { it.file == null } -> {
                    L.w { "[VoiceRecorder] no candidate files after stop (recorder failure)" }
                    RecordingState.RecordFailed(RecordingState.Reason.RECORDER_INIT_FAILED)
                }

                else -> {
                    val picked = pickCandidateByGesture(gesture, candidates)
                    val pickedFile = picked?.file
                    val pickedLength = pickedFile?.length() ?: 0L
                    when {
                        pickedFile == null || pickedLength == 0L -> {
                            L.w {
                                "[VoiceRecorder] picked candidate not usable " +
                                    "(file=${pickedFile?.absolutePath} length=$pickedLength)"
                            }
                            candidates.forEach { it.file?.let(::deleteCandidateFile) }
                            RecordingState.RecordFailed(RecordingState.Reason.RECORDER_INIT_FAILED)
                        }
                        pickedLength > 10 * 1024 * 1024 -> {
                            candidates.forEach { it.file?.let(::deleteCandidateFile) }
                            RecordingState.TooLarge
                        }
                        else -> {
                            deleteUnpickedCandidates(picked, candidates)
                            L.i {
                                "[VoiceRecorder] Recording saved. gesture=$gesture " +
                                    "duration=$recordingDuration " +
                                    "pickedRecipe=${picked.recipe.id} pickedSize=$pickedLength " +
                                    "candidates=" + candidates.joinToString { "${it.recipe.id}(${it.file?.length() ?: 0}B)" }
                            }
                            RecordingState.StoppedWithCandidates(
                                pickedFilePath = pickedFile.absolutePath,
                                candidates = candidates,
                            )
                        }
                    }
                }
            }

            when (terminalState) {
                is RecordingState.StoppedWithCandidates -> lastCandidates = terminalState.candidates
                else -> lastCandidates = emptyList()
            }

            withContext(Dispatchers.Main) {
                if (!isAttachedToWindow) {
                    L.i { "[VoiceRecorder] View detached, skip callback" }
                    return@withContext
                }
                // Defensive: ensure dismiss even if onRecordingDismissed was not
                // set by the host (the synchronous call already fired, so this
                // is a no-op for well-behaved callers).
                onRecordingDismissed?.invoke()
                recordingCallback?.invoke(terminalState)
            }
        }
    }

    /**
     * Pick a candidate based on the user's gesture:
     * - [GestureTarget.ADD_EFFECT] → prefer denoise+effect candidate
     * - [GestureTarget.NONE] → prefer denoise-only candidate (original voice)
     * Falls back through usable candidates if the preferred one is missing.
     */
    private fun pickCandidateByGesture(
        gesture: GestureTarget,
        candidates: List<VoiceMessageRecordingCandidate>,
    ): VoiceMessageRecordingCandidate? {
        fun usable(c: VoiceMessageRecordingCandidate): Boolean {
            val f = c.file ?: return false
            return f.exists() && f.length() > 0
        }

        return when (gesture) {
            GestureTarget.ADD_EFFECT -> {
                candidates.firstOrNull { usable(it) && it.recipe.effect != null }
                    ?: candidates.firstOrNull { usable(it) }
            }
            else -> {
                candidates.firstOrNull { usable(it) && it.recipe.denoise && it.recipe.effect == null }
                    ?: candidates.firstOrNull { usable(it) }
            }
        }
    }

    private fun deleteUnpickedCandidates(
        picked: VoiceMessageRecordingCandidate,
        candidates: List<VoiceMessageRecordingCandidate>,
    ) {
        candidates.forEach { candidate ->
            if (candidate !== picked) {
                candidate.file?.let(::deleteCandidateFile)
            }
        }
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager?.abandonAudioFocusRequest(it)
            }
        }
    }

    private fun abortRecording(reason: RecordingState.Reason) {
        isRecording = false
        VoiceRecordingTracker.setRecording(false, "abort")
        countdownJob?.cancel()
        amplitudeUpdateJob?.cancel()
        deleteRecordingFiles()
        releaseAudioFocus()
        resetButton()
        onRecordingDismissed?.invoke()
        recordingCallback?.invoke(RecordingState.RecordFailed(reason))
    }

    private fun resetButton() {
        recordButton.text = context.getString(R.string.chat_voice_hold_to_talk)
        recordButton.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary))
        waveformView.setBarColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary_night))
        recordView.setBackgroundResource(R.drawable.chat_msg_input_bg)

        llActionButtons.visibility = View.INVISIBLE
        btnCancel.setBackgroundResource(R.drawable.chat_voice_pill_bg_gray)
        btnAddEffect.setBackgroundResource(R.drawable.chat_voice_pill_bg_gray)
        tvCancelHint.visibility = View.INVISIBLE
        tvEffectHint.visibility = View.INVISIBLE
        tvStop.visibility = View.GONE
        waveformView.visibility = View.GONE
        waveformView.stopAnimation()
    }

    private fun deleteRecordingFiles() {
        lastCandidates.forEach { it.file?.let(::deleteCandidateFile) }
        lastCandidates = emptyList()
    }

    private fun deleteCandidateFile(file: File) {
        MyBlobProvider.getInstance().delete(file.absolutePath.toUri())
    }
}

sealed class RecordingState {
    data object Started : RecordingState()
    data class StoppedWithCandidates(
        val pickedFilePath: String,
        val candidates: List<VoiceMessageRecordingCandidate>,
    ) : RecordingState()
    data object TooShort : RecordingState()
    data object Cancelled : RecordingState()
    data object RecordPermissionRequired : RecordingState()
    data object TooLarge : RecordingState()
    data class RecordFailed(val reason: Reason) : RecordingState()

    enum class Reason {
        AUDIO_FOCUS_DENIED,
        RECORDER_INIT_FAILED,
    }
}
