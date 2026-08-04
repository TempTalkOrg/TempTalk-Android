package com.difft.android.chat.mediasend

import android.content.Context
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.FileUtil.getFilePath
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.chat.mediasend.v2.videos.VideoTrimData
import com.difft.android.chat.mms.MediaConstraints
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.video.StreamingTranscoder
import com.difft.android.video.TranscoderOptions
import com.difft.android.video.VideoRemuxer
import com.difft.android.video.VideoSource
import com.difft.android.video.VideoUtil
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

    @WorkerThread
    override fun transform(context: Context, media: LocalMedia): LocalMedia {
        // Size and duration come from the MediaStore row rather than from opening the file: the
        // bare realPath is exactly what may be unreadable, and both values are already known.
        val fileSize = MediaMetadataSource.sizeBytes(media)
        val constraints = MediaConstraints.getPushMediaConstraints(SentMediaQuality.fromCode(sentMediaQuality.code))
        val maxFileSize = constraints.getCompressedVideoMaxSize(context)

        val needsTrimming = data.isDurationEdited

        // Case 1: User trimmed video -> must transcode
        if (needsTrimming) {
            L.i { "VideoTrimTransform: Using full transcode (trimming required)" }
            return performFullTranscode(context, media, constraints)
        }

        // Check if compression is needed (bitrate/size check). An unknown bitrate (-1) makes
        // needsCompression take its conservative branch, which is the behaviour we want.
        val preset = constraints.videoTranscodingSettings
        val inputBitRate = VideoUtil.inputBitRate(fileSize, MediaMetadataSource.durationMs(media))
        val targetBitRate = preset.videoBitRate + preset.audioBitRate
        val compressionNeeded = needsCompression(inputBitRate, targetBitRate, fileSize, maxFileSize)

        L.i { "VideoTrimTransform: sizeBytes=$fileSize, inputBitRate=$inputBitRate, targetBitRate=$targetBitRate, compressionNeeded=$compressionNeeded" }

        return if (compressionNeeded) {
            // Case 2: Compression needed -> full transcode
            L.i { "VideoTrimTransform: Compression needed, using full transcode" }
            performFullTranscode(context, media, constraints)
        } else {
            // Case 3: No trimming, no compression needed -> fast remux (metadata removal only)
            L.i { "VideoTrimTransform: No compression needed, using fast remux (metadata removal only)" }
            performFastRemux(context, media)
        }
    }

    /**
     * Perform full video transcode (for trimming or compression).
     * This is slower but necessary when video content needs to be modified.
     */
    private fun performFullTranscode(context: Context, media: LocalMedia, constraints: MediaConstraints): LocalMedia {
        var outputStream: FileOutputStream? = null

        try {
            val transformProperties = TransformProperties(false, data.isDurationEdited, data.startTimeUs, data.endTimeUs, sentMediaQuality.code, false)
            val options = if (transformProperties.videoTrim) {
                TranscoderOptions(transformProperties.videoTrimStartTimeUs, transformProperties.videoTrimEndTimeUs)
            } else {
                null
            }

            // VideoSource is the single dispatch owner: a sandbox source still reaches the native
            // file path inside it, a gallery source is read through its provider's descriptor.
            val transcoder = StreamingTranscoder(VideoSource.of(context, media.readableUri()), options, constraints.videoTranscodingSettings, constraints.getCompressedVideoMaxSize(context), true)

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
    private fun performFastRemux(context: Context, media: LocalMedia): LocalMedia {
        val outputFile = File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY), "${UUID.randomUUID()}.mp4")
        val outputPath = outputFile.absolutePath

        // A source that cannot be opened at all throws out of here and is reported per item; a
        // readable source that fails to remux still returns false and is sent as-is below.
        val success = VideoRemuxer.remux(VideoSource.of(context, media.readableUri()), outputPath)

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
