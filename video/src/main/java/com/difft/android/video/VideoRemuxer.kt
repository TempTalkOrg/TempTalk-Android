package com.difft.android.video

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.difft.android.base.log.lumberjack.L
import com.difft.android.video.exceptions.VideoSourceException
import java.io.File
import java.nio.ByteBuffer









object VideoRemuxer {

    private const val TAG = "VideoRemuxer"
    private const val BUFFER_SIZE = 1024 * 1024 

    














    @Throws(VideoSourceException::class)
    @SuppressLint("WrongConstant") 
    fun remux(source: VideoSource, outputPath: String): Boolean {
        val startTime = System.currentTimeMillis()
        L.i { "$TAG: Starting remux scheme=${source.scheme}" }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            
            
            extractor = try {
                source.mediaInput.createExtractor()
            } catch (e: Exception) {
                throw VideoSourceException("Unable to read video source for remux", e)
            }

            val rotation = VideoUtil.getVideoRotation(source)
            L.d { "$TAG: Input video rotation: $rotation" }

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
                L.d { "$TAG: Set output rotation hint: $rotation" }
            }

            val trackCount = extractor.trackCount
            val trackIndexMap = mutableMapOf<Int, Int>()

            
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val newTrackIndex = muxer.addTrack(format)
                    trackIndexMap[i] = newTrackIndex
                    extractor.selectTrack(i)
                    L.d { "$TAG: Added track $i ($mime) -> $newTrackIndex" }
                }
            }

            if (trackIndexMap.isEmpty()) {
                L.w { "$TAG: No video/audio tracks found" }
                return false
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break
                }

                val trackIndex = extractor.sampleTrackIndex
                val outputTrackIndex = trackIndexMap[trackIndex]

                if (outputTrackIndex != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                }

                extractor.advance()
            }

            val duration = System.currentTimeMillis() - startTime
            L.i { "$TAG: Remux completed in ${duration}ms" }
            return true

        } catch (e: VideoSourceException) {
            deletePartialOutput(outputPath)
            throw e
        } catch (e: Exception) {
            L.e(e) { "$TAG: Remux failed: ${e.message}" }
            deletePartialOutput(outputPath)
            return false
        } finally {
            
            runCatching { muxer?.stop() }
                .onFailure { L.w { "$TAG: Error stopping muxer: ${it.message}" } }
            runCatching { muxer?.release() }
                .onFailure { L.w { "$TAG: Error releasing muxer: ${it.message}" } }
            runCatching { extractor?.release() }
                .onFailure { L.w { "$TAG: Error releasing extractor: ${it.message}" } }
        }
    }

    private fun deletePartialOutput(outputPath: String) {
        runCatching { File(outputPath).delete() }
            .onFailure { L.w { "$TAG: cleanup failed: ${it.javaClass.simpleName}" } }
    }
}