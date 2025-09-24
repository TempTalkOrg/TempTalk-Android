package org.thoughtcrime.securesms.mediasend.v2

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.luck.picture.lib.entity.LocalMedia
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.mediasend.ImageEditorModelRenderMediaTransform
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.MediaTransform
import org.thoughtcrime.securesms.mediasend.VideoTrimTransform
import org.thoughtcrime.securesms.mediasend.v2.videos.VideoTrimData
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.providers.MyBlobProvider
import org.thoughtcrime.securesms.scribbles.ImageEditorFragment

class MediaSelectionRepository(context: Context) {

    private val context: Context = context.applicationContext

    /**
     * Tries to send the selected media, performing proper transformations for edited images and videos.
     */
    fun send(
        selectedMedia: List<LocalMedia>,
        stateMap: Map<Uri, Any>,
        quality: SentMediaQuality,
        message: CharSequence?,
    ): Maybe<MediaSendActivityResult> {
        if (selectedMedia.isEmpty()) {
            throw IllegalStateException("No selected media!")
        }

        return Maybe.create { emitter ->
            val trimmedBody: String = message?.toString()?.trim() ?: ""
            val modelsToTransform: Map<LocalMedia, MediaTransform> = buildModelsToTransform(selectedMedia, stateMap, quality)
            val oldToNewMediaMap: Map<LocalMedia, LocalMedia> = transformMediaSync(context, selectedMedia, modelsToTransform)
            val updatedMedia = oldToNewMediaMap.values.toList()

            emitter.onSuccess(
                MediaSendActivityResult(
                    media = updatedMedia,
                    body = trimmedBody
                )
            )
        }.subscribeOn(Schedulers.io()).cast(MediaSendActivityResult::class.java)
    }

    fun deleteBlobs(media: List<LocalMedia>) {
        media
            .map(LocalMedia::getRealPath)
            .forEach { MyBlobProvider.getInstance().delete(Uri.parse(it)) }
    }

    fun cleanUp(selectedMedia: List<LocalMedia>) {
        deleteBlobs(selectedMedia)
    }


    @WorkerThread
    private fun buildModelsToTransform(
        selectedMedia: List<LocalMedia>,
        stateMap: Map<Uri, Any>,
        quality: SentMediaQuality
    ): Map<LocalMedia, MediaTransform> {
        val modelsToRender: MutableMap<LocalMedia, MediaTransform> = mutableMapOf()

        selectedMedia.forEach {
            val state = stateMap[Uri.parse(it.realPath)]
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
    ): LinkedHashMap<LocalMedia, LocalMedia> {
        val updatedMedia = LinkedHashMap<LocalMedia, LocalMedia>(currentMedia.size)

        for (media in currentMedia) {
            val transformer = modelsToTransform[media]
            if (transformer != null) {
                updatedMedia[media] = transformer.transform(context, media)
            } else {
                updatedMedia[media] = media
            }
        }
        return updatedMedia
    }
}