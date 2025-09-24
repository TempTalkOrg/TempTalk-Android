package com.difft.android.chat.widget

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutImageMessageViewBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.canAutoSaveAttachment
import com.difft.android.chat.message.shouldDecrypt
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.isVideo
import com.hi.dhl.binding.viewbind
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import kotlin.math.max
import kotlin.math.min

class ImageAndVideoMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    val binding: LayoutImageMessageViewBinding by viewbind(this)

    fun setupImageView(message: TextChatMessage) {

        binding.imageView.setImageResource(com.luck.picture.lib.R.drawable.ps_image_placeholder)
        binding.progressView.visibility = View.GONE

        val attachment = message.attachment ?: return

        val isVideo = attachment.isVideo()
        binding.playButton.visibility = if (isVideo) View.VISIBLE else View.GONE

        val fileName: String = attachment.fileName ?: ""
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(message.id) + fileName

        if (attachment.size > FileUtil.MAX_SUPPORT_FILE_SIZE) {
            binding.tvMaxLimit.visibility = View.VISIBLE
            binding.clContent.visibility = View.GONE

            binding.tvMaxLimit.text = context.getString(R.string.max_support_file_size_50)
        } else {
            binding.tvMaxLimit.visibility = View.GONE
            binding.clContent.visibility = View.VISIBLE

            val finalWidth: Int
            val finalHeight: Int
            val screenWidth = Resources.getSystem().displayMetrics.widthPixels
            val screenHeight = Resources.getSystem().displayMetrics.heightPixels

            val minWidth = (screenWidth / 3f).toInt() // 最小宽度为屏幕宽度的1/3
            val maxWidth = screenWidth - 70.dp
            val maxHeight = (screenHeight / 3f).toInt() // 限制最大高度为屏幕高度的1/3

            val finalMaxWidth = if (maxWidth >= minWidth) maxWidth else minWidth

            val minAspectRatio = 1f / 3f
            val maxAspectRatio = 1 / minAspectRatio

            if (attachment.width > 0 && attachment.height > 0) {

                var ratio = attachment.width.toFloat() / attachment.height
                ratio = max(minAspectRatio, min(maxAspectRatio, ratio))

                // 先按宽度计算
                var tempWidth = attachment.width.takeIf { it != 0 }?.coerceIn(minWidth, finalMaxWidth) ?: finalMaxWidth
                var tempHeight = (tempWidth / ratio).toInt()

                // 如果高度超出限制，则按高度重新计算
                if (tempHeight > maxHeight) {
                    tempHeight = maxHeight
                    tempWidth = (tempHeight * ratio).toInt().coerceIn(minWidth, finalMaxWidth)
                }

                finalWidth = tempWidth
                finalHeight = tempHeight
            } else {
                finalWidth = maxWidth
                finalHeight = 0
            }

            val layoutParams = binding.imageView.layoutParams
            layoutParams.width = finalWidth
            layoutParams.height = finalHeight
            binding.imageView.layoutParams = layoutParams

            val progress = FileUtil.progressMap[message.id]
            val isFileValid = FileUtil.isFileValid(attachmentPath)
            if (isFileValid) {
                loadImage(attachmentPath, finalWidth, finalHeight)
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
                    binding.progressView.visibility = View.VISIBLE
                    binding.progressBar.progress = progress
                }
            }
        }
    }

    private fun loadImage(attachmentPath: String, width: Int, height: Int) {
        Glide.with(context)
            .load(attachmentPath)
//            .override(width, height)
            .placeholder(com.luck.picture.lib.R.drawable.ps_image_placeholder)
            .error(com.luck.picture.lib.R.drawable.ps_image_placeholder)
            .transform(CenterCrop(), RoundedCorners(6.dp))
            .into(binding.imageView)
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
}

