package com.difft.android.chat.attachment.videoprobe

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import com.difft.android.chat.media.EncryptedAttachmentAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class VideoSize(val width: Int, val height: Int)





fun interface VideoFrameProber {
    fun probe(basePath: String): VideoSize?
}

class MediaRetrieverVideoFrameProber @Inject constructor(
    @ApplicationContext private val context: Context,
) : VideoFrameProber {

    override fun probe(basePath: String): VideoSize? {
        val retriever = MediaMetadataRetriever()
        try {
            
            if (EncryptedAttachmentAccess.hasEncrypted(basePath)) {
                retriever.setDataSource(context, EncryptedAttachmentAccess.contentUriFromBasePath(basePath))
            } else {
                retriever.setDataSource(basePath)
            }
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_BOUND_PX, FRAME_BOUND_PX)
            } else {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return null
            val frameSize = VideoSize(frame.width, frame.height)
            frame.recycle()
            return metadataSize(retriever) ?: frameSize.takeIf { it.width > 0 && it.height > 0 }
        } finally {
            runCatching { retriever.release() }
        }
    }

    
    private fun metadataSize(retriever: MediaMetadataRetriever): VideoSize? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (width <= 0 || height <= 0) return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return if (rotation == 90 || rotation == 270) VideoSize(height, width) else VideoSize(width, height)
    }

    private companion object {
        const val FRAME_BOUND_PX = 512
    }
}
