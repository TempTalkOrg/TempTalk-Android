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

/**
 * A utility class that remuxes video files without re-encoding.
 * This is much faster than full transcoding and is useful for:
 * - Removing location metadata (GPS) for privacy
 * - Moving moov atom to the front (faststart)
 *
 * Since it doesn't re-encode, the video/audio quality remains unchanged.
 */
object VideoRemuxer {

    private const val TAG = "VideoRemuxer"
    private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    /**
     * Remux the video to a new file, removing all metadata (including location) but preserving rotation.
     * This is a fast operation as it doesn't re-encode the video/audio streams.
     *
     * Two failure classes, deliberately not merged:
     *  - the source cannot be opened at all: throws [VideoSourceException], because anything
     *    downstream that needs those bytes will fail too and the item has to be reported;
     *  - the source opens but a track / muxer / sample step fails: returns false, which keeps the
     *    long-standing trade-off of sending the original with its metadata intact rather than
     *    turning a privacy downgrade into a blocked send.
     *
     * @param source The input video source
     * @param outputPath The output video file path
     * @return true if successful, false if the source was readable but could not be remuxed
     */
    @Throws(VideoSourceException::class)
    @SuppressLint("WrongConstant") // MediaExtractor.sampleFlags values are compatible with MediaCodec.BufferInfo.flags
    fun remux(source: VideoSource, outputPath: String): Boolean {
        val startTime = System.currentTimeMillis()
        L.i { "$TAG: Starting remux scheme=${source.scheme}" }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            // Bind before creating the muxer: an unreadable source must not leave a zero-byte
            // output file behind for a caller to mistake for a successful remux.
            extractor = try {
                source.mediaInput.createExtractor()
            } catch (e: Exception) {
                throw VideoSourceException("Unable to read video source for remux", e)
            }

            val rotation = VideoUtil.getVideoRotation(source)
            L.d { "$TAG: Input video rotation: $rotation" }

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

        } catch (e: VideoSourceException) {
            deletePartialOutput(outputPath)
            throw e
        } catch (e: Exception) {
            L.e(e) { "$TAG: Remux failed: ${e.message}" }
            deletePartialOutput(outputPath)
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

    private fun deletePartialOutput(outputPath: String) {
        runCatching { File(outputPath).delete() }
            .onFailure { L.w { "$TAG: cleanup failed: ${it.javaClass.simpleName}" } }
    }
}