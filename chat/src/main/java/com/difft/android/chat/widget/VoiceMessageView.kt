package com.difft.android.chat.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.databinding.VoiceMessageViewBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.canAutoSaveAttachment
import com.difft.android.chat.message.shouldDecrypt
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.isAudioFile
import com.hi.dhl.binding.viewbind
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import java.util.Date

/**
 * Remember call release function when this view is not used anymore
 */
class VoiceMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "VoiceMessageView"
    }

    val binding: VoiceMessageViewBinding by viewbind(this)

    private var message: TextChatMessage? = null
    private var attachmentPath: String = ""

    fun setAudioMessage(audioMessage: TextChatMessage) {
        this.message = audioMessage
        val attachment = audioMessage.attachment ?: return
        val fileName: String = attachment.fileName ?: ""
        attachmentPath = FileUtil.getMessageAttachmentFilePath(audioMessage.id) + fileName
        if (attachment.size > FileUtil.MAX_SUPPORT_FILE_SIZE) {
            binding.playTime.text = context.getString(R.string.max_support_file_size_50)
            return
        }

        if (attachment.isAudioFile()) {
            binding.clFileName.visibility = VISIBLE
            binding.tvFileName.text = fileName
        } else {
            binding.clFileName.visibility = GONE
        }

        binding.progressBar.visibility = View.GONE

        val progress = FileUtil.progressMap[audioMessage.id]
        val isFileValid = FileUtil.isFileValid(attachmentPath) || FileUtil.isFileValid("$attachmentPath.encrypt")

        if (isFileValid && (audioMessage.isMine || attachment.status == AttachmentStatus.SUCCESS.code || progress == 100)) {
            setupAudioView()
        }
        if (attachment.status != AttachmentStatus.FAILED.code
            && progress != -1
            && attachment.status != AttachmentStatus.SUCCESS.code
            && progress != 100
            || !isFileValid
        ) {
            if (progress == null) {
                downloadAndSetupMediaPlayer(audioMessage, attachmentPath)
            } else {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.progress = progress
            }
        }

        if (AudioMessageManager.currentPlayingMessage?.id == message?.id) {
            if (AudioMessageManager.isPaused) {
                binding.playButton.setImageResource(R.drawable.ic_chat_audio_item_play)
                binding.audioWaveProgressBar.setProgress(AudioMessageManager.currentProgress)
            } else {
                binding.playButton.setImageResource(R.drawable.ic_chat_audio_item_pause)
                binding.audioWaveProgressBar.setProgress(AudioMessageManager.currentProgress)
            }
        } else {
            binding.playButton.setImageResource(R.drawable.ic_chat_audio_item_play)
            binding.audioWaveProgressBar.setProgress(0f)
//            playTime.text = formatTime(attachment.totalTime ?: 0L)
        }

        if (AudioMessageManager.currentPlayingMessage?.id == message?.id) {
            binding.audioWaveProgressBar.setProgress(AudioMessageManager.currentProgress)
            val currentTotalTime = message?.attachment?.totalTime ?: 0L
            binding.playTime.text = formatTime((currentTotalTime * (1 - AudioMessageManager.currentProgress)).toLong())
        }
    }

    private fun setupAudioView() {
        setupPlayButtonClickListener()

        val attachment = message?.attachment ?: return

        if ((attachment.totalTime == 0L || attachment.amplitudes.isNullOrEmpty())) {
            L.d { "[VoiceMessageView] start extract Amplitudes" }
            binding.audioWaveProgressBar.setAmplitudes(emptyList())
            binding.playTime.text = formatTime(0)
            message?.let {
                AudioAmplitudesHelper.extractAmplitudesFromAacFile(attachmentPath, it)
            }
        } else {
//            L.d { "[VoiceMessageView] no need extract Amplitudes:" + " ${attachment.amplitudes?.size}  ${attachment.totalTime}" }
            binding.audioWaveProgressBar.setAmplitudes(attachment.amplitudes ?: emptyList())
            binding.playTime.text = formatTime(attachment.totalTime ?: 0)
        }
    }

    private fun downloadAndSetupMediaPlayer(audioMessage: TextChatMessage, attachmentPath: String) {
        audioMessage.attachment?.key?.let { key ->
            ApplicationDependencies.getJobManager().add(
                DownloadAttachmentJob(
                    audioMessage.id,
                    audioMessage.attachment?.id ?: "",
                    attachmentPath,
                    audioMessage.attachment?.authorityId ?: 0,
                    key,
                    audioMessage.shouldDecrypt(),
                    audioMessage.canAutoSaveAttachment()
                )
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPlayButtonClickListener() {
        binding.playButton.setOnClickListener {
            message?.let {
                AudioMessageManager.playOrPauseAudio(it, attachmentPath)
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
                } else { //大于300视为长按
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

    // 设置已播放部分颜色
    fun setProgressColor(color: Int) {
        binding.audioWaveProgressBar.setProgressColor(color)
    }

    // 设置游标颜色
    fun setCursorColor(color: Int) {
        binding.audioWaveProgressBar.setCursorColor(color)
    }

    fun unbind() {
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unbind()
    }
}