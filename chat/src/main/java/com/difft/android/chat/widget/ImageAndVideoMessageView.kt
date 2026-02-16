package com.difft.android.chat.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutImageMessageViewBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.message.shouldDecrypt
import com.hi.dhl.binding.viewbind
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.isVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.File
import kotlin.math.max
import kotlin.math.min

class ImageAndVideoMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    val binding: LayoutImageMessageViewBinding by viewbind(this)

    private var progressJob: Job? = null
    private var currentAttachmentId: String? = null
    private var currentMessage: TextChatMessage? = null
    private var currentShouldSaveToPhotos: Boolean = false
    private var currentContainerWidth: Int = 0

    @SuppressLint("SetTextI18n")
    fun setupImageView(message: TextChatMessage, shouldSaveToPhotos: Boolean = false, containerWidth: Int = 0) {
        currentShouldSaveToPhotos = shouldSaveToPhotos
        currentContainerWidth = containerWidth
        currentAttachmentId = message.id
        currentMessage = message

        // Reset all status views
        hideAllStatusViews()

        val attachment = message.attachment ?: return

        val isVideo = attachment.isVideo()
        val fileName: String = attachment.fileName ?: ""
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(message.id) + fileName

        // Calculate and set image dimensions
        setupImageDimensions(attachment, attachmentPath, isVideo)

        val progress = message.getAttachmentProgress()
        val isFileValid = FileUtil.isFileValid(attachmentPath)
        val isCurrentDeviceSend = message.isMine && message.id.last().digitToIntOrNull() == DEFAULT_DEVICE_ID

        // Distinguish upload/download state based on whether sent from current device
        if (isCurrentDeviceSend) {
            // Current device send - upload state
            handleUploadState(progress, isFileValid, attachmentPath, attachment, isVideo)
        } else {
            // Other device send or sync message - download state
            handleDownloadState(message, progress, isFileValid, attachmentPath, attachment, isVideo)
        }
    }

    /**
     * Handle upload state (sent from current device)
     */
    private fun handleUploadState(
        progress: Int?,
        isFileValid: Boolean,
        attachmentPath: String,
        attachment: difft.android.messageserialization.model.Attachment,
        isVideo: Boolean
    ) {
        // Load image
        if (isFileValid) {
            loadImage(attachmentPath, attachment.size, attachment.contentType)
        }

        // Show progress while uploading
        if (progress != null && progress in 0..99) {
            showUploadProgress(progress)
        } else if (isFileValid) {
            // Upload complete, show video play button
            binding.playButton.visibility = if (isVideo) View.VISIBLE else View.GONE
        }
    }

    /**
     * Handle download state (sent from other device or sync message)
     */
    private fun handleDownloadState(
        message: TextChatMessage,
        progress: Int?,
        isFileValid: Boolean,
        attachmentPath: String,
        attachment: difft.android.messageserialization.model.Attachment,
        isVideo: Boolean
    ) {
        // Priority 1: Check expired state
        val isExpired = if (progress != null) {
            progress == -2
        } else {
            attachment.status == AttachmentStatus.EXPIRED.code
        }

        if (isExpired) {
            showExpiredState()
            return
        }

        // Priority 2: Check failed state
        val isFailed = if (progress != null) {
            progress == -1
        } else {
            attachment.status == AttachmentStatus.FAILED.code
        }

        if (isFailed) {
            showRetryState()
            return
        }

        // Priority 3: Large file manual download prompt (>10MB)
        val fileSize = attachment.size
        val isLargeFile = fileSize > FileUtil.LARGE_FILE_THRESHOLD
        if (isLargeFile && (attachment.status != AttachmentStatus.SUCCESS.code && progress != 100 || !isFileValid) && progress == null) {
            showManualDownloadState(fileSize)
            return
        }

        // Load downloaded image
        if (isFileValid) {
            loadImage(attachmentPath, attachment.size, attachment.contentType)
        }

        // Priority 4: Downloading or auto download
        if (attachment.status != AttachmentStatus.SUCCESS.code && progress != 100 || !isFileValid) {
            if (progress == null) {
                // Auto download
                downloadAttachment(message, attachmentPath)
                showDownloadingState(0)
            } else {
                // Show download progress
                showDownloadingState(progress)
            }
        } else if (isFileValid) {
            // Download complete, show video play button
            binding.playButton.visibility = if (isVideo) View.VISIBLE else View.GONE
        }
    }

    /**
     * Hide all status views
     */
    private fun hideAllStatusViews() {
        binding.playButton.visibility = View.GONE
        binding.uploadProgressView.visibility = View.GONE
        binding.downloadStatusView.visibility = View.GONE
        binding.expiredStatusView.visibility = View.GONE
    }

    /**
     * Show upload progress - horizontal progress bar style
     */
    private fun showUploadProgress(progress: Int) {
        binding.uploadProgressView.visibility = View.VISIBLE
        binding.uploadProgressBar.progress = progress
    }

    /**
     * Show downloading state
     */
    private fun showDownloadingState(progress: Int) {
        binding.downloadStatusView.visibility = View.VISIBLE
        binding.downloadCircleBg.visibility = View.VISIBLE
        binding.downloadProgressRing.visibility = View.VISIBLE
        binding.downloadProgressRing.setProgress(progress)
        binding.downloadStatusIcon.setImageResource(R.drawable.ic_media_download)
        binding.downloadStatusText.visibility = View.GONE
        // Progress ring shows actual progress, no rotation animation needed
    }

    /**
     * Show retry state
     */
    private fun showRetryState() {
        binding.downloadStatusView.visibility = View.VISIBLE
        binding.downloadCircleBg.visibility = View.VISIBLE
        binding.downloadProgressRing.visibility = View.GONE
        binding.downloadStatusIcon.setImageResource(R.drawable.ic_media_retry)
        binding.downloadStatusText.visibility = View.GONE
    }

    /**
     * Show manual download state
     */
    private fun showManualDownloadState(fileSize: Int) {
        binding.downloadStatusView.visibility = View.VISIBLE
        binding.downloadCircleBg.visibility = View.VISIBLE
        binding.downloadProgressRing.visibility = View.GONE
        binding.downloadStatusIcon.setImageResource(R.drawable.ic_media_download)
        binding.downloadStatusText.visibility = View.VISIBLE
        binding.downloadStatusText.text = FileUtil.readableFileSize(fileSize.toLong())
    }

    /**
     * Show expired state
     */
    private fun showExpiredState() {
        binding.expiredStatusView.visibility = View.VISIBLE
    }

    /**
     * Setup image dimensions
     */
    private fun setupImageDimensions(
        attachment: difft.android.messageserialization.model.Attachment,
        attachmentPath: String,
        isVideo: Boolean
    ) {
        var finalWidth: Int
        var finalHeight: Int
        // Use containerWidth if available (passed from Adapter), otherwise fallback to displayMetrics
        val displayWidth = Resources.getSystem().displayMetrics.widthPixels
        val containerWidth = if (currentContainerWidth > 0) {
            currentContainerWidth
        } else {
            displayWidth
        }
        val containerHeight = Resources.getSystem().displayMetrics.heightPixels

        val maxWidth = containerWidth - 70.dp
        val maxHeight = (containerHeight / 3f).toInt()

        val minAspectRatio = 1f / 6f
        val maxAspectRatio = 6f

        val effectiveWidth: Int
        val effectiveHeight: Int

        if (attachment.width > 0 && attachment.height > 0) {
            effectiveWidth = attachment.width
            effectiveHeight = attachment.height
        } else {
            val mimeType = MediaUtil.getMimeType(context, attachmentPath.toUri()) ?: ""
            val actualDimensions = MediaUtil.getMediaWidthAndHeight(attachmentPath, mimeType)
            effectiveWidth = actualDimensions.first
            effectiveHeight = actualDimensions.second
        }

        if (effectiveWidth > 0 && effectiveHeight > 0) {
            var ratio = effectiveWidth.toFloat() / effectiveHeight
            ratio = max(minAspectRatio, min(maxAspectRatio, ratio))

            val originalRatio = effectiveWidth.toFloat() / effectiveHeight
            val isWideImage = originalRatio > 1f

            var tempWidth: Int
            var tempHeight: Int

            if (isWideImage) {
                tempWidth = minOf(effectiveWidth, maxWidth)
                tempHeight = (tempWidth / ratio).toInt()

                if (tempHeight > maxHeight) {
                    tempHeight = maxHeight
                    tempWidth = (tempHeight * ratio).toInt()
                }
            } else {
                tempHeight = minOf(effectiveHeight, maxHeight)
                tempWidth = (tempHeight * ratio).toInt()

                if (tempWidth > maxWidth) {
                    tempWidth = maxWidth
                    tempHeight = (tempWidth / ratio).toInt()
                }
            }

            finalWidth = tempWidth
            finalHeight = tempHeight
        } else {
            val defaultRatio = if (isVideo) 16f / 9f else 4f / 3f
            finalWidth = minOf(maxWidth, (maxHeight * defaultRatio).toInt())
            finalHeight = (finalWidth / defaultRatio).toInt()
        }

        val layoutParams = binding.imageView.layoutParams
        layoutParams.width = finalWidth
        layoutParams.height = finalHeight
        binding.imageView.layoutParams = layoutParams
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (progressJob == null) {
            findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                FileUtil.progressUpdate
                    .filter { it == currentAttachmentId }
                    .collect {
                        withContext(Dispatchers.Main) {
                            currentMessage?.let { setupImageView(it, currentShouldSaveToPhotos, currentContainerWidth) }
                        }
                    }
            }?.also { progressJob = it }
        }
    }

    override fun onDetachedFromWindow() {
        progressJob?.cancel()
        progressJob = null
        super.onDetachedFromWindow()
    }

    private fun loadImage(attachmentPath: String, expectedSize: Int, contentType: String) {
        val file = File(attachmentPath)
        val fileLastModified = file.lastModified()
        val actualFileSize = file.length()

        Glide.with(context)
            .load(attachmentPath)
            .signature(ObjectKey(fileLastModified))
            .transform(CenterCrop(), RoundedCorners(6.dp))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    L.e { "[MediaMsg] Load FAILED - path: $attachmentPath, contentType: $contentType, expectedSize: $expectedSize, actualFileSize: $actualFileSize, lastModified: $fileLastModified, error: ${e?.rootCauses?.joinToString { it.message ?: "unknown" }}" }
                    return false
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    return false
                }
            })
            .into(binding.imageView)
    }

    private fun downloadAttachment(message: TextChatMessage, attachmentPath: String) {
        message.attachment?.key?.let { key ->
            val autoSave = currentShouldSaveToPhotos && !message.isConfidential()
            ApplicationDependencies.getJobManager().add(
                DownloadAttachmentJob(
                    message.id,
                    message.attachment?.id ?: "",
                    attachmentPath,
                    message.attachment?.authorityId ?: 0,
                    key,
                    message.shouldDecrypt(),
                    autoSave
                )
            )
        }
    }

}