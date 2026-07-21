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
import com.difft.android.base.utils.windowHeightPx
import com.difft.android.base.utils.windowWidthPx
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
    private var loadImageJob: Job? = null
    private var currentAttachmentId: String? = null
    private var currentMessage: TextChatMessage? = null
    private var currentShouldSaveToPhotos: Boolean = false
    private var currentContainerWidth: Int = 0

    /**
     * Rounds the image corners at the VIEW level (6dp) — the sole corner mechanism, since no Glide
     * transform is applied. Corners can't be a Glide transform here because the encrypted RESOURCE
     * cache stores animated (gif/webp) content as untransformed source bytes, so a bitmap corner
     * transform would diverge on a cache hit (issue #1002: animated bubbles rendered square). A view
     * clip rounds ANY drawable (static / animated) regardless of the cache path; getOutline re-runs
     * on layout so it tracks the image's changing size. Cropping is handled by centerCrop scaleType.
     */
    private val roundedCornerOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, 6.dp.toFloat())
        }
    }

    @SuppressLint("SetTextI18n")
    fun setupImageView(message: TextChatMessage, shouldSaveToPhotos: Boolean = false, containerWidth: Int = 0) {
        currentShouldSaveToPhotos = shouldSaveToPhotos
        currentContainerWidth = containerWidth
        val previousAttachmentId = currentAttachmentId
        currentAttachmentId = message.id
        currentMessage = message

        // Round the image corners via a view-level outline clip (see [roundedCornerOutline]) — the
        // sole corner mechanism now that no Glide transform is applied. Idempotent per bind.
        binding.imageView.outlineProvider = roundedCornerOutline
        binding.imageView.clipToOutline = true

        // Reset status views every bind. Only clear the bitmap when the bubble is being rebound
        // to a DIFFERENT attachment (ViewHolder reuse during scroll) — same-attachment rebinds
        // (status change, progress update, list-context refresh) keep the existing bitmap to
        // avoid a visible blank-frame flash exposing the placeholder background.
        hideAllStatusViews()
        if (previousAttachmentId != null && previousAttachmentId != message.id) {
            Glide.with(context.getSafeContext()).clear(binding.imageView)
            binding.imageView.setImageDrawable(null)
        }

        val attachment = message.attachment ?: return

        val isVideo = attachment.isVideo()
        val fileName: String = attachment.fileName ?: ""
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(message.id) + fileName

        // Calculate and set image dimensions
        setupImageDimensions(attachment, attachmentPath, isVideo)

        val progress = message.getAttachmentProgress()
        val isFileValid = EncryptedAttachmentAccess.isReadable(attachmentPath)
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
        // Prefer currentContainerWidth (set by ChatMessageViewHolder for dual-pane), fall back
        // to the current Activity window bounds via WindowMetrics. Avoids Resources.getSystem(),
        // which always returns device-level dimensions and breaks on foldables (see PR #580).
        val containerWidth = if (currentContainerWidth > 0) {
            currentContainerWidth
        } else {
            windowWidthPx()
        }
        val containerHeight = windowHeightPx()

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
            // Encrypted-at-rest (no plaintext on disk): decode dimensions through the decrypting
            // content uri. decodeFile() on the missing plaintext path would return 0 and the bubble
            // would fall back to a default aspect ratio (e.g. a too-narrow image with a right gap,
            // common on forwarded images whose forward payload omits width/height).
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

        // Universal min display width for ALL thumbnail media so small items don't squeeze the
        // overlays (timestamp / send status / download progress). Fixed 150dp (not screen/3, which
        // grew too large on tablets/foldables) matches Signal/iOS; clamped to maxWidth.
        if (effectiveWidth > 0 && effectiveHeight > 0) {
            val minWidth = 150.dp
            if (finalWidth < minWidth) {
                val ratio = max(minAspectRatio, min(maxAspectRatio, effectiveWidth.toFloat() / effectiveHeight))
                finalWidth = minOf(minWidth, maxWidth)
                finalHeight = (finalWidth / ratio).toInt()
                if (finalHeight > maxHeight) {
                    // Very tall item: cap height but KEEP min width — the centerCrop ImageView crops
                    // the vertical overflow (opens full on tap) instead of shrinking to a thin strip,
                    // so both min-width and max-height hold and the overlays still fit. Mirrors Signal.
                    finalHeight = maxHeight
                }
            }
        }

        val layoutParams = binding.imageView.layoutParams
        layoutParams.width = finalWidth
        layoutParams.height = finalHeight
        binding.imageView.layoutParams = layoutParams
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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
        // Intentionally do NOT reset currentAttachmentId here. setupImageView relies on its
        // surviving the detach so that, on the next bind of a recycled ViewHolder to a
        // different message, previousAttachmentId differs from message.id and the stale
        // bitmap from the previous bubble is cleared (scroll-recycle stale-image protection).
        progressJob?.cancel()
        progressJob = null
        loadImageJob?.cancel()
        loadImageJob = null
        super.onDetachedFromWindow()
    }

    private fun loadImage(attachmentPath: String, expectedSize: Int, contentType: String) {
        loadImageJob?.cancel()
        loadImageJob = getLifecycleOwner()?.lifecycleScope?.launch {
            val plainFile = File(attachmentPath)
            val encryptedFile = EncryptedAttachmentAccess.encryptedFile(attachmentPath)

            // Cap the decode resolution at the (already screen-bounded) display size computed by
            // setupImageDimensions. A large user-picked gif/webp otherwise decodes at native
            // resolution (decode memory = w*h*4*frames), risking jank/OOM. This is the size Glide
            // already targets via into(view) for static images, but made EXPLICIT so the animated
            // gif/webp decoders honor it too. == the view size, so zero visual change.
            val targetW = binding.imageView.layoutParams?.width ?: 0
            val targetH = binding.imageView.layoutParams?.height ?: 0

            // Encrypted-at-rest image/video (no plaintext on disk): load via the decrypting provider.
            // For video, Glide decodes the first frame through the provider's seekable proxy fd; for
            // images it decodes the bitmap. While a fresh send is still uploading the plaintext coexists
            // with the ciphertext, so prefer the plaintext here (instant sender preview via Glide, which
            // tolerates the file being deleted mid-load and simply rebinds once only the ciphertext remains).
            val isEncryptedMedia = (contentType.contains("image") || contentType.contains("video")) &&
                !plainFile.exists() && encryptedFile.exists()

            // For encrypted images we may use Glide's RESOURCE disk cache, but the cached bytes are
            // themselves encrypted (see MyAppGlideModule) so plaintext is never persisted. Both static
            // (decoded bitmap) and animated gif/webp (untransformed source bytes) are cacheable. Restrict it to:
            //  - non-confidential messages: confidential content must never persist in Glide's cache;
            //  - Keystore key available: otherwise fall back to NONE (zero regression).
            val isAnimatedType = contentType.contains("gif") || contentType.contains("webp")
            val useEncryptedResourceCache = isEncryptedMedia &&
                currentMessage?.isConfidential() != true &&
                GlideCacheKeyManager.isCacheKeyReady(context)

            val (model: Any, fileLastModified: Long, actualFileSize: Long) = withContext(Dispatchers.IO) {
                if (isEncryptedMedia) {
                    val uri = EncryptedAttachmentAccess.contentUri(
                        currentMessage?.id ?: "",
                        plainFile.name
                    )
                    Triple(uri as Any, encryptedFile.lastModified(), encryptedFile.length())
                } else {
                    Triple(attachmentPath as Any, plainFile.lastModified(), plainFile.length())
                }
            }

            Glide.with(context.getSafeContext())
                .load(model)
                // Bound the decode resolution to the display size (see targetW/targetH above).
                .apply { if (targetW > 0 && targetH > 0) override(targetW, targetH) }
                .apply {
                    when {
                        // Encrypted RESOURCE cache: persists only encrypted resources (a decoded bitmap
                        // for static, untransformed source bytes for animated gif/webp).
                        useEncryptedResourceCache -> diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        // Encrypted source but cache disabled (confidential / no key): never cache.
                        isEncryptedMedia -> diskCacheStrategy(DiskCacheStrategy.NONE)
                        // Plaintext image: leave Glide's default (AUTOMATIC) behavior unchanged.
                    }
                }
                // Signature keys the in-memory cache; use the backing file's lastModified so an
                // updated attachment invalidates the cached bitmap.
                .signature(ObjectKey(fileLastModified))
                .apply {
                    // No Glide transform is applied on any path: crop is handled by the ImageView's
                    // centerCrop scaleType and rounded corners by the view-level outline clip
                    // (roundedCornerOutline), both independent of the cache — so a RESOURCE cache hit can
                    // never diverge from the first render (the encrypted animated cache stores
                    // untransformed source bytes). Animated content just opts into that encoder.
                    if (useEncryptedResourceCache && isAnimatedType) {
                        set(EncryptedByteBufferResourceEncoder.ENCRYPT_ANIMATED_CACHE, true)
                    }
                }
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