package com.difft.android.chat.mediasend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.media.AnimatedImageDetector
import com.difft.android.base.media.ImageCompressor
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.fonts.FontTypefaceProvider
import com.difft.android.chat.mms.MediaConstraints
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.providers.MyBlobProvider
import com.difft.android.chat.util.MediaUtil
import com.difft.android.imageeditor.core.model.EditorModel
import com.difft.android.selector.entity.LocalMedia
import util.StreamUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

class ImageEditorModelRenderMediaTransform(
    private val modelToRender: EditorModel?,
    private val size: Point?,
    private val sentMediaQuality: SentMediaQuality,
    private val preserveSourceForRetry: Boolean = false,
) : MediaTransform {

    companion object {
        internal fun logPassthrough(media: LocalMedia, readable: Boolean) {
            Diagnostic(media.size).apply {
                if (readable) skipped(if (MediaUtil.isGif(media.mimeType)) "animated" else "passthrough") else reason = "source"
            }.log(media)
        }
    }

    @WorkerThread
    override fun transform(context: Context, media: LocalMedia): LocalMedia {
        val diagnostic = Diagnostic(media.size)
        try {
            
            val renderedInput = render(context, media)
            diagnostic.reason = "source"
            val input = renderedInput ?: media.readableUri()
            try {
                when {
                    sentMediaQuality == SentMediaQuality.STANDARD -> {
                        
                        ImageSendFileOps.isJpeg(context, input)
                        compressStandard(context, media, input, diagnostic, renderedInput != null)
                    }
                    renderedInput != null -> diagnostic.skipped("rendered", "gps_stripped")
                    ImageSendFileOps.isJpeg(context, input) -> {
                        val result = ImageSendFileOps.stripJpeg(context, input, File(media.fileName).extension)
                        result.file?.let {
                            media.realPath = it.absolutePath
                            media.size = it.length()
                        }
                        if (result.reason == null) diagnostic.skipped("high", "gps_stripped") else {
                            diagnostic.fallback(result.reason.name.lowercase(Locale.ROOT), "failed")
                        }
                    }
                    else -> diagnostic.skipped("high")
                }
            } catch (e: IOException) {
                if (renderedInput == null) throw e
                diagnostic.fallback("source_io")
            } catch (e: SecurityException) {
                if (renderedInput == null) throw e
                diagnostic.fallback("source_denied")
            }
            return media
        } finally {
            diagnostic.log(media)
        }
    }

    private fun compressStandard(
        context: Context, media: LocalMedia, input: Uri, diagnostic: Diagnostic, rendered: Boolean
    ) {
        if (AnimatedImageDetector.isAnimated(context, input, media.mimeType)) {
            diagnostic.skipped("animated")
            return
        }
        val constraints = MediaConstraints.getPushMediaConstraints(SentMediaQuality.STANDARD)
        when (val result = ImageCompressor.compress(
            context, input, media.mimeType, constraints.getImageDimensionTargets(context),
            constraints.getImageMaxSize(context), constraints.getImageCompressionQualitySetting(context),
            File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY))
        )) {
            is ImageCompressor.Result.Compressed -> {
                val baseName = media.fileName.substringBeforeLast('.').ifBlank { UUID.randomUUID().toString() }
                media.realPath = result.file.absolutePath
                media.size = result.size
                media.width = result.width
                media.height = result.height
                media.mimeType = result.mime
                media.fileName = "$baseName.${result.file.extension}"
                diagnostic.result = "compressed"
                diagnostic.reason = "standard"
                diagnostic.tier = result.tier
                diagnostic.meta = "gps_stripped"
                
                if (!preserveSourceForRetry || rendered) ImageSendFileOps.deleteDraftInput(input, result.file)
            }
            is ImageCompressor.Result.Failed -> diagnostic.fallback(result.reason.name.lowercase(Locale.ROOT))
        }
    }

    private fun render(context: Context, media: LocalMedia): Uri? {
        val model = modelToRender ?: return null
        var bitmap: Bitmap? = null
        var outputStream: ByteArrayOutputStream? = null
        try {
            outputStream = ByteArrayOutputStream()
            val rendered = model.render(context, size, FontTypefaceProvider())
            bitmap = rendered
            rendered.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            val uri = MyBlobProvider.getInstance()
                .forData(outputStream.toByteArray())
                .withMimeType(MediaUtil.IMAGE_JPEG)
                .withFileName("${UUID.randomUUID()}.jpg")
                .createForDraftAttachmentAsync(context).get()
            media.realPath = uri.path.orEmpty()
            media.mimeType = MediaUtil.IMAGE_JPEG
            media.width = rendered.width
            media.height = rendered.height
            media.size = outputStream.size().toLong()
            return Uri.fromFile(File(media.realPath))
        } finally {
            
            bitmap?.recycle()
            StreamUtil.close(outputStream)
        }
    }

    private class Diagnostic(private val sourceBytes: Long) {
        var result = "failed"
        var reason = "render"
        var tier = -1
        var meta = "kept"

        fun skipped(reason: String, meta: String = "kept") {
            result = "skipped"
            this.reason = reason
            this.meta = meta
        }

        fun fallback(reason: String, meta: String = "kept") {
            result = "failed"
            this.reason = reason
            this.meta = meta
            L.w { "[MediaSend] fallback reason=$reason meta=$meta" }
        }

        fun log(media: LocalMedia) {
            val format = when (media.mimeType.lowercase(Locale.ROOT)) {
                "image/jpeg", "image/jpg" -> "jpeg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic", "image/heif", "image/avif", "image/gif" -> media.mimeType.substringAfter('/').lowercase(Locale.ROOT)
                else -> "other"
            }
            val event = "[MediaSend] compress result=$result reason=$reason srcBytes=$sourceBytes outBytes=${media.size} " +
                "out=${media.width}x${media.height} tier=$tier fmt=$format meta=$meta"
            L.i { event }
        }
    }
}
