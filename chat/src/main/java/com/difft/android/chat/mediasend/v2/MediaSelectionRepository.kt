package com.difft.android.chat.mediasend.v2

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.selector.entity.LocalMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.mediasend.ImageEditorModelRenderMediaTransform
import com.difft.android.chat.mediasend.ImageSendFileOps
import com.difft.android.chat.mediasend.MediaFailure
import com.difft.android.chat.mediasend.MediaFailureClassifier
import com.difft.android.chat.mediasend.MediaKey
import com.difft.android.chat.mediasend.MediaSendActivityResult
import com.difft.android.chat.mediasend.MediaTransform
import com.difft.android.chat.mediasend.SendableMedia
import com.difft.android.chat.mediasend.VideoTrimTransform
import com.difft.android.chat.mediasend.mediaKey
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.mediasend.v2.videos.VideoTrimData
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.providers.MyBlobProvider
import com.difft.android.chat.scribbles.ImageEditorFragment
import com.difft.android.chat.util.MediaUtil
import java.io.File







data class TransformResult(
    val updated: LinkedHashMap<LocalMedia, SendableMedia>,
    val failures: List<MediaFailure>
)





data class MediaSendOutcome(
    val result: MediaSendActivityResult,
    val failures: List<MediaFailure>,
    val retirableSources: List<File> = emptyList(),
) {
    val total: Int get() = result.media.size + failures.size

    
    internal fun retireDraftSources(): Job {
        val sources = if (failures.isEmpty()) retirableSources else emptyList()
        val outputs = result.media.filter { it.sendUri.scheme == ContentResolver.SCHEME_FILE }
            .map { File(requireNotNull(it.sendUri.path)) }
        val selectedCount = total
        return appScope.launch {
            val deleted = sources.count { ImageSendFileOps.deleteDraftInput(Uri.fromFile(it), outputs) }
            L.i { "[MediaSend] draft sources deleted=$deleted kept=${selectedCount - deleted}" }
        }
    }
}

class MediaSelectionRepository(context: Context) {

    private val context: Context = context.applicationContext

    


    suspend fun send(
        selectedMedia: List<LocalMedia>,
        stateMap: Map<MediaKey, Any>,
        quality: SentMediaQuality,
        message: CharSequence?,
        confidentialMode: Int = 0,
    ): MediaSendOutcome {
        if (selectedMedia.isEmpty()) {
            throw IllegalStateException("No selected media!")
        }

        return withContext(Dispatchers.IO) {
            val trimmedBody: String = message?.toString()?.trim() ?: ""
            val modelsToTransform: Map<LocalMedia, MediaTransform> = buildModelsToTransform(selectedMedia, stateMap, quality)
            val transform: TransformResult = transformMediaSync(context, selectedMedia, modelsToTransform)
            val sendable: List<SendableMedia> = transform.updated.values.toList()
            
            val retirableSources = if (transform.failures.isEmpty()) {
                val outputs = sendable.filter { it.sendUri.scheme == ContentResolver.SCHEME_FILE }
                    .map { File(requireNotNull(it.sendUri.path)) }
                transform.updated.mapNotNull { (source, sent) ->
                    if (source.realPath != sent.media.realPath) {
                        ImageSendFileOps.retirableDraftInput(source.readableUri(), outputs)
                    } else null
                }.distinct()
            } else {
                emptyList()
            }

            L.i {
                "[MediaSend] send resolved=${sendable.size} failed=${transform.failures.size} " +
                    "content=${sendable.count { it.sendUri.scheme == ContentResolver.SCHEME_CONTENT }} " +
                    "file=${sendable.count { it.sendUri.scheme == ContentResolver.SCHEME_FILE }} quality=${quality.name}"
            }

            MediaSendOutcome(
                result = MediaSendActivityResult(
                    media = sendable,
                    
                    
                    body = trimmedBody,
                    confidentialMode = confidentialMode
                ),
                failures = transform.failures,
                retirableSources = retirableSources,
            )
        }
    }

    




    @WorkerThread
    fun deleteBlobs(media: List<LocalMedia>) {
        val provider = MyBlobProvider.getInstance()
        val deleted = media.count { item ->
            
            
            
            item.realPath.isNotBlank() && provider.delete(Uri.fromFile(File(item.realPath)))
        }
        L.i { "[MediaSend] deleteBlobs total=${media.size} deleted=$deleted" }
    }

    @WorkerThread
    fun cleanUp(selectedMedia: List<LocalMedia>) = deleteBlobs(selectedMedia)


    @WorkerThread
    internal fun buildModelsToTransform(
        selectedMedia: List<LocalMedia>,
        stateMap: Map<MediaKey, Any>,
        quality: SentMediaQuality
    ): Map<LocalMedia, MediaTransform> {
        val modelsToRender: MutableMap<LocalMedia, MediaTransform> = mutableMapOf()

        selectedMedia.forEach {
            val state = stateMap[it.mediaKey()]
            if (state is ImageEditorFragment.Data) {
                modelsToRender[it] = ImageEditorModelRenderMediaTransform(
                    state.readModel(), null, quality, preserveSourceForRetry = true
                )
            }

            if (state is VideoTrimData) {
                modelsToRender[it] = VideoTrimTransform(state, quality)
            }

        }

        return modelsToRender
    }

    @WorkerThread
    fun transformMediaSync(
        context: Context,
        currentMedia: List<LocalMedia>,
        modelsToTransform: Map<LocalMedia, MediaTransform>
    ): TransformResult {
        val updated = LinkedHashMap<LocalMedia, SendableMedia>(currentMedia.size)
        val failures = mutableListOf<MediaFailure>()

        currentMedia.forEachIndexed { index, media ->
            val position = index + 1                                   
            val displayName = media.fileName.takeIf { it.isNotEmpty() }
            
            val sourceRealPath = media.realPath
            try {
                val transformer = modelsToTransform[media]
                
                val transformed = transformer?.transform(context, media.copyForTransform()) ?: media
                val producedNewBytes = transformed.realPath != sourceRealPath
                val sendUri = resolveSendUri(media, transformed, producedNewBytes)
                val readable = producedNewBytes || MediaFailureClassifier.isReadable(context, sendUri)
                if (transformer == null && MediaUtil.isImageType(media.mimeType)) {
                    ImageEditorModelRenderMediaTransform.logPassthrough(media, readable)
                }
                if (!readable) {
                    
                    
                    
                    
                    failures += MediaFailureClassifier.classifyAndLog(
                        context, sendUri, media.mimeType, position, displayName, cause = null
                    )
                    return@forEachIndexed
                }
                updated[media] = SendableMedia(transformed, sendUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                
                failures += MediaFailureClassifier.classifyAndLog(
                    context, media.readableUri(), media.mimeType, position, displayName, cause = e
                )
            }
        }
        return TransformResult(updated, failures)
    }

    








    private fun resolveSendUri(source: LocalMedia, transformed: LocalMedia, producedNewBytes: Boolean): Uri =
        if (producedNewBytes) {
            
            
            
            Uri.fromFile(File(transformed.realPath))
        } else {
            source.readableUri()
        }
}
