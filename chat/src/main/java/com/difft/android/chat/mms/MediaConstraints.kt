package com.difft.android.chat.mms

import android.content.Context
import androidx.annotation.IntRange
import com.difft.android.chat.util.ByteUnit
import com.difft.android.chat.util.MediaUtil
import com.difft.android.websocket.api.crypto.AttachmentCipherStreamUtil
import com.difft.android.websocket.internal.crypto.PaddingInputStream
import difft.android.messageserialization.model.Attachment
import com.difft.android.video.TranscodingPreset

abstract class MediaConstraints {

    abstract fun getImageMaxWidth(context: Context): Int

    abstract fun getImageMaxHeight(context: Context): Int

    abstract fun getImageMaxSize(context: Context): Int

    open val videoTranscodingSettings: TranscodingPreset get() = TranscodingPreset.LEVEL_1

    open fun isHighQuality(): Boolean = false

    /**
     * Provide a list of dimensions that should be attempted during compression. We will keep moving
     * down the list until the image can be scaled to fit under [getImageMaxSize]. The first entry in
     * the list should match your max width/height.
     */
    abstract fun getImageDimensionTargets(context: Context): IntArray

    abstract fun getGifMaxSize(context: Context): Long

    abstract fun getVideoMaxSize(context: Context): Long

    @IntRange(from = 0, to = 100)
    open fun getImageCompressionQualitySetting(context: Context): Int = 70

    open fun getUncompressedVideoMaxSize(context: Context): Long = getVideoMaxSize(context)

    open fun getCompressedVideoMaxSize(context: Context): Long = getVideoMaxSize(context)

    abstract fun getAudioMaxSize(context: Context): Long

    abstract fun getDocumentMaxSize(context: Context): Long

    fun getMaxAttachmentSize(): Long {
        val maxCipherTextSize = ByteUnit.MEGABYTES.toBytes(100)
        val maxPaddedSize = AttachmentCipherStreamUtil.getPlaintextLength(maxCipherTextSize)
        return PaddingInputStream.getMaxUnpaddedSize(maxPaddedSize)
    }

    fun canResize(attachment: Attachment): Boolean =
        MediaUtil.isImage(attachment) && !MediaUtil.isGif(attachment) ||
            MediaUtil.isVideo(attachment) && isVideoTranscodeAvailable()

    fun canResize(mediaType: String): Boolean =
        MediaUtil.isImageType(mediaType) && !MediaUtil.isGif(mediaType) ||
            MediaUtil.isVideoType(mediaType) && isVideoTranscodeAvailable()

    companion object {
        @JvmStatic
        fun getPushMediaConstraints(): MediaConstraints = getPushMediaConstraints(null)

        @JvmStatic
        fun getPushMediaConstraints(sentMediaQuality: SentMediaQuality?): MediaConstraints =
            PushMediaConstraints(sentMediaQuality)

        @JvmStatic
        fun isVideoTranscodeAvailable(): Boolean {
            // minSdk=26 and the feature-flag gating below is disabled, so transcode
            // is always available. (Was: SDK_INT >= 26 && (useStreamingVideoMuxer() ||
            // MemoryFileDescriptor.supported()).)
            return true
        }
    }
}
