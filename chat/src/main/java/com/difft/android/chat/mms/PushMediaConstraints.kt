package com.difft.android.chat.mms

import android.content.Context
import androidx.annotation.IntRange
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.chat.util.Util
import com.difft.android.video.TranscodingPreset

private const val KB = 1024
private const val MB = 1024 * KB

class PushMediaConstraints(sentMediaQuality: SentMediaQuality?) : MediaConstraints() {

    private val currentConfig: MediaConfig =
        getCurrentConfig(ApplicationHelper.instance, sentMediaQuality)

    override fun isHighQuality(): Boolean = currentConfig == MediaConfig.LEVEL_3

    override fun getImageMaxWidth(context: Context): Int = currentConfig.imageSizeTargets[0]

    override fun getImageMaxHeight(context: Context): Int = getImageMaxWidth(context)

    override fun getImageMaxSize(context: Context): Int =
        Math.min(currentConfig.maxImageFileSize.toLong(), getMaxAttachmentSize()).toInt()

    override fun getImageDimensionTargets(context: Context): IntArray = currentConfig.imageSizeTargets

    override fun getGifMaxSize(context: Context): Long =
        Math.min((25 * MB).toLong(), getMaxAttachmentSize())

    override fun getVideoMaxSize(context: Context): Long = getMaxAttachmentSize()

    override fun getUncompressedVideoMaxSize(context: Context): Long =
        if (MediaConstraints.isVideoTranscodeAvailable()) (500 * MB).toLong() else getVideoMaxSize(context)

    override fun getCompressedVideoMaxSize(context: Context): Long =
        if (Util.isLowMemory(context)) (30 * MB).toLong() else (50 * MB).toLong()

    override fun getAudioMaxSize(context: Context): Long = getMaxAttachmentSize()

    override fun getDocumentMaxSize(context: Context): Long = getMaxAttachmentSize()

    override fun getImageCompressionQualitySetting(context: Context): Int = currentConfig.qualitySetting

    override val videoTranscodingSettings: TranscodingPreset get() = currentConfig.videoPreset

    enum class MediaConfig(
        private val isLowMemory: Boolean,
        private val level: Int,
        val maxImageFileSize: Int,
        val imageSizeTargets: IntArray,
        @param:IntRange(from = 0, to = 100) val qualitySetting: Int,
        val videoPreset: TranscodingPreset
    ) {
        LEVEL_1_LOW_MEMORY(true, 1, MB, intArrayOf(768, 512), 70, TranscodingPreset.LEVEL_1),
        LEVEL_1(false, 1, MB, intArrayOf(1600, 1024, 768, 512), 70, TranscodingPreset.LEVEL_1),
        LEVEL_2(false, 2, (1.5 * MB).toInt(), intArrayOf(2048, 1600, 1024, 768, 512), 75, TranscodingPreset.LEVEL_2),
        LEVEL_3(false, 3, 3 * MB, intArrayOf(4096, 3072, 2048, 1600, 1024, 768, 512), 75, TranscodingPreset.LEVEL_3);

        companion object {
            @JvmStatic
            fun getDefault(context: Context): MediaConfig =
                if (Util.isLowMemory(context)) LEVEL_1_LOW_MEMORY else LEVEL_1
        }
    }

    companion object {
        private fun getCurrentConfig(context: Context, sentMediaQuality: SentMediaQuality?): MediaConfig {
            if (Util.isLowMemory(context)) {
                return MediaConfig.LEVEL_1_LOW_MEMORY
            }
            if (sentMediaQuality == SentMediaQuality.HIGH) {
                return MediaConfig.LEVEL_3
            }
            return MediaConfig.getDefault(context)
        }
    }
}
