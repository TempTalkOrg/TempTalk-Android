package com.difft.android.chat.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutImageMessageViewBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.canAutoSaveAttachment
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.shouldDecrypt
import com.hi.dhl.binding.viewbind
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.isVideo
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import org.thoughtcrime.securesms.util.MediaUtil
import kotlin.math.max
import kotlin.math.min

class ImageAndVideoMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    val binding: LayoutImageMessageViewBinding by viewbind(this)

    @SuppressLint("SetTextI18n")
    fun setupImageView(message: TextChatMessage) {

        binding.imageView.setImageResource(com.luck.picture.lib.R.drawable.ps_image_placeholder)
        binding.progressView.visibility = View.GONE
        binding.tvDownloadHint.visibility = View.GONE

        val attachment = message.attachment ?: return

        val isVideo = attachment.isVideo()
        binding.playButton.visibility = if (isVideo) View.VISIBLE else View.GONE

        val fileName: String = attachment.fileName ?: ""
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(message.id) + fileName

        var finalWidth: Int
        var finalHeight: Int
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels

        val maxWidth = screenWidth - 70.dp
        val maxHeight = (screenHeight / 3f).toInt() // 限制最大高度为屏幕高度的1/3

        val minAspectRatio = 1f / 6f
        val maxAspectRatio = 6f // 宽高比最大6:1

        // 首先尝试获取有效的宽高信息
        val effectiveWidth: Int
        val effectiveHeight: Int

        if (attachment.width > 0 && attachment.height > 0) {
            // 使用attachment中的宽高信息
            effectiveWidth = attachment.width
            effectiveHeight = attachment.height
        } else {
            // 尝试从文件中获取实际尺寸（支持图片和视频）
            val mimeType = MediaUtil.getMimeType(context, attachmentPath.toUri()) ?: ""
            val actualDimensions = MediaUtil.getMediaWidthAndHeight(attachmentPath, mimeType)
            effectiveWidth = actualDimensions.first
            effectiveHeight = actualDimensions.second
        }

        if (effectiveWidth > 0 && effectiveHeight > 0) {
            // 有有效尺寸信息，使用智能缩放逻辑
            var ratio = effectiveWidth.toFloat() / effectiveHeight
            ratio = max(minAspectRatio, min(maxAspectRatio, ratio))

            // 根据图片方向和尺寸智能选择限制策略
            val originalRatio = effectiveWidth.toFloat() / effectiveHeight
            val isWideImage = originalRatio > 1f // 宽图

            var tempWidth: Int
            var tempHeight: Int

            if (isWideImage) {
                // 宽图：优先按宽度限制
                tempWidth = minOf(effectiveWidth, maxWidth)
                tempHeight = (tempWidth / ratio).toInt()

                // 如果高度超出限制，按高度重新计算
                if (tempHeight > maxHeight) {
                    tempHeight = maxHeight
                    tempWidth = (tempHeight * ratio).toInt()
                }
            } else {
                // 长图：优先按高度限制
                tempHeight = minOf(effectiveHeight, maxHeight)
                tempWidth = (tempHeight * ratio).toInt()

                // 如果宽度超出限制，按宽度重新计算
                if (tempWidth > maxWidth) {
                    tempWidth = maxWidth
                    tempHeight = (tempWidth / ratio).toInt()
                }
            }

            finalWidth = tempWidth
            finalHeight = tempHeight
        } else {
            // 没有有效尺寸信息，使用默认比例
            val defaultRatio = if (isVideo) 16f / 9f else 4f / 3f
            finalWidth = minOf(maxWidth, (maxHeight * defaultRatio).toInt())
            finalHeight = (finalWidth / defaultRatio).toInt()
        }

        val layoutParams = binding.imageView.layoutParams
        layoutParams.width = finalWidth
        layoutParams.height = finalHeight
        binding.imageView.layoutParams = layoutParams

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
                // Show download prompt with file size (reuse fail view with different text)
                binding.tvDownloadHint.visibility = View.VISIBLE
                val fileSizeText = FileUtil.readableFileSize(fileSize.toLong())
                binding.tvDownloadHint.text = context.getString(R.string.chat_tap_to_download) + " ($fileSizeText)"
                return
            }
        }

        if (isFileValid) {
            loadImage(attachmentPath, finalWidth, finalHeight)
        }

        // Priority 3: Show progress or auto download (for files <= 10M)
        if (attachment.status != AttachmentStatus.SUCCESS.code && progress != 100 || !isFileValid) {
            if (progress == null) {
                if (!isCurrentDeviceSend) {
                    downloadAttachment(message, attachmentPath)
                }
            } else {
                binding.progressView.visibility = View.VISIBLE
                binding.progressBar.progress = progress
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

