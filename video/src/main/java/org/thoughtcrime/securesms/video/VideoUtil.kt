package org.thoughtcrime.securesms.video

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Utility class for video-related operations.
 */
object VideoUtil {

    /**
     * Get input bitrate and target bitrate for compression decision.
     *
     * @param inputPath The video file path
     * @param targetVideoBitRate Target video bitrate
     * @param targetAudioBitRate Target audio bitrate
     * @return Pair of (inputBitRate, targetBitRate), or (0, 0) if unable to determine
     */
    @JvmStatic
    fun getBitrateInfo(inputPath: String, targetVideoBitRate: Int, targetAudioBitRate: Int): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            if (durationMs <= 0) return Pair(0, 0)

            val fileSize = File(inputPath).length()
            val inputBitRate = (fileSize * 8 / (durationMs / 1000.0)).toInt()
            val targetBitRate = targetVideoBitRate + targetAudioBitRate

            Pair(inputBitRate, targetBitRate)
        } catch (e: Exception) {
            Pair(0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Get video rotation from metadata.
     *
     * @param inputPath The video file path
     * @return Rotation in degrees (0, 90, 180, 270), or 0 if unable to determine
     */
    @JvmStatic
    fun getVideoRotation(inputPath: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }
}