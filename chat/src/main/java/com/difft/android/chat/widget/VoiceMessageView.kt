package com.difft.android.chat.widget

import com.difft.android.chat.ui.BubblePalette
import android.content.res.ColorStateList
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.chat.R
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.AttachmentDownloadAction
import com.difft.android.chat.attachment.AttachmentDownloadDecision
import com.difft.android.chat.attachment.AttachmentStatusRepair
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.databinding.VoiceMessageViewBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.getAttachmentIdForProgress
import com.difft.android.chat.message.getAttachmentProgress
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.isAudioFile
import com.hi.dhl.binding.viewbind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.DownloadAttachmentJob
import java.util.Date




class VoiceMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    val binding: VoiceMessageViewBinding by viewbind(this)

    private var message: TextChatMessage? = null
    private var attachmentPath: String = ""
    
    private var currentAttachmentId: String? = null

    
    private var currentMessageId: String? = null

    
    var onConfidentialPlayStarted: ((TextChatMessage) -> Unit)? = null
    private var hasTriggeredConfidentialPlayStart = false

    
    private var downloadProgressJob: Job? = null
    private var playStatusJob: Job? = null
    private var audioProgressJob: Job? = null
    private var amplitudeJob: Job? = null

    @SuppressLint("SetTextI18n")
    fun setAudioMessage(audioMessage: TextChatMessage) {
        currentAttachmentId = audioMessage.getAttachmentIdForProgress()
        currentMessageId = audioMessage.id
        this.message = audioMessage
        val attachment = audioMessage.attachment ?: return
        val fileName: String = attachment.fileName ?: ""
        attachmentPath = AttachmentPathResolver.fileFor(attachment)

        if (attachment.isAudioFile()) {
            binding.clFileName.visibility = VISIBLE
            binding.tvFileName.text = fileName
        } else {
            binding.clFileName.visibility = GONE
        }

        binding.progressBar.visibility = View.GONE
        binding.tvDownloadHint.visibility = View.GONE
        
        
        
        binding.playButton.setOnClickListener(null)
        binding.playButton.isClickable = false
        binding.playButton.isEnabled = true

        val progress = audioMessage.getAttachmentProgress()
        
        
        
        val isFileValid = EncryptedAttachmentAccess.isReadable(attachmentPath, attachment.size)
        
        
        val isUploading = AttachmentDownloadDecision.isUploadInFlight(isFileValid, progress)
        
        
        if (isFileValid && !isUploading) AttachmentStatusRepair.markSuccessIfStale(attachment)

        if (AttachmentDownloadDecision.needsFetch(isFileValid)) {
            
            if (AttachmentDownloadDecision.isExpired(attachment.status, progress)) {
                showHintInsteadOfWaveform(context.getString(R.string.file_expired))
                return
            }

            
            if (AttachmentDownloadDecision.isFailed(attachment.status, progress)) {
                showHintInsteadOfWaveform(context.getString(R.string.download_failed))
                return
            }

            
            val fileSize = attachment.size
            if (AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid, fileSize, progress)) {
                val fileSizeText = FileUtil.readableFileSize(fileSize.toLong())
                showHintInsteadOfWaveform(context.getString(R.string.chat_tap_to_download) + " ($fileSizeText)")
                return
            }
        }
        
        binding.playButton.visibility = View.VISIBLE
        binding.audioWaveProgressBar.visibility = View.VISIBLE
        binding.playTime.visibility = View.VISIBLE

        
        
        
        if (isFileValid) {
            setupAudioView()
        }

        
        
        if (isUploading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = progress ?: 0
        } else when (AttachmentDownloadDecision.downloadAction(isFileValid, progress)) {
            AttachmentDownloadAction.AUTO_DOWNLOAD -> downloadAndSetupMediaPlayer(audioMessage, attachmentPath)

            AttachmentDownloadAction.SHOW_PROGRESS -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.progress = progress ?: 0
            }

            AttachmentDownloadAction.READY -> Unit
        }

        
        syncPlayStateUI()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupSubscriptions()
    }

    override fun onDetachedFromWindow() {
        cancelSubscriptions()
        super.onDetachedFromWindow()
    }

    





    private fun showHintInsteadOfWaveform(hint: String) {
        binding.playButton.visibility = View.VISIBLE
        binding.playButton.isEnabled = false
        binding.audioWaveProgressBar.visibility = View.INVISIBLE
        binding.playTime.visibility = View.INVISIBLE
        binding.tvDownloadHint.visibility = View.VISIBLE
        binding.tvDownloadHint.text = hint
    }

    private fun setupSubscriptions() {
        val lifecycleScope = findViewTreeLifecycleOwner()?.lifecycleScope ?: return

        
        if (downloadProgressJob == null) {
            lifecycleScope.launch {
                FileUtil.progressUpdate
                    .filter { it == currentAttachmentId }
                    .collect {
                        withContext(Dispatchers.Main) {
                            message?.let { setAudioMessage(it) }
                        }
                    }
            }.also { downloadProgressJob = it }
        }

        
        if (playStatusJob == null) {
            lifecycleScope.launch {
                AudioMessageManager.playStatusUpdate
                    .filter { it.first.id == currentMessageId }
                    .collect {
                        withContext(Dispatchers.Main) {
                            syncPlayStateUI()
                        }
                    }
            }.also { playStatusJob = it }
        }

        
        if (audioProgressJob == null) {
            lifecycleScope.launch {
                AudioMessageManager.progressUpdate
                    .filter { it.first.id == currentMessageId }
                    .collect { (msg, progress) ->
                        withContext(Dispatchers.Main) {
                            binding.audioWaveProgressBar.setProgress(progress)
                            val totalTime = msg.attachment?.totalTime ?: 0L
                            binding.playTime.text = formatTime((totalTime * (1 - progress)).toLong())
                        }
                    }
            }.also { audioProgressJob = it }
        }

        
        if (amplitudeJob == null) {
            lifecycleScope.launch {
                AudioAmplitudesHelper.amplitudeExtractionComplete
                    .filter { it.id == currentMessageId }
                    .collect { updatedMessage ->
                        withContext(Dispatchers.Main) {
                            message = updatedMessage
                            binding.audioWaveProgressBar.setAmplitudes(updatedMessage.attachment?.amplitudes ?: emptyList())
                            binding.playTime.text = formatTime(updatedMessage.attachment?.totalTime ?: 0)
                        }
                    }
            }.also { amplitudeJob = it }
        }
    }

    private fun cancelSubscriptions() {
        downloadProgressJob?.cancel()
        downloadProgressJob = null
        playStatusJob?.cancel()
        playStatusJob = null
        audioProgressJob?.cancel()
        audioProgressJob = null
        amplitudeJob?.cancel()
        amplitudeJob = null
    }

    




    private fun syncPlayStateUI() {
        val msg = message ?: return

        val isCurrentlyPlaying = AudioMessageManager.currentPlayingMessage?.id == msg.id

        if (isCurrentlyPlaying) {
            if (AudioMessageManager.isPaused) {
                binding.playButton.setImageResource(R.drawable.ic_chat_audio_item_play)
            } else {
                binding.playButton.setImageResource(R.drawable.ic_chat_audio_item_pause)
            }
            binding.audioWaveProgressBar.setProgress(AudioMessageManager.currentProgress)
            val totalTime = msg.attachment?.totalTime ?: 0L
            binding.playTime.text = formatTime((totalTime * (1 - AudioMessageManager.currentProgress)).toLong())
        } else {
            
            binding.playButton.setImageResource(R.drawable.ic_chat_audio_item_play)
            binding.audioWaveProgressBar.setProgress(0f)
            binding.playTime.text = formatTime(msg.attachment?.totalTime ?: 0)
        }
    }

    private fun setupAudioView() {
        setupPlayButtonClickListener()

        val attachment = message?.attachment ?: return

        if ((attachment.totalTime == 0L || attachment.amplitudes.isNullOrEmpty())) {
            L.d { "[VoiceMessageView] start extract Amplitudes" }
            binding.audioWaveProgressBar.setAmplitudes(emptyList())
            
            
            binding.playTime.text = formatTime(attachment.totalTime ?: 0)
            message?.let {
                AudioAmplitudesHelper.extractAmplitudesFromAacFile(attachmentPath, it)
            }
        } else {
            binding.audioWaveProgressBar.setAmplitudes(attachment.amplitudes ?: emptyList())
            binding.playTime.text = formatTime(attachment.totalTime ?: 0)
        }
    }

    private fun downloadAndSetupMediaPlayer(audioMessage: TextChatMessage, attachmentPath: String) {
        val attachment = audioMessage.attachment ?: return
        val key = attachment.key?.takeIf { it.isNotEmpty() } ?: return
        ApplicationDependencies.getJobManager().add(
            DownloadAttachmentJob(
                attachment.localId,
                audioMessage.id,
                attachment.id,
                attachmentPath,
                attachment.authorityId,
                key,
                false 
            )
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPlayButtonClickListener() {
        binding.playButton.setOnClickListener {
            message?.let { msg ->
                
                if (!hasTriggeredConfidentialPlayStart) {
                    hasTriggeredConfidentialPlayStart = true
                    onConfidentialPlayStarted?.invoke(msg)
                }
                AudioMessageManager.playOrPauseAudio(msg, attachmentPath)
            }
        }

        var time: Long = 0
        var isLonePressPerformed = false
        this.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                time = Date().time
                isLonePressPerformed = false
                false
            } else {
                val current = Date().time - time
                if (current < 300) {
                    false
                } else {
                    val itemView = LinkTextUtils.findParentChatMessageItemView(this)
                    if (itemView != null && !isLonePressPerformed) {
                        itemView.performLongClick()
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        isLonePressPerformed = true
                    }
                    true
                }
            }
        }
    }

    private fun formatTime(time: Long): String {
        if (time <= 0) return "0:00"
        val totalSeconds = time / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}:${seconds.toString().padStart(2, '0')}"
    }

    




    internal fun applyPalette(palette: BubblePalette, highlight: Int? = null) {
        binding.playButton.imageTintList = ColorStateList.valueOf(palette.text)
        binding.ivAudioFile.imageTintList = ColorStateList.valueOf(palette.icon)
        binding.tvFileName.setTextColor(palette.text)
        binding.playTime.setTextColor(palette.text)
        binding.tvDownloadHint.setTextColor(palette.meta)
        binding.audioWaveProgressBar.setTrackColor(palette.barUnplayed)
        binding.audioWaveProgressBar.setProgressColor(highlight ?: palette.barPlayed)
        binding.audioWaveProgressBar.setCursorColor(highlight ?: palette.text)
    }

    fun setProgressColor(color: Int) {
        binding.audioWaveProgressBar.setProgressColor(color)
    }

    fun setCursorColor(color: Int) {
        binding.audioWaveProgressBar.setCursorColor(color)
    }
}