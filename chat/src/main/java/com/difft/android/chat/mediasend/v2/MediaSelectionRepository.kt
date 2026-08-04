package com.difft.android.chat.mediasend.v2

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.entity.LocalMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.difft.android.chat.mediasend.ImageEditorModelRenderMediaTransform
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
import java.io.File

/**
 * Outcome of [MediaSelectionRepository.transformMediaSync]: each item that can be sent together with
 * the URI to send it from, plus the items that cannot.
 *
 * `updated.size + failures.size` always equals the input size, so no third count is needed.
 */
data class TransformResult(
    val updated: LinkedHashMap<LocalMedia, SendableMedia>,
    val failures: List<MediaFailure>
)

/**
 * Result of a send attempt. Not Parcelable on purpose: [MediaFailure] carries a `Throwable`, and the
 * only consumer of [failures] is the review screen, which is still alive when `send()` returns.
 */
data class MediaSendOutcome(
    val result: MediaSendActivityResult,
    val failures: List<MediaFailure>
) {
    val total: Int get() = result.media.size + failures.size
}

class MediaSelectionRepository(context: Context) {

    private val context: Context = context.applicationContext

    /**
     * Tries to send the selected media, performing proper transformations for edited images and videos.
     */
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

            L.i {
                "[MediaSend] send resolved=${sendable.size} failed=${transform.failures.size} " +
                    "content=${sendable.count { it.sendUri.scheme == ContentResolver.SCHEME_CONTENT }} " +
                    "file=${sendable.count { it.sendUri.scheme == ContentResolver.SCHEME_FILE }}"
            }

            MediaSendOutcome(
                result = MediaSendActivityResult(
                    media = sendable,
                    // Never dropped because some item failed: the caption belongs to the message,
                    // not to any one attachment.
                    body = trimmedBody,
                    confidentialMode = confidentialMode
                ),
                failures = transform.failures
            )
        }
    }

    /**
     * Removes the draft blobs backing [media], if any. Canonicalizing paths is real IO, so callers
     * must be off the main thread.
     *
     * The result of every attempt is counted and summarised: dropping it is what made a delete that
     * could never succeed indistinguishable from one that did.
     */
    @WorkerThread
    fun deleteBlobs(media: List<LocalMedia>) {
        val provider = MyBlobProvider.getInstance()
        val deleted = media.count { item ->
            // realPath, never readableUri(): a blob store's delete domain is file paths only. A
            // gallery item's content URI would only ever be refused by the ownership gate, so
            // normalizing here would swap a meaningful path for a guaranteed rejection.
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
                modelsToRender[it] = ImageEditorModelRenderMediaTransform(state.readModel(), null, quality)
            }

            if (state is VideoTrimData) {
                modelsToRender[it] = VideoTrimTransform(state, quality)
            }

//            if (quality == SentMediaQuality.HIGH) {
//                val existingTransform: MediaTransform? = modelsToRender[it]
//
//                modelsToRender[it] = if (existingTransform == null) {
//                    SentMediaQualityTransform(quality)
//                } else {
//                    CompositeMediaTransform(existingTransform, SentMediaQualityTransform(quality))
//                }
//            }
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
            val position = index + 1                                   // 1-based, as the user sees it
            val displayName = media.fileName.takeIf { it.isNotEmpty() }
            // Snapshot before transform(): a MediaTransform rewrites realPath on the same instance,
            // so after the call there is no way left to tell whether new bytes were produced.
            val sourceRealPath = media.realPath
            try {
                val transformer = modelsToTransform[media]
                val transformed = transformer?.transform(context, media) ?: media
                val producedNewBytes = transformed.realPath != sourceRealPath
                val sendUri = resolveSendUri(media, transformed, producedNewBytes)
                if (!producedNewBytes && !MediaFailureClassifier.isReadable(context, sendUri)) {
                    // The default path (full quality, unedited) reads nothing before the attachment
                    // copy. Establishing readability here is what makes the failure land on the
                    // review screen — with a position, an N-of-M count and the typed caption still
                    // intact — rather than after that screen is gone.
                    failures += MediaFailureClassifier.classifyAndLog(
                        context, sendUri, media.mimeType, position, displayName, cause = null
                    )
                    return@forEachIndexed
                }
                updated[media] = SendableMedia(transformed, sendUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Per item, so one unreadable or unsupported item can no longer discard the whole
                // batch. Not Throwable: Error semantics stay exactly as they are today.
                failures += MediaFailureClassifier.classifyAndLog(
                    context, media.readableUri(), media.mimeType, position, displayName, cause = e
                )
            }
        }
        return TransformResult(updated, failures)
    }

    /**
     * The single place that knows whether a transform actually wrote new bytes, and therefore the
     * only place that can decide where the send must read from.
     *
     * [producedNewBytes] is "was realPath rewritten", not "was there a transformer": three branches
     * run a transform and deliberately keep the original media (transcode not required, fast remux
     * failed, nothing to render). Those must fall back to the normalized source URI, because a
     * `file://` URI over the original bare path is exactly what is unreadable under scoped storage.
     */
    private fun resolveSendUri(source: LocalMedia, transformed: LocalMedia, producedNewBytes: Boolean): Uri =
        if (producedNewBytes) {
            // These bytes were just written into the app sandbox, so a file URI is always readable.
            // readableUri() cannot be used here: a transform never rewrites `path`, so it would
            // hand back the pre-edit source and silently drop the edit.
            Uri.fromFile(File(transformed.realPath))
        } else {
            source.readableUri()
        }
}