package com.difft.android.video.videoconverter

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.difft.android.base.log.lumberjack.L
import com.difft.android.video.interfaces.MediaInput
import com.difft.android.video.interfaces.Muxer
import com.difft.android.video.videoconverter.utils.Extensions
import com.difft.android.video.videoconverter.utils.MediaCodecCompat
import com.difft.android.video.videoconverter.utils.Preconditions
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Streaming video-track transcoder. Ported 1:1 from the Signal-derived Java implementation as
 * part of the Kotlin port campaign (#1093). The drain/feed MediaCodec state machine in [step]
 * and the encoder-setup constructor are preserved verbatim, so the class and those members
 * exceed detekt size thresholds by design.
 */
@Suppress("LargeClass")
internal class VideoTrackConverter {

    private val mTimeFrom: Long
    private val mTimeTo: Long

    val mInputDuration: Long

    private val mVideoExtractor: MediaExtractor
    private val mVideoDecoder: MediaCodec
    private val mVideoEncoder: MediaCodec

    private val mInputSurface: InputSurface
    private val mOutputSurface: OutputSurface

    private val mVideoDecoderInputBuffers: Array<ByteBuffer>
    private var mVideoEncoderOutputBuffers: Array<ByteBuffer>
    private val mVideoDecoderOutputBufferInfo: MediaCodec.BufferInfo
    private val mVideoEncoderOutputBufferInfo: MediaCodec.BufferInfo

    var mEncoderOutputVideoFormat: MediaFormat? = null

    var mVideoExtractorDone = false
    private var mVideoDecoderDone = false
    var mVideoEncoderDone = false

    private var mOutputVideoTrack = -1

    var mMuxingVideoPresentationTime: Long = 0

    private var mVideoExtractedFrameCount = 0
    private var mVideoDecodedFrameCount = 0
    private var mVideoEncodedFrameCount = 0

    private var mMuxer: Muxer? = null

    @Suppress("LongMethod")
    private constructor(
        videoExtractor: MediaExtractor,
        videoInputTrack: Int,
        timeFrom: Long,
        timeTo: Long,
        videoResolution: Int,
        videoBitrate: Int,
        videoCodec: String
    ) {
        mTimeFrom = timeFrom
        mTimeTo = timeTo
        mVideoExtractor = videoExtractor

        val videoCodecInfo = MediaConverter.selectCodec(videoCodec)
        if (videoCodecInfo == null) {
            // Don't fail CTS if they don't have an AVC codec (not here, anyway).
            L.e { TAG + "Unable to find an appropriate codec for " + videoCodec }
            throw FileNotFoundException()
        }
        L.i { TAG + "Video encoder selected: " + videoCodecInfo.name + ", isHardwareAccelerated: " + (if (Build.VERSION.SDK_INT >= 29) videoCodecInfo.isHardwareAccelerated else "unknown") }

        val inputVideoFormat = mVideoExtractor.getTrackFormat(videoInputTrack)

        mInputDuration = if (inputVideoFormat.containsKey(MediaFormat.KEY_DURATION)) inputVideoFormat.getLong(MediaFormat.KEY_DURATION) else 0

        val rotation = if (inputVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) inputVideoFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
        val width = if (inputVideoFormat.containsKey(MEDIA_FORMAT_KEY_DISPLAY_WIDTH)) {
            inputVideoFormat.getInteger(MEDIA_FORMAT_KEY_DISPLAY_WIDTH)
        } else {
            inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
        }
        val height = if (inputVideoFormat.containsKey(MEDIA_FORMAT_KEY_DISPLAY_HEIGHT)) {
            inputVideoFormat.getInteger(MEDIA_FORMAT_KEY_DISPLAY_HEIGHT)
        } else {
            inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        }
        var outputWidth = width
        var outputHeight = height
        if (outputWidth < outputHeight) {
            outputWidth = videoResolution
            outputHeight = height * outputWidth / width
        } else {
            outputHeight = videoResolution
            outputWidth = width * outputHeight / height
        }
        // many encoders do not work when height and width are not multiple of 16 (also, some iPhones do not play some heights)
        outputHeight = (outputHeight + 7) and 0xF.inv()
        outputWidth = (outputWidth + 7) and 0xF.inv()

        val outputWidthRotated: Int
        val outputHeightRotated: Int
        if (rotation % 180 == 90) {
            outputWidthRotated = outputHeight
            outputHeightRotated = outputWidth
        } else {
            outputWidthRotated = outputWidth
            outputHeightRotated = outputHeight
        }

        val outputVideoFormat = MediaFormat.createVideoFormat(videoCodec, outputWidthRotated, outputHeightRotated)

        // Set some properties. Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        outputVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
        outputVideoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_VIDEO_FRAME_RATE)
        outputVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL)
        if (Build.VERSION.SDK_INT >= 31 && isHdr(inputVideoFormat)) {
            outputVideoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        L.i { TAG + "Video encoder config: " + outputVideoFormat.toString() }

        // Create a MediaCodec for the desired codec, then configure it as an encoder with
        // our desired properties. Request a Surface to use for input.
        val inputSurfaceReference = AtomicReference<Surface>()
        mVideoEncoder = createVideoEncoder(videoCodecInfo, outputVideoFormat, inputSurfaceReference)
        mInputSurface = InputSurface(inputSurfaceReference.get())
        mInputSurface.makeCurrent()
        // Create a MediaCodec for the decoder, based on the extractor's format.
        mOutputSurface = OutputSurface()

        mOutputSurface.changeFragmentShader(
            createFragmentShader(
                inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH), inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT),
                outputWidth, outputHeight
            )
        )

        mVideoDecoder = createVideoDecoder(inputVideoFormat, mOutputSurface.getSurface())

        mVideoDecoderInputBuffers = mVideoDecoder.inputBuffers
        mVideoEncoderOutputBuffers = mVideoEncoder.outputBuffers
        mVideoDecoderOutputBufferInfo = MediaCodec.BufferInfo()
        mVideoEncoderOutputBufferInfo = MediaCodec.BufferInfo()

        if (mTimeFrom > 0) {
            mVideoExtractor.seekTo(mTimeFrom * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            L.i { TAG + "Seek video:" + mTimeFrom + " " + mVideoExtractor.getSampleTime() }
        }
    }

    private fun isHdr(inputVideoFormat: MediaFormat): Boolean {
        return try {
            val colorInfo = inputVideoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            colorInfo == MediaFormat.COLOR_TRANSFER_ST2084 || colorInfo == MediaFormat.COLOR_TRANSFER_HLG
        } catch (npe: NullPointerException) {
            // color transfer key does not exist, no color data supplied
            false
        }
    }

    @Throws(IOException::class)
    fun setMuxer(muxer: Muxer) {
        mMuxer = muxer
        if (mEncoderOutputVideoFormat != null) {
            L.d { TAG + "muxer: adding video track." }
            mOutputVideoTrack = muxer.addTrack(mEncoderOutputVideoFormat!!)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    @Throws(IOException::class, TranscodingException::class)
    fun step() {
        // Extract video from file and feed to decoder.
        // Do not extract video if we have determined the output format but we are not yet
        // ready to mux the frames.
        while (!mVideoExtractorDone &&
            (mEncoderOutputVideoFormat == null || mMuxer != null)
        ) {
            val decoderInputBufferIndex = mVideoDecoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
            if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) L.d { TAG + "no video decoder input buffer" }
                break
            }
            if (VERBOSE) {
                L.d { TAG + "video decoder: returned input buffer: " + decoderInputBufferIndex }
            }
            val decoderInputBuffer = mVideoDecoderInputBuffers[decoderInputBufferIndex]
            val size = mVideoExtractor.readSampleData(decoderInputBuffer, 0)
            val presentationTime = mVideoExtractor.getSampleTime()
            if (VERBOSE) {
                L.d { TAG + "video extractor: returned buffer of size " + size }
                L.d { TAG + "video extractor: returned buffer for time " + presentationTime }
            }
            mVideoExtractorDone = size < 0 || (mTimeTo > 0 && presentationTime > mTimeTo * 1000)

            if (mVideoExtractorDone) {
                if (VERBOSE) L.d { TAG + "video extractor: EOS" }
                mVideoDecoder.queueInputBuffer(
                    decoderInputBufferIndex,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            } else {
                mVideoDecoder.queueInputBuffer(
                    decoderInputBufferIndex,
                    0,
                    size,
                    presentationTime,
                    mVideoExtractor.getSampleFlags()
                )
            }
            mVideoExtractor.advance()
            mVideoExtractedFrameCount++
            // We extracted a frame, let's try something else next.
            break
        }

        // Poll output frames from the video decoder and feed the encoder.
        while (!mVideoDecoderDone && (mEncoderOutputVideoFormat == null || mMuxer != null)) {
            val decoderOutputBufferIndex =
                mVideoDecoder.dequeueOutputBuffer(
                    mVideoDecoderOutputBufferInfo, TIMEOUT_USEC.toLong()
                )
            if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) L.d { TAG + "no video decoder output buffer" }
                break
            }
            if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (VERBOSE) L.d { TAG + "video decoder: output buffers changed" }
                break
            }
            if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (VERBOSE) {
                    L.d { TAG + "video decoder: output format changed: " + mVideoDecoder.getOutputFormat() }
                }
                break
            }
            if (VERBOSE) {
                L.d {
                    TAG + "video decoder: returned output buffer: " +
                        decoderOutputBufferIndex
                }
                L.d {
                    TAG + "video decoder: returned buffer of size " +
                        mVideoDecoderOutputBufferInfo.size
                }
            }
            if ((mVideoDecoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                if (VERBOSE) L.d { TAG + "video decoder: codec config buffer" }
                mVideoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                break
            }
            if (mVideoDecoderOutputBufferInfo.presentationTimeUs < mTimeFrom * 1000 &&
                (mVideoDecoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
            ) {
                if (VERBOSE) L.d { TAG + "video decoder: frame prior to " + mVideoDecoderOutputBufferInfo.presentationTimeUs }
                mVideoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                break
            }
            if (VERBOSE) {
                L.d { TAG + "video decoder: returned buffer for time " + mVideoDecoderOutputBufferInfo.presentationTimeUs }
            }
            val render = mVideoDecoderOutputBufferInfo.size != 0
            mVideoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, render)
            if (render) {
                if (VERBOSE) L.d { TAG + "output surface: await new image" }
                mOutputSurface.awaitNewImage()
                // Edit the frame and send it to the encoder.
                if (VERBOSE) L.d { TAG + "output surface: draw image" }
                mOutputSurface.drawImage()
                mInputSurface.setPresentationTime(mVideoDecoderOutputBufferInfo.presentationTimeUs * 1000)
                if (VERBOSE) L.d { TAG + "input surface: swap buffers" }
                mInputSurface.swapBuffers()
                if (VERBOSE) L.d { TAG + "video encoder: notified of new frame" }
            }
            if ((mVideoDecoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (VERBOSE) L.d { TAG + "video decoder: EOS" }
                mVideoDecoderDone = true
                mVideoEncoder.signalEndOfInputStream()
            }
            mVideoDecodedFrameCount++
            // We extracted a pending frame, let's try something else next.
            break
        }

        // Poll frames from the video encoder and send them to the muxer.
        while (!mVideoEncoderDone && (mEncoderOutputVideoFormat == null || mMuxer != null)) {
            val encoderOutputBufferIndex = mVideoEncoder.dequeueOutputBuffer(mVideoEncoderOutputBufferInfo, TIMEOUT_USEC.toLong())
            if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) L.d { TAG + "no video encoder output buffer" }
                if (mVideoDecoderDone) {
                    // on some devices and encoder stops after signalEndOfInputStream
                    L.w { TAG + "mVideoDecoderDone, but didn't get BUFFER_FLAG_END_OF_STREAM" }
                    mVideoEncodedFrameCount = mVideoDecodedFrameCount
                    mVideoEncoderDone = true
                }
                break
            }
            if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (VERBOSE) L.d { TAG + "video encoder: output buffers changed" }
                mVideoEncoderOutputBuffers = mVideoEncoder.outputBuffers
                break
            }
            if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (VERBOSE) L.d { TAG + "video encoder: output format changed" }
                Preconditions.checkState("video encoder changed its output format again?", mOutputVideoTrack < 0)
                mEncoderOutputVideoFormat = mVideoEncoder.getOutputFormat()
                break
            }
            Preconditions.checkState("should have added track before processing output", mMuxer != null)
            if (VERBOSE) {
                L.d { TAG + "video encoder: returned output buffer: " + encoderOutputBufferIndex }
                L.d { TAG + "video encoder: returned buffer of size " + mVideoEncoderOutputBufferInfo.size }
            }
            val encoderOutputBuffer = mVideoEncoderOutputBuffers[encoderOutputBufferIndex]
            if ((mVideoEncoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                if (VERBOSE) L.d { TAG + "video encoder: codec config buffer" }
                // Simply ignore codec config buffers.
                mVideoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
                break
            }
            if (VERBOSE) {
                L.d { TAG + "video encoder: returned buffer for time " + mVideoEncoderOutputBufferInfo.presentationTimeUs }
            }
            if (mVideoEncoderOutputBufferInfo.size != 0) {
                mMuxer!!.writeSampleData(mOutputVideoTrack, encoderOutputBuffer, mVideoEncoderOutputBufferInfo)
                mMuxingVideoPresentationTime = Math.max(mMuxingVideoPresentationTime, mVideoEncoderOutputBufferInfo.presentationTimeUs)
            }
            if ((mVideoEncoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (VERBOSE) L.d { TAG + "video encoder: EOS" }
                mVideoEncoderDone = true
            }
            mVideoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
            mVideoEncodedFrameCount++
            // We enqueued an encoded frame, let's try something else next.
            break
        }
    }

    @Throws(Exception::class)
    fun release() {
        var exception: Exception? = null
        try {
            mVideoExtractor.release()
        } catch (e: Exception) {
            L.e(e) { TAG + " error while releasing mVideoExtractor" }
            exception = e
        }
        try {
            mVideoDecoder.stop()
            mVideoDecoder.release()
        } catch (e: Exception) {
            L.e(e) { TAG + " error while releasing mVideoDecoder" }
            if (exception == null) {
                exception = e
            }
        }
        try {
            mOutputSurface.release()
        } catch (e: Exception) {
            L.e(e) { TAG + " error while releasing mOutputSurface" }
            if (exception == null) {
                exception = e
            }
        }
        try {
            mInputSurface.release()
        } catch (e: Exception) {
            L.e(e) { TAG + " error while releasing mInputSurface" }
            if (exception == null) {
                exception = e
            }
        }
        try {
            mVideoEncoder.stop()
            mVideoEncoder.release()
        } catch (e: Exception) {
            L.e(e) { TAG + " error while releasing mVideoEncoder" }
            if (exception == null) {
                exception = e
            }
        }
        if (exception != null) {
            throw exception
        }
    }

    fun dumpState(): String {
        return String.format(
            Locale.US,
            "V{" +
                "extracted:%d(done:%b) " +
                "decoded:%d(done:%b) " +
                "encoded:%d(done:%b) " +
                "muxing:%b(track:%d)} ",
            mVideoExtractedFrameCount, mVideoExtractorDone,
            mVideoDecodedFrameCount, mVideoDecoderDone,
            mVideoEncodedFrameCount, mVideoEncoderDone,
            mMuxer != null, mOutputVideoTrack
        )
    }

    fun verifyEndState() {
        Preconditions.checkState("encoded (" + mVideoEncodedFrameCount + ") and decoded (" + mVideoDecodedFrameCount + ") video frame counts should match", Extensions.isWithin(mVideoDecodedFrameCount.toLong(), mVideoEncodedFrameCount.toLong(), FRAME_RATE_TOLERANCE))
        Preconditions.checkState("decoded frame count should be less than extracted frame count", mVideoDecodedFrameCount <= mVideoExtractedFrameCount)
    }

    private fun createVideoDecoder(
        inputFormat: MediaFormat,
        surface: Surface
    ): MediaCodec {
        val decoderPair: Pair<MediaCodec, MediaFormat> = MediaCodecCompat.findDecoder(inputFormat)
        val decoder = decoderPair.first
        decoder.configure(decoderPair.second, surface, null, 0)
        decoder.start()
        return decoder
    }

    @Throws(IOException::class)
    private fun createVideoEncoder(
        codecInfo: MediaCodecInfo,
        format: MediaFormat,
        surfaceReference: AtomicReference<Surface>
    ): MediaCodec {
        val tonemapRequested = isTonemapEnabled(format)
        val encoder = MediaCodec.createByCodecName(codecInfo.name)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (tonemapRequested && !isTonemapEnabled(format)) {
            L.d { TAG + "HDR tone-mapping requested but not supported by the decoder." }
        }
        // Must be called before start()
        surfaceReference.set(encoder.createInputSurface())
        encoder.start()
        return encoder
    }

    companion object {
        private const val TAG = "media-converter"
        private const val VERBOSE = false // lots of logging

        private const val OUTPUT_VIDEO_IFRAME_INTERVAL = 1 // 1 second between I-frames
        private const val OUTPUT_VIDEO_FRAME_RATE = 30 // needed only for MediaFormat.KEY_I_FRAME_INTERVAL to work; the actual frame rate matches the source

        private const val TIMEOUT_USEC = 10000

        private const val MEDIA_FORMAT_KEY_DISPLAY_WIDTH = "display-width"
        private const val MEDIA_FORMAT_KEY_DISPLAY_HEIGHT = "display-height"

        private const val FRAME_RATE_TOLERANCE = 0.05f // tolerance for transcoding VFR -> CFR

        @Throws(IOException::class, TranscodingException::class)
        fun create(
            input: MediaInput,
            timeFrom: Long,
            timeTo: Long,
            videoResolution: Int,
            videoBitrate: Int,
            videoCodec: String
        ): VideoTrackConverter? {
            val videoExtractor = input.createExtractor()
            val videoInputTrack = getAndSelectVideoTrackIndex(videoExtractor)
            if (videoInputTrack == -1) {
                videoExtractor.release()
                return null
            }
            return VideoTrackConverter(videoExtractor, videoInputTrack, timeFrom, timeTo, videoResolution, videoBitrate, videoCodec)
        }

        @Suppress("LongMethod")
        private fun createFragmentShader(
            srcWidth: Int,
            srcHeight: Int,
            dstWidth: Int,
            dstHeight: Int
        ): String {
            val kernelSizeX = srcWidth.toFloat() / dstWidth.toFloat()
            val kernelSizeY = srcHeight.toFloat() / dstHeight.toFloat()
            L.i { TAG + "kernel " + kernelSizeX + "x" + kernelSizeY }
            val shader: String
            if (kernelSizeX <= 2 && kernelSizeY <= 2) {
                shader =
                    "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" + // highp here doesn't seem to matter
                        "varying vec2 vTextureCoord;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "void main() {\n" +
                        "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                        "}\n"
            } else {
                val kernelRadiusX = Math.ceil((kernelSizeX - .1f).toDouble()).toInt() / 2
                val kernelRadiusY = Math.ceil((kernelSizeY - .1f).toDouble()).toInt() / 2
                val stepX = kernelSizeX / (1 + 2 * kernelRadiusX) * (1f / srcWidth)
                val stepY = kernelSizeY / (1 + 2 * kernelRadiusY) * (1f / srcHeight)
                val sum = ((1 + 2 * kernelRadiusX) * (1 + 2 * kernelRadiusY)).toFloat()
                val colorLoop = StringBuilder()
                for (i in -kernelRadiusX..kernelRadiusX) {
                    for (j in -kernelRadiusY..kernelRadiusY) {
                        if (i != 0 || j != 0) {
                            colorLoop.append("      + texture2D(sTexture, vTextureCoord.xy + vec2(")
                                .append(i * stepX).append(", ").append(j * stepY).append("))\n")
                        }
                    }
                }
                shader =
                    "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" + // highp here doesn't seem to matter
                        "varying vec2 vTextureCoord;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "void main() {\n" +
                        "    gl_FragColor = (texture2D(sTexture, vTextureCoord)\n" +
                        colorLoop.toString() +
                        "    ) / " + sum + ";\n" +
                        "}\n"
            }
            L.i { TAG + shader }
            return shader
        }

        private fun isTonemapEnabled(format: MediaFormat): Boolean {
            if (Build.VERSION.SDK_INT < 31) {
                return false
            }
            return try {
                val request = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
                request == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            } catch (npe: NullPointerException) {
                // transfer request key does not exist, tone mapping not requested
                false
            }
        }

        private fun getAndSelectVideoTrackIndex(extractor: MediaExtractor): Int {
            for (index in 0 until extractor.trackCount) {
                if (VERBOSE) {
                    val currentIndex = index
                    L.d { TAG + "format for track " + currentIndex + " is " + MediaConverter.getMimeTypeFor(extractor.getTrackFormat(currentIndex)) }
                }
                if (isVideoFormat(extractor.getTrackFormat(index))) {
                    extractor.selectTrack(index)
                    return index
                }
            }
            return -1
        }

        private fun isVideoFormat(format: MediaFormat): Boolean {
            return MediaConverter.getMimeTypeFor(format).startsWith("video/")
        }
    }
}
