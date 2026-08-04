package com.difft.android.video.videoconverter

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES20
import com.difft.android.base.log.lumberjack.L
import com.difft.android.video.interfaces.MediaInput
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoThumbnailsExtractor {

    interface Callback {
        fun durationKnown(duration: Long)

        fun publishProgress(index: Int, thumbnail: Bitmap): Boolean

        fun failed()
    }

    companion object {

        private const val TAG = "VideoThumbnailsExtractor"

        @JvmStatic
        fun extractThumbnails(
            input: MediaInput,
            thumbnailCount: Int,
            thumbnailResolution: Int,
            callback: Callback
        ) {
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            var outputSurface: OutputSurface? = null
            try {
                extractor = input.createExtractor()
                var mediaFormat: MediaFormat? = null
                for (index in 0 until extractor.trackCount) {
                    val mimeType = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    if (mimeType != null && mimeType.startsWith("video/")) {
                        extractor.selectTrack(index)
                        mediaFormat = extractor.getTrackFormat(index)
                        break
                    }
                }
                if (mediaFormat != null) {
                    val mime = mediaFormat.getString(MediaFormat.KEY_MIME)
                        ?: throw IllegalArgumentException("Mime type for MediaFormat was null: \t$mediaFormat")

                    val rotation = if (mediaFormat.containsKey(MediaFormat.KEY_ROTATION)) mediaFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
                    val width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH)
                    val height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    val outputWidth: Int
                    val outputHeight: Int

                    if (width < height) {
                        outputWidth = thumbnailResolution
                        outputHeight = height * outputWidth / width
                    } else {
                        outputHeight = thumbnailResolution
                        outputWidth = width * outputHeight / height
                    }

                    val outputWidthRotated: Int
                    val outputHeightRotated: Int

                    if (rotation % 180 == 90) {
                        outputWidthRotated = outputHeight
                        outputHeightRotated = outputWidth
                    } else {
                        outputWidthRotated = outputWidth
                        outputHeightRotated = outputHeight
                    }

                    L.i { TAG + "video :" + width + "x" + height + " " + rotation }
                    L.i { TAG + "output: " + outputWidthRotated + "x" + outputHeightRotated }

                    outputSurface = OutputSurface(outputWidthRotated, outputHeightRotated, true)

                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(mediaFormat, outputSurface.getSurface(), null, 0)
                    decoder.start()

                    var duration: Long = 0

                    if (mediaFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = mediaFormat.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        L.w { TAG + "Video is missing duration!" }
                    }

                    callback.durationKnown(duration)

                    doExtract(extractor, decoder, outputSurface, outputWidthRotated, outputHeightRotated, duration, thumbnailCount, callback)
                }
            } catch (t: Throwable) {
                L.w(t) { TAG }
                callback.failed()
            } finally {
                if (outputSurface != null) {
                    outputSurface.release()
                }
                if (decoder != null) {
                    try {
                        decoder.stop()
                    } catch (codecException: MediaCodec.CodecException) {
                        L.w(codecException) { TAG + " Decoder stop failed: " + codecException.diagnosticInfo }
                    } catch (ise: IllegalStateException) {
                        L.w(ise) { TAG + " Decoder stop failed" }
                    }
                    decoder.release()
                }
                if (extractor != null) {
                    extractor.release()
                }
            }
        }

        @Throws(TranscodingException::class)
        private fun doExtract(
            extractor: MediaExtractor,
            decoder: MediaCodec,
            outputSurface: OutputSurface,
            outputWidth: Int,
            outputHeight: Int,
            duration: Long,
            thumbnailCount: Int,
            callback: Callback
        ) {
            val TIMEOUT_USEC = 10000
            val decoderInputBuffers = decoder.inputBuffers
            val info = MediaCodec.BufferInfo()

            var samplesExtracted = 0
            var thumbnailsCreated = 0

            L.i { TAG + "doExtract started" }
            val pixelBuf = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4)
            pixelBuf.order(ByteOrder.LITTLE_ENDIAN)

            var outputDone = false
            var inputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoderInputBuffers[inputBufIndex]
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0 || samplesExtracted >= thumbnailCount) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            L.i { TAG + "input done" }
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, 0 /*flags*/)
                            samplesExtracted++
                            val currentSamplesExtracted = samplesExtracted
                            extractor.seekTo(duration * currentSamplesExtracted / thumbnailCount, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            L.i { TAG + "seek to " + duration * currentSamplesExtracted / thumbnailCount + ", actual " + extractor.sampleTime }
                        }
                    }
                }

                val outputBufIndex: Int
                try {
                    outputBufIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                } catch (e: IllegalStateException) {
                    L.w(e) { TAG + " Decoder not in the Executing state, or codec is configured in asynchronous mode." }
                    throw TranscodingException("Decoder not in the Executing state, or codec is configured in asynchronous mode.", e)
                }

                if (outputBufIndex >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }

                    val shouldRender = info.size != 0 /*&& (info.presentationTimeUs >= duration * decodeCount / thumbnailCount)*/

                    decoder.releaseOutputBuffer(outputBufIndex, shouldRender)
                    if (shouldRender) {
                        outputSurface.awaitNewImage()
                        outputSurface.drawImage()

                        if (thumbnailsCreated < thumbnailCount) {
                            pixelBuf.rewind()
                            GLES20.glReadPixels(0, 0, outputWidth, outputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf)

                            val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                            pixelBuf.rewind()
                            bitmap.copyPixelsFromBuffer(pixelBuf)

                            val currentThumbnailsCreated = thumbnailsCreated
                            if (!callback.publishProgress(currentThumbnailsCreated, bitmap)) {
                                break
                            }
                            L.i { TAG + "publishProgress for frame " + currentThumbnailsCreated + " at " + info.presentationTimeUs + " (target " + duration * currentThumbnailsCreated / thumbnailCount + ")" }
                        }
                        thumbnailsCreated++
                    }
                }
            }
            L.i { TAG + "doExtract finished" }
        }
    }
}
