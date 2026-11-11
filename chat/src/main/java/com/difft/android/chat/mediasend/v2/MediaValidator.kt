package com.difft.android.chat.mediasend.v2

import android.content.Context
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.chat.mms.MediaConstraints
import com.difft.android.chat.util.MediaUtil

object MediaValidator {


    private fun isValidGif(context: Context, media: LocalMedia, mediaConstraints: MediaConstraints): Boolean {
        return MediaUtil.isGif(media.mimeType) && media.size < mediaConstraints.getGifMaxSize(context)
    }

    private fun isValidVideo(context: Context, media: LocalMedia, mediaConstraints: MediaConstraints): Boolean {
        return MediaUtil.isVideoType(media.mimeType) && media.size < mediaConstraints.getUncompressedVideoMaxSize(context)
    }

    private fun isSupportedMediaType(mimeType: String): Boolean {
        return MediaUtil.isGif(mimeType) || MediaUtil.isImageType(mimeType) || MediaUtil.isVideoType(mimeType)
    }

    data class FilterResult(val filteredMedia: List<LocalMedia>, val filterError: FilterError?, val bucketId: String?)

    sealed class FilterError {
        object ItemTooLarge : FilterError()
        object ItemInvalidType : FilterError()
        object TooManyItems : FilterError()
        class NoItems(val cause: FilterError? = null) : FilterError() {
            init {
                require(cause !is NoItems)
            }
        }

        object None : FilterError()
    }
}
