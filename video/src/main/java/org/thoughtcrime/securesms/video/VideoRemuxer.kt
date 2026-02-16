package org.thoughtcrime.securesms.video

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.RequiresApi
import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.nio.ByteBuffer

/**
 * A utility class that remuxes video files without re-encoding.
 * This is much faster than full transcoding and is useful for:
 * - Removing location metadata (GPS) for privacy
 * - Moving moov atom to the front (faststart)
 *
 * Since it doesn't re-encode, the video/audio quality remains unchanged.
 */
@RequiresApi(Build.VERSION_CODES.O)
object VideoRemuxer {

    private const val TAG = "VideoRemuxer"
    private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    /**
     * Remux the video to a new file, removing all metadata (including location) but preserving rotation.
     * This is a fast operation as it doesn't re-encode the video/audio streams.
     *
     * @param inputPath The input video file path
     * @param outputPath The output video file path
     * @return true if successful, false otherwise
     */
    @SuppressLint("WrongConstant") // MediaExtractor.sampleFlags values are compatible with MediaCodec.BufferInfo.flags
    fun remux(inputPath: String, outputPath: String): Boolean {
        val startTime = System.currentTimeMillis()
        L.i { "$TAG: Starting remux from $inputPath to $outputPath" }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            // Get video rotation from input file using VideoUtil
            val rotation = VideoUtil.getVideoRotation(inputPath)
            L.d { "$TAG: Input video rotation: $rotation" }

            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Set the rotation on the output muxer (must be called before start())
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
                L.d { "$TAG: Set output rotation hint: $rotation" }
            }

            val trackCount = extractor.trackCount
            val trackIndexMap = mutableMapOf<Int, Int>()

            // Add all tracks to the muxer
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                // Only process video and audio tracks
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

            // Copy all samples
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

        } catch (e: Exception) {
            L.e(e) { "$TAG: Remux failed: ${e.message}" }
            // Clean up partial output file
            try {
                File(outputPath).delete()
            } catch (ignored: Exception) {
                L.w { "[VideoRemuxer] cleanup failed: ${ignored.stackTraceToString()}" }
            }
            return false
        } finally {
            // Use separate runCatching blocks to ensure each cleanup executes independently
            runCatching { muxer?.stop() }
                .onFailure { L.w { "$TAG: Error stopping muxer: ${it.message}" } }
            runCatching { muxer?.release() }
                .onFailure { L.w { "$TAG: Error releasing muxer: ${it.message}" } }
            runCatching { extractor?.release() }
                .onFailure { L.w { "$TAG: Error releasing extractor: ${it.message}" } }
        }
    }
}