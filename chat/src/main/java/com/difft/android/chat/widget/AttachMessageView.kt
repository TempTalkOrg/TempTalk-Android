package com.difft.android.chat.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutAttachMessageViewBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.canAutoSaveAttachment
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.shouldDecrypt
import com.hi.dhl.binding.viewbind
import difft.android.messageserialization.model.AttachmentStatus
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import org.thoughtcrime.securesms.util.viewFile

class AttachMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    val binding: LayoutAttachMessageViewBinding by viewbind(this)

    fun setupAttachmentView(message: TextChatMessage) {
        val attachment = message.attachment ?: return

        val fileName: String = attachment.fileName ?: ""
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(message.id) + fileName

        binding.open.visibility = View.INVISIBLE
        binding.progress.visibility = View.INVISIBLE
        binding.tvDownloadHint.visibility = View.INVISIBLE

        binding.attachmentName.text = attachment.fileName
        binding.attachmentSize.text = FileUtil.readableFileSize(attachment.size.toLong())

        val progress = message.getAttachmentProgress()
        val isFileValid = FileUtil.isFileValid(attachmentPath)

        val isCurrentDeviceSend = message.isMine && message.id.last().digitToIntOrNull() == DEFAULT_DEVICE_ID
        if (!isCurrentDeviceSend) {
            // Priority 1: Show expired view if file has expired
            val isExpired = if (progress != null) {
                progress == -2
            } else {
                attachment.status == AttachmentStatus.EXPIRED.code
            }

            if (isExpired) {
                binding.tvDownloadHint.visibility = View.VISIBLE
                binding.tvDownloadHint.text = context.getString(R.string.file_expired)
                return
            }

            // Priority 2: Show fail view if download failed
            val isFailed = if (progress != null) {
                progress == -1
            } else {
                attachment.status == AttachmentStatus.FAILED.code
            }

            if (isFailed) {
                binding.tvDownloadHint.visibility = View.VISIBLE
                binding.tvDownloadHint.text = context.getString(R.string.download_failed)
                return
            }

            // Priority 2: Show download prompt for files > 10M
            val fileSize = attachment.size
            val isLargeFile = fileSize > FileUtil.LARGE_FILE_THRESHOLD

            if (isLargeFile && (attachment.status != AttachmentStatus.SUCCESS.code && progress != 100 || !isFileValid) && progress == null) {
                // Show download prompt (reuse fail view with different text)
                binding.tvDownloadHint.visibility = View.VISIBLE
                binding.tvDownloadHint.text = context.getString(R.string.chat_tap_to_download)
                return
            }
        }

        if (isFileValid) {
            binding.open.visibility = View.VISIBLE
            binding.open.setOnClickListener {
                context.viewFile(attachmentPath)
            }
        }

        // Priority 3: Show progress or auto download (for files <= 10M)
        if (attachment.status != AttachmentStatus.SUCCESS.code && progress != 100 || !isFileValid) {
            if (progress == null) {
                if (!isCurrentDeviceSend) {
                    downloadAttachment(message, attachmentPath)
                }
            } else {
                binding.progress.visibility = View.VISIBLE
                binding.progress.setProgress(progress)
            }
        }
    }

    private fun downloadAttachment(message: TextChatMessage, attachmentPath: String) {
        message.attachment?.key?.let { key ->
            ApplicationDependencies.getJobManager().add(
                DownloadAttachmentJob(
                    message.id,
                    message.attachment?.id ?: "",
                    attachmentPath,
                    message.attachment?.authorityId ?: 0,
                    key,
                    message.shouldDecrypt(),
                    message.canAutoSaveAttachment()
                )
            )
        }
    }

    fun openFile() {
        binding.open.performClick()
    }
}

