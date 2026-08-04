package com.difft.android.chat.mediasend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.fonts.FontTypefaceProvider
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.providers.MyBlobProvider
import com.difft.android.chat.util.MediaUtil
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.imageeditor.core.model.EditorModel
import top.zibin.luban.Luban
import util.StreamUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

class ImageEditorModelRenderMediaTransform(
    private val modelToRender: EditorModel?,
    private val size: Point?,
    private val sentMediaQuality: SentMediaQuality
) : MediaTransform {

    @WorkerThread
    override fun transform(context: Context, media: LocalMedia): LocalMedia {
        // Non-null only once a render output exists. readableUri() is a *source*-only contract: it
        // reads `path`, which a render never rewrites, so after a render it would hand back the
        // untouched original and the compressor would silently discard the user's edits.
        var compressInput: Uri? = null
        var bitmap: Bitmap? = null
        var outputStream: ByteArrayOutputStream? = null
        try {
            if (modelToRender != null) { // render the edited image first
                outputStream = ByteArrayOutputStream()
                val rendered = modelToRender.render(context, size, FontTypefaceProvider())
                bitmap = rendered
                rendered.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)

                val uri = MyBlobProvider.getInstance()
                    .forData(outputStream.toByteArray())
                    .withMimeType(MediaUtil.IMAGE_JPEG)
                    .withFileName(UUID.randomUUID().toString() + ".jpg")
                    .createForDraftAttachmentAsync(context).get()

                media.realPath = uri.path.orEmpty()
                media.mimeType = MediaUtil.IMAGE_JPEG
                media.width = rendered.width
                media.height = rendered.height
                media.size = outputStream.size().toLong()
                // Just written into the app sandbox, so a file URI over it is always readable.
                compressInput = Uri.fromFile(File(media.realPath))
            }
        } finally {
            // Still runs on the propagating path — the render bitmap must not leak.
            bitmap?.recycle()
            StreamUtil.close(outputStream)
        }
        // No catch: reaching this branch means the user actually cropped, drew or added text, so
        // falling back to "use the untouched original" would silently send an image they never
        // composed. The failure propagates and is reported as one failed item instead.

        try {
            if (sentMediaQuality == SentMediaQuality.STANDARD) { // needs compression
                val file = Luban.with(context)
                    // Must be the Uri overload: passing a content URI as a String makes Luban treat
                    // it as a bare file path. Its Uri provider reads through ContentResolver, which
                    // works for both a provider URI and a sandbox file:// one.
                    .load(compressInput ?: media.readableUri())
                    .ignoreBy(100)
                    .setTargetDir(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY))
                    .setRenameListener { UUID.randomUUID().toString() + ".jpg" }
                    .get()

                if (file != null && file.isNotEmpty()) {
                    media.realPath = file[0].path
                }
            }
        } catch (e: IOException) {
            if (modelToRender == null) {
                // Luban was reading the SOURCE, so this IS a source read failure. Swallowing it only
                // defers the report to the attachment copy, after the review screen is gone.
                throw e
            }
            // A render output already exists and carries the user's edits; compression is a size
            // optimisation only, so keep the full-quality render rather than failing the send.
            L.w { "[MediaSend] compress skipped after render mime=${media.mimeType}: ${e.javaClass.simpleName}" }
        }

        return media
    }
}
