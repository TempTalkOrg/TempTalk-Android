package org.thoughtcrime.securesms.mediasend

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.FileUtil.getFilePath
import com.luck.picture.lib.entity.LocalMedia
import org.thoughtcrime.securesms.mediasend.v2.videos.VideoTrimData
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.video.StreamingTranscoder
import org.thoughtcrime.securesms.video.TranscoderOptions
import org.thoughtcrime.securesms.video.VideoRemuxer
import org.thoughtcrime.securesms.video.VideoUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class VideoTrimTransform(private val data: VideoTrimData, private val sentMediaQuality: SentMediaQuality) : MediaTransform {

    companion object {
        /**
         * Check if video needs compression based on bitrate and file size.
         * This is consistent with StreamingTranscoder.isTranscodeRequired logic.
         *
         * @param inputBitRate The input video bitrate
         * @param targetBitRate The target bitrate threshold
         * @param fileSize The original file size in bytes
         * @param maxFileSize The maximum allowed file size
         * @return true if compression is needed, false otherwise
         */
        @JvmStatic
        fun needsCompression(
            inputBitRate: Int,
            targetBitRate: Int,
            fileSize: Long,
            maxFileSize: Long
        ): Boolean {
            // If bitrate info not available, assume compression is needed
            if (inputBitRate <= 0 || targetBitRate <= 0) return true
            // Consistent with StreamingTranscoder: compress if bitrate >= target * 1.2 or file too large
            return inputBitRate >= (targetBitRate * 1.2).toInt() || fileSize > maxFileSize
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @WorkerThread
    override fun transform(context: Context, media: LocalMedia): LocalMedia {
        val inputPath = media.realPath
        val inputFile = File(inputPath)
        val fileSize = inputFile.length()
        val constraints = MediaConstraints.getPushMediaConstraints(SentMediaQuality.fromCode(sentMediaQuality.code))
        val maxFileSize = constraints.getCompressedVideoMaxSize(context)

        val needsTrimming = data.isDurationEdited

        // Case 1: User trimmed video -> must transcode
        if (needsTrimming) {
            L.i { "VideoTrimTransform: Using full transcode (trimming required)" }
            return performFullTranscode(context, media, constraints)
        }

        // Check if compression is needed (bitrate/size check)
        val (inputBitRate, targetBitRate) = getBitrateInfo(inputPath, constraints)
        val compressionNeeded = needsCompression(inputBitRate, targetBitRate, fileSize, maxFileSize)

        L.i { "VideoTrimTransform: fileSize=${fileSize / 1024 / 1024}MB, inputBitRate=$inputBitRate, targetBitRate=$targetBitRate, compressionNeeded=$compressionNeeded" }

        return if (compressionNeeded) {
            // Case 2: Compression needed -> full transcode
            L.i { "VideoTrimTransform: Compression needed, using full transcode" }
            performFullTranscode(context, media, constraints)
        } else {
            // Case 3: No trimming, no compression needed -> fast remux (metadata removal only)
            L.i { "VideoTrimTransform: No compression needed, using fast remux (metadata removal only)" }
            performFastRemux(media)
        }
    }

    /**
     * Get input bitrate and target bitrate for compression decision.
     */
    private fun getBitrateInfo(inputPath: String, constraints: MediaConstraints): Pair<Int, Int> {
        val preset = constraints.videoTranscodingSettings
        return VideoUtil.getBitrateInfo(inputPath, preset.videoBitRate, preset.audioBitRate)
    }

    /**
     * Perform full video transcode (for trimming or compression).
     * This is slower but necessary when video content needs to be modified.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun performFullTranscode(context: Context, media: LocalMedia, constraints: MediaConstraints): LocalMedia {
        var outputStream: FileOutputStream? = null

        try {
            val transformProperties = TransformProperties(false, data.isDurationEdited, data.startTimeUs, data.endTimeUs, sentMediaQuality.code, false)
            val inputFile = File(media.realPath)
            val options = if (transformProperties.videoTrim) {
                TranscoderOptions(transformProperties.videoTrimStartTimeUs, transformProperties.videoTrimEndTimeUs)
            } else {
                null
            }

            // Use File constructor for native file handling (Signal's approach)
            // This avoids JNI overhead from MediaDataSource
            val transcoder = StreamingTranscoder(inputFile, options, constraints.videoTranscodingSettings, constraints.getCompressedVideoMaxSize(context), true)

            if (transcoder.isTranscodeRequired) {
                // Create output file directly without copying input first
                val outputFile = File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY), "${UUID.randomUUID()}.mp4")

                outputStream = FileOutputStream(outputFile)
                transcoder.transcode({ percent ->
                    L.d { "video transcode percent: $percent" }
                }, outputStream, { false })
                L.i { "video transcode success" }
                media.realPath = outputFile.absolutePath
            } else {
                L.i { "Transcode was not required" }
            }
        } catch (e: Exception) {
            L.w { "video transcode fail: ${e.stackTraceToString()}" }
            throw e
        } finally {
            try {
                outputStream?.close()
            } catch (e: IOException) {
                L.w { "Failed to close output stream: ${e.stackTraceToString()}" }
            }
        }
        return media
    }

    /**
     * Perform fast remux to remove metadata without re-encoding.
     * This is very fast (milliseconds) as it only copies the encoded streams.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun performFastRemux(media: LocalMedia): LocalMedia {
        val inputPath = media.realPath
        val outputFile = File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY), "${UUID.randomUUID()}.mp4")
        val outputPath = outputFile.absolutePath

        val success = VideoRemuxer.remux(inputPath, outputPath)

        if (success) {
            L.i { "Fast remux completed successfully" }
            media.realPath = outputPath
        } else {
            L.w { "Fast remux failed, keeping original file" }
            // If remux fails, we keep the original file
            // This is a trade-off: privacy vs reliability
            // The video will still be sent, just with metadata intact
        }

        return media
    }
}
