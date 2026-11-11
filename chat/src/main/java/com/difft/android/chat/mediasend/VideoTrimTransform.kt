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
import com.difft.android.chat.mms.videoConstraintsQuality
import com.difft.android.video.StreamingTranscoder
import com.difft.android.video.TranscoderOptions
import com.difft.android.video.VideoRemuxer
import com.difft.android.video.VideoSource
import com.difft.android.video.VideoUtil
import util.FileSystemUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class VideoTrimTransform(private val data: VideoTrimData, private val sentMediaQuality: SentMediaQuality) : MediaTransform {

    companion object {
        private const val MP4_MIME_TYPE = "video/mp4"
        private const val FALLBACK_BASE_NAME = "video"

        









        





        internal fun relabelAsMp4(media: LocalMedia) {
            val originalName = media.fileName.takeIf { it.isNotBlank() } ?: FileSystemUtils.getFileName(media.path)
            val baseName = originalName.substringBeforeLast('.').ifBlank { FALLBACK_BASE_NAME }
            media.mimeType = MP4_MIME_TYPE
            media.fileName = "$baseName.mp4"
        }

        @JvmStatic
        fun needsCompression(
            inputBitRate: Int,
            targetBitRate: Int,
            fileSize: Long,
            maxFileSize: Long
        ): Boolean {
            
            if (inputBitRate <= 0 || targetBitRate <= 0) return true
            
            return inputBitRate >= (targetBitRate * 1.2).toInt() || fileSize > maxFileSize
        }
    }

    @WorkerThread
    override fun transform(context: Context, media: LocalMedia): LocalMedia {
        val sourcePath = media.realPath
        val result = transformToOutput(context, media)
        
        if (result.realPath != sourcePath) relabelAsMp4(result)
        return result
    }

    private fun transformToOutput(context: Context, media: LocalMedia): LocalMedia {
        
        
        val fileSize = MediaMetadataSource.sizeBytes(media)
        val constraints = MediaConstraints.getPushMediaConstraints(sentMediaQuality.videoConstraintsQuality())
        val maxFileSize = constraints.getCompressedVideoMaxSize(context)

        val needsTrimming = data.isDurationEdited

        
        if (needsTrimming) {
            L.i { "VideoTrimTransform: Using full transcode (trimming required)" }
            return performFullTranscode(context, media, constraints)
        }

        
        
        val preset = constraints.videoTranscodingSettings
        val inputBitRate = VideoUtil.inputBitRate(fileSize, MediaMetadataSource.durationMs(media))
        val targetBitRate = preset.videoBitRate + preset.audioBitRate
        val compressionNeeded = needsCompression(inputBitRate, targetBitRate, fileSize, maxFileSize)

        L.i { "VideoTrimTransform: sizeBytes=$fileSize, inputBitRate=$inputBitRate, targetBitRate=$targetBitRate, compressionNeeded=$compressionNeeded" }

        return if (compressionNeeded) {
            
            L.i { "VideoTrimTransform: Compression needed, using full transcode" }
            performFullTranscode(context, media, constraints)
        } else {
            
            L.i { "VideoTrimTransform: No compression needed, using fast remux (metadata removal only)" }
            performFastRemux(context, media)
        }
    }

    



    private fun performFullTranscode(context: Context, media: LocalMedia, constraints: MediaConstraints): LocalMedia {
        var outputStream: FileOutputStream? = null

        try {
            val transformProperties = TransformProperties(false, data.isDurationEdited, data.startTimeUs, data.endTimeUs, sentMediaQuality.code, false)
            val options = if (transformProperties.videoTrim) {
                TranscoderOptions(transformProperties.videoTrimStartTimeUs, transformProperties.videoTrimEndTimeUs)
            } else {
                null
            }

            
            
            val transcoder = StreamingTranscoder(VideoSource.of(context, media.readableUri()), options, constraints.videoTranscodingSettings, constraints.getCompressedVideoMaxSize(context), true)

            if (transcoder.isTranscodeRequired) {
                
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

    



    private fun performFastRemux(context: Context, media: LocalMedia): LocalMedia {
        val outputFile = File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY), "${UUID.randomUUID()}.mp4")
        val outputPath = outputFile.absolutePath

        
        
        val success = VideoRemuxer.remux(VideoSource.of(context, media.readableUri()), outputPath)

        if (success) {
            L.i { "Fast remux completed successfully" }
            media.realPath = outputPath
        } else {
            L.w { "Fast remux failed, keeping original file" }
            
            
            
        }

        return media
    }
}
