package com.difft.android.chat.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutAttachMessageViewBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.canAutoSaveAttachment
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.shouldDecrypt
import difft.android.messageserialization.model.AttachmentStatus
import com.hi.dhl.binding.viewbind
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

        if (attachment.size > FileUtil.MAX_SUPPORT_FILE_SIZE) {
            binding.tvMaxLimit.visibility = View.VISIBLE
            binding.llContent.visibility = View.GONE

            binding.tvMaxLimit.text = context.getString(R.string.max_support_file_size_50)
        } else {
            binding.fail.visibility = View.INVISIBLE
            binding.open.visibility = View.INVISIBLE
            binding.progress.visibility = View.INVISIBLE
            binding.attachmentSize.visibility = View.INVISIBLE
            binding.attachmentFail.visibility = View.INVISIBLE

            binding.attachmentName.text = attachment.fileName


            binding.attachmentSize.visibility = View.VISIBLE
            binding.attachmentSize.text = FileUtil.readableFileSize(attachment.size.toLong())

            val progress = message.getAttachmentProgress()
            val isFileValid = FileUtil.isFileValid(attachmentPath)

            if (isFileValid) {
                binding.open.visibility = View.VISIBLE
                binding.open.setOnClickListener {
                    context.viewFile(attachmentPath)
                }
            }
            if (attachment.status != AttachmentStatus.FAILED.code
                && progress != -1
                && attachment.status != AttachmentStatus.SUCCESS.code
                && progress != 100
                || !isFileValid
            ) {
                if (progress == null) {
                    downloadAttachment(message, attachmentPath)
                } else {
                    binding.progress.visibility = View.VISIBLE
                    binding.progress.setProgress(progress)
                }
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

