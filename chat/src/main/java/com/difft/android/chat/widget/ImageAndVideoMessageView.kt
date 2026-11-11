package com.difft.android.chat.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.getLifecycleOwner
import com.difft.android.base.utils.getSafeContext
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.difft.android.base.glide.EncryptedByteBufferResourceEncoder
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.ui.ChatMessageContainerView
import com.difft.android.chat.R
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.AttachmentDownloadAction
import com.difft.android.chat.attachment.AttachmentDownloadDecision
import com.difft.android.chat.attachment.AttachmentStatusRepair
import com.difft.android.chat.databinding.LayoutImageMessageViewBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.getAttachmentIdForProgress
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isConfidential
import com.hi.dhl.binding.viewbind
import difft.android.messageserialization.model.isPlayableVideo
import difft.android.messageserialization.model.normalizeContentType
import difft.android.messageserialization.model.isWhitelistedVideoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.DownloadAttachmentJob
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.MediaUtil
import java.io.File
import kotlin.math.max
import kotlin.math.min

class ImageAndVideoMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    val binding: LayoutImageMessageViewBinding by viewbind(this)

    private var progressJob: Job? = null
    private var currentAttachmentId: String? = null
    private var currentMessage: TextChatMessage? = null
    private var currentShouldSaveToPhotos: Boolean = false
    private var currentContainerWidth: Int = 0

    





    private var sizedForContentWidthPx: Int = 0

    







    private val roundedCornerOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, 16.dp.toFloat())
        }
    }

    @SuppressLint("SetTextI18n")
    fun setupImageView(message: TextChatMessage, shouldSaveToPhotos: Boolean = false, containerWidth: Int = 0) {
        currentShouldSaveToPhotos = shouldSaveToPhotos
        currentContainerWidth = containerWidth
        sizedForContentWidthPx = 0
        val previousAttachmentId = currentAttachmentId
        
        val attachmentId = message.getAttachmentIdForProgress()
        currentAttachmentId = attachmentId
        currentMessage = message

        
        
        binding.imageView.outlineProvider = roundedCornerOutline
        binding.imageView.clipToOutline = true

        
        
        
        
        hideAllStatusViews()
        if (previousAttachmentId != null && previousAttachmentId != attachmentId) {
            Glide.with(context.getSafeContext()).clear(binding.imageView)
            binding.imageView.setImageDrawable(null)
        }

        val attachment = message.attachment ?: return

        val isVideo = attachment.isPlayableVideo()
        val attachmentPath = AttachmentPathResolver.fileFor(attachment)

        
        setupImageDimensions(attachment, attachmentPath, isVideo)

        val progress = message.getAttachmentProgress()
        val isFileValid = EncryptedAttachmentAccess.isReadable(attachmentPath, attachment.size)
        
        
        val isUploading = AttachmentDownloadDecision.isUploadInFlight(isFileValid, progress)
        
        
        if (isFileValid && !isUploading) AttachmentStatusRepair.markSuccessIfStale(attachment)

        if (isUploading) {
            loadImage(attachmentPath, attachment.size, attachment.contentType)
            showUploadProgress(progress ?: 0)
            return
        }
        renderFetchState(message, progress, isFileValid, attachmentPath, attachment, isVideo)
    }

    



    private fun renderFetchState(
        message: TextChatMessage,
        progress: Int?,
        isFileValid: Boolean,
        attachmentPath: String,
        attachment: difft.android.messageserialization.model.Attachment,
        isVideo: Boolean
    ) {
        if (AttachmentDownloadDecision.needsFetch(isFileValid)) {
            
            if (AttachmentDownloadDecision.isExpired(attachment.status, progress)) {
                showExpiredState()
                return
            }

            
            if (AttachmentDownloadDecision.isFailed(attachment.status, progress)) {
                showRetryState()
                return
            }

            
            val fileSize = attachment.size
            if (AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid, fileSize, progress)) {
                showManualDownloadState(fileSize)
                return
            }
        }

        
        if (isFileValid) {
            loadImage(attachmentPath, attachment.size, attachment.contentType)
        }

        
        when (AttachmentDownloadDecision.downloadAction(isFileValid, progress)) {
            AttachmentDownloadAction.AUTO_DOWNLOAD -> {
                
                
                
                if (downloadAttachment(message, attachmentPath)) showDownloadingState(0)
            }

            AttachmentDownloadAction.SHOW_PROGRESS -> showDownloadingState(progress ?: 0)

            
            AttachmentDownloadAction.READY ->
                binding.playButton.visibility = if (isVideo) View.VISIBLE else View.GONE
        }
    }

    


    private fun hideAllStatusViews() {
        binding.playButton.visibility = View.GONE
        binding.uploadProgressView.visibility = View.GONE
        binding.downloadStatusView.visibility = View.GONE
        binding.expiredStatusView.visibility = View.GONE
    }

    


    private fun showUploadProgress(progress: Int) {
        binding.uploadProgressView.visibility = View.VISIBLE
        binding.uploadProgressBar.progress = progress
    }

    


    private fun showDownloadingState(progress: Int) {
        binding.downloadStatusView.visibility = View.VISIBLE
        binding.downloadCircleBg.visibility = View.VISIBLE
        binding.retryCircleBg.visibility = View.GONE
        binding.downloadProgressRing.visibility = View.VISIBLE
        binding.downloadProgressRing.setProgress(progress)
        binding.downloadStatusIcon.setImageResource(R.drawable.ic_media_download)
        binding.downloadStatusText.visibility = View.GONE
        
    }

    



    private fun showRetryState() {
        binding.downloadStatusView.visibility = View.VISIBLE
        binding.downloadCircleBg.visibility = View.GONE
        binding.retryCircleBg.visibility = View.VISIBLE
        binding.downloadProgressRing.visibility = View.GONE
        binding.downloadStatusIcon.setImageResource(R.drawable.ic_media_retry)
        binding.downloadStatusText.visibility = View.GONE
    }

    


    private fun showManualDownloadState(fileSize: Int) {
        binding.downloadStatusView.visibility = View.VISIBLE
        binding.downloadCircleBg.visibility = View.VISIBLE
        binding.retryCircleBg.visibility = View.GONE
        binding.downloadProgressRing.visibility = View.GONE
        binding.downloadStatusIcon.setImageResource(R.drawable.ic_media_download)
        binding.downloadStatusText.visibility = View.VISIBLE
        binding.downloadStatusText.text = FileUtil.readableFileSize(fileSize.toLong())
    }

    


    private fun showExpiredState() {
        binding.expiredStatusView.visibility = View.VISIBLE
    }

    


    private fun setupImageDimensions(
        attachment: difft.android.messageserialization.model.Attachment,
        attachmentPath: String,
        isVideo: Boolean
    ) {
        var finalWidth: Int
        var finalHeight: Int
        
        
        
        
        
        
        
        
        
        val containerWidth = resolvedContentWidthPx()
        sizedForContentWidthPx = containerWidth
        
        
        val containerHeight = chatContentHeightPx()

        
        val avatarColumn = generateSequence(parent) { it.parent }
            .filterIsInstance<ChatMessageContainerView>().firstOrNull()?.avatarColumn == true
        val maxWidth = containerWidth - 70.dp - if (avatarColumn) ChatMessageContainerView.AVATAR_COLUMN_EXTRA_DP.dp else 0
        val maxHeight = (containerHeight / 3f).toInt()

        val minAspectRatio = 1f / 6f
        val maxAspectRatio = 6f

        val effectiveWidth: Int
        val effectiveHeight: Int

        if (attachment.width > 0 && attachment.height > 0) {
            effectiveWidth = attachment.width
            effectiveHeight = attachment.height
        } else if (MediaUtil.isVideoType(normalizeContentType(attachment.contentType))) {
            
            
            effectiveWidth = 0
            effectiveHeight = 0
        } else {
            
            
            
            
            val actualDimensions = if (
                !EncryptedAttachmentAccess.hasPlaintext(attachmentPath) &&
                EncryptedAttachmentAccess.hasEncrypted(attachmentPath)
            ) {
                val uri = EncryptedAttachmentAccess.contentUriFromBasePath(attachmentPath)
                val mimeType = MediaUtil.getMimeType(context, uri) ?: attachment.contentType ?: ""
                MediaUtil.getMediaWidthAndHeight(context, uri, mimeType)
            } else {
                val mimeType = MediaUtil.getMimeType(context, attachmentPath.toUri()) ?: ""
                MediaUtil.getMediaWidthAndHeight(attachmentPath, mimeType)
            }
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

        
        
        
        if (effectiveWidth > 0 && effectiveHeight > 0) {
            val minWidth = 150.dp
            if (finalWidth < minWidth) {
                val ratio = max(minAspectRatio, min(maxAspectRatio, effectiveWidth.toFloat() / effectiveHeight))
                finalWidth = minOf(minWidth, maxWidth)
                finalHeight = (finalWidth / ratio).toInt()
                if (finalHeight > maxHeight) {
                    
                    
                    
                    finalHeight = maxHeight
                }
            }
        }

        val layoutParams = binding.imageView.layoutParams
        layoutParams.width = finalWidth
        layoutParams.height = finalHeight
        binding.imageView.layoutParams = layoutParams
    }

    









    private fun resolvedContentWidthPx(): Int {
        
        
        if (parent == null && currentContainerWidth > 0) {
            return minOf(currentContainerWidth, resources.getDimensionPixelSize(R.dimen.chat_content_max_width))
        }
        return chatContentWidthPx()
    }

    









    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val message = currentMessage
        if (message != null &&
            sizedForContentWidthPx != 0 &&
            sizedForContentWidthPx != resolvedContentWidthPx()
        ) {
            setupImageView(message, currentShouldSaveToPhotos, currentContainerWidth)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        
        
        
        
        currentMessage?.let { message ->
            if (sizedForContentWidthPx != 0 && sizedForContentWidthPx != resolvedContentWidthPx()) {
                setupImageView(message, currentShouldSaveToPhotos, currentContainerWidth)
            }
        }
        if (progressJob == null) {
            getLifecycleOwner()?.lifecycleScope?.launch {
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
        val plainFile = File(attachmentPath)
        val encryptedFile = EncryptedAttachmentAccess.encryptedFile(attachmentPath)

        
        
        
        
        
        val targetW = binding.imageView.layoutParams?.width ?: 0
        val targetH = binding.imageView.layoutParams?.height ?: 0

        
        
        
        
        
        val isEncryptedMedia = (contentType.contains("image") || isWhitelistedVideoType(contentType)) &&
            !plainFile.exists() && encryptedFile.exists()

        
        
        
        
        
        val isAnimatedType = contentType.contains("gif") || contentType.contains("webp")
        val useEncryptedResourceCache = isEncryptedMedia &&
            currentMessage?.isConfidential() != true &&
            GlideCacheKeyManager.isCacheKeyReady(context)

        
        
        val (model: Any, fileLastModified: Long, actualFileSize: Long) = if (isEncryptedMedia) {
            
            
            val uri = EncryptedAttachmentAccess.contentUriFromBasePath(attachmentPath)
            Triple(uri as Any, encryptedFile.lastModified(), encryptedFile.length())
        } else {
            Triple(attachmentPath as Any, plainFile.lastModified(), plainFile.length())
        }

        Glide.with(context.getSafeContext())
            .load(model)
            
            .apply { if (targetW > 0 && targetH > 0) override(targetW, targetH) }
            .apply {
                when {
                    
                    
                    useEncryptedResourceCache -> diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    
                    isEncryptedMedia -> diskCacheStrategy(DiskCacheStrategy.NONE)
                    
                }
            }
            
            
            .signature(ObjectKey(fileLastModified))
            .apply {
                
                
                
                
                
                if (useEncryptedResourceCache && isAnimatedType) {
                    set(EncryptedByteBufferResourceEncoder.ENCRYPT_ANIMATED_CACHE, true)
                }
            }
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    
                    L.e { "[MediaMsg] load failed ext=${File(attachmentPath).extension} contentType=$contentType expectedSize=$expectedSize actualFileSize=$actualFileSize lastModified=$fileLastModified error=${e?.rootCauses?.joinToString { it.message ?: "unknown" }}" }
                    return false
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    return false
                }
            })
            .into(binding.imageView)
    }

    
    private fun downloadAttachment(message: TextChatMessage, attachmentPath: String): Boolean {
        val attachment = message.attachment ?: return false
        val key = attachment.key?.takeIf { it.isNotEmpty() } ?: return false
        val autoSave = currentShouldSaveToPhotos && !message.isConfidential()
        ApplicationDependencies.getJobManager().add(
            DownloadAttachmentJob(
                attachment.localId,
                message.id,
                attachment.id,
                attachmentPath,
                attachment.authorityId,
                key,
                autoSave
            )
        )
        return true
    }

}