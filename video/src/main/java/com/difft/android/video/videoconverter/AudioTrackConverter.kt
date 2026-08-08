package com.difft.android.video.videoconverter

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.video.interfaces.MediaInput
import com.difft.android.video.interfaces.Muxer
import com.difft.android.video.videoconverter.utils.Preconditions
import com.difft.android.video.videoconverter.utils.VideoConstants
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Streaming audio-track transcoder. Ported 1:1 from the Signal-derived Java implementation as
 * part of the Kotlin port campaign (#1093). The drain/feed MediaCodec state machine in [step]
 * is preserved verbatim, so the class and that method exceed detekt size thresholds by design.
 */
@Suppress("LargeClass")
internal class AudioTrackConverter {

    private val mTimeFrom: Long
    private val mTimeTo: Long
    private val mAudioBitrate: Int

    val mInputDuration: Long

    private val mAudioExtractor: MediaExtractor
    private val mAudioDecoder: MediaCodec
    private val mAudioEncoder: MediaCodec

    private val instanceSampleBuffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)
    private val instanceBufferInfo = MediaCodec.BufferInfo()

    private val mAudioDecoderInputBuffers: Array<ByteBuffer>
    private var mAudioDecoderOutputBuffers: Array<ByteBuffer>
    private val mAudioEncoderInputBuffers: Array<ByteBuffer>
    private var mAudioEncoderOutputBuffers: Array<ByteBuffer>
    private val mAudioDecoderOutputBufferInfo: MediaCodec.BufferInfo
    private val mAudioEncoderOutputBufferInfo: MediaCodec.BufferInfo

    var mEncoderOutputAudioFormat: MediaFormat? = null

    var mAudioExtractorDone = false
    private var mAudioDecoderDone = false
    var mAudioEncoderDone = false
    private var skipTrancode = false

    private var mOutputAudioTrack = -1

    private var mPendingAudioDecoderOutputBufferIndex = -1
    var mMuxingAudioPresentationTime: Long = 0

    private var mAudioExtractedFrameCount = 0
    private var mAudioDecodedFrameCount = 0
    private var mAudioEncodedFrameCount = 0

    private var mMuxer: Muxer? = null

    @Throws(IOException::class)
    private constructor(
        audioExtractor: MediaExtractor,
        audioInputTrack: Int,
        timeFrom: Long,
        timeTo: Long,
        audioBitrate: Int,
        allowSkipTranscode: Boolean
    ) {
        mTimeFrom = timeFrom
        mTimeTo = timeTo
        mAudioExtractor = audioExtractor
        mAudioBitrate = audioBitrate

        val audioCodecInfo = MediaConverter.selectCodec(OUTPUT_AUDIO_MIME_TYPE)
        if (audioCodecInfo == null) {
            // Don't fail CTS if they don't have an AAC codec (not here, anyway).
            L.e { TAG + "Unable to find an appropriate codec for " + OUTPUT_AUDIO_MIME_TYPE }
            throw FileNotFoundException()
        }
        if (VERBOSE) L.d { TAG + "audio found codec: " + audioCodecInfo.name }

        val inputAudioFormat = mAudioExtractor.getTrackFormat(audioInputTrack)
        mInputDuration = if (inputAudioFormat.containsKey(MediaFormat.KEY_DURATION)) inputAudioFormat.getLong(MediaFormat.KEY_DURATION) else 0

        skipTrancode = allowSkipTranscode && formatCanSkipTranscode(inputAudioFormat, audioBitrate)
        if (skipTrancode) {
            mEncoderOutputAudioFormat = inputAudioFormat
        }

        if (VERBOSE) L.d { TAG + "audio skipping transcoding: " + skipTrancode }

        val outputAudioFormat =
            MediaFormat.createAudioFormat(
                OUTPUT_AUDIO_MIME_TYPE,
                inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            )
        outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
        outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE)
        outputAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_BUFFER_SIZE)

        // Create a MediaCodec for the desired codec, then configure it as an encoder with
        // our desired properties. Request a Surface to use for input.
        mAudioEncoder = createAudioEncoder(audioCodecInfo, outputAudioFormat)
        // Create a MediaCodec for the decoder, based on the extractor's format.
        mAudioDecoder = createAudioDecoder(inputAudioFormat)

        mAudioDecoderInputBuffers = mAudioDecoder.inputBuffers
        mAudioDecoderOutputBuffers = mAudioDecoder.outputBuffers
        mAudioEncoderInputBuffers = mAudioEncoder.inputBuffers
        mAudioEncoderOutputBuffers = mAudioEncoder.outputBuffers
        mAudioDecoderOutputBufferInfo = MediaCodec.BufferInfo()
        mAudioEncoderOutputBufferInfo = MediaCodec.BufferInfo()

        if (mTimeFrom > 0) {
            mAudioExtractor.seekTo(mTimeFrom * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            L.i { TAG + "Seek audio:" + mTimeFrom + " " + mAudioExtractor.getSampleTime() }
        }
    }

    @Throws(IOException::class)
    fun setMuxer(muxer: Muxer) {
        mMuxer = muxer
        if (mEncoderOutputAudioFormat != null) {
            L.d { TAG + "muxer: adding audio track." }
            if (!mEncoderOutputAudioFormat!!.containsKey(MediaFormat.KEY_BIT_RATE)) {
                L.d { TAG + "muxer: fixed MediaFormat to add bitrate." }
                mEncoderOutputAudioFormat!!.setInteger(MediaFormat.KEY_BIT_RATE, mAudioBitrate)
            }
            if (!mEncoderOutputAudioFormat!!.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
                L.d { TAG + "muxer: fixed MediaFormat to add AAC profile." }
                mEncoderOutputAudioFormat!!.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE)
            }
            mOutputAudioTrack = muxer.addTrack(mEncoderOutputAudioFormat!!)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    @Throws(IOException::class)
    fun step() {
        if (skipTrancode && mEncoderOutputAudioFormat != null) {
            try {
                extractAndRemux()
                return
            } catch (e: IllegalArgumentException) {
                L.w(e) { TAG + " Remuxer threw an exception! Disabling remux." }
                skipTrancode = false
            }
        }

        // Extract audio from file and feed to decoder.
        // Do not extract audio if we have determined the output format but we are not yet
        // ready to mux the frames.
        while (!mAudioExtractorDone && (mEncoderOutputAudioFormat == null || mMuxer != null)) {
            val decoderInputBufferIndex = mAudioDecoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
            if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) L.d { TAG + "no audio decoder input buffer" }
                break
            }
            if (VERBOSE) {
                L.d { TAG + "audio decoder: returned input buffer: " + decoderInputBufferIndex }
            }
            val decoderInputBuffer = mAudioDecoderInputBuffers[decoderInputBufferIndex]
            val size = mAudioExtractor.readSampleData(decoderInputBuffer, 0)
            val presentationTime = mAudioExtractor.getSampleTime()
            if (VERBOSE) {
                L.d { TAG + "audio extractor: returned buffer of size " + size }
                L.d { TAG + "audio extractor: returned buffer for time " + presentationTime }
            }
            mAudioExtractorDone = isAudioExtractorDone(size, presentationTime)

            if (mAudioExtractorDone) {
                if (VERBOSE) L.d { TAG + "audio extractor: EOS" }
                mAudioDecoder.queueInputBuffer(
                    decoderInputBufferIndex,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            } else {
                mAudioDecoder.queueInputBuffer(
                    decoderInputBufferIndex,
                    0,
                    size,
                    presentationTime,
                    mAudioExtractor.getSampleFlags()
                )
            }
            mAudioExtractor.advance()
            mAudioExtractedFrameCount++
            // We extracted a frame, let's try something else next.
            break
        }

        // Poll output frames from the audio decoder.
        // Do not poll if we already have a pending buffer to feed to the encoder.
        while (!mAudioDecoderDone && mPendingAudioDecoderOutputBufferIndex == -1 &&
            (mEncoderOutputAudioFormat == null || mMuxer != null)
        ) {
            val decoderOutputBufferIndex =
                mAudioDecoder.dequeueOutputBuffer(
                    mAudioDecoderOutputBufferInfo, TIMEOUT_USEC.toLong()
                )
            if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) L.d { TAG + "no audio decoder output buffer" }
                break
            }
            if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (VERBOSE) L.d { TAG + "audio decoder: output buffers changed" }
                mAudioDecoderOutputBuffers = mAudioDecoder.outputBuffers
                break
            }
            if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (VERBOSE) {
                    val decoderOutputAudioFormat = mAudioDecoder.getOutputFormat()
                    L.d { TAG + "audio decoder: output format changed: " + decoderOutputAudioFormat }
                }
                break
            }
            if (VERBOSE) {
                L.d { TAG + "audio decoder: returned output buffer: " + decoderOutputBufferIndex }
                L.d { TAG + "audio decoder: returned buffer of size " + mAudioDecoderOutputBufferInfo.size }
            }
            if ((mAudioDecoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                if (VERBOSE) L.d { TAG + "audio decoder: codec config buffer" }
                mAudioDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                break
            }
            if (mAudioDecoderOutputBufferInfo.presentationTimeUs < mTimeFrom * 1000 &&
                (mAudioDecoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
            ) {
                if (VERBOSE) {
                    L.d { TAG + "audio decoder: frame prior to " + mAudioDecoderOutputBufferInfo.presentationTimeUs }
                }
                mAudioDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                break
            }
            if (VERBOSE) {
                L.d { TAG + "audio decoder: returned buffer for time " + mAudioDecoderOutputBufferInfo.presentationTimeUs }
                L.d { TAG + "audio decoder: output buffer is now pending: " + mPendingAudioDecoderOutputBufferIndex }
            }
            mPendingAudioDecoderOutputBufferIndex = decoderOutputBufferIndex
            mAudioDecodedFrameCount++
            // We extracted a pending frame, let's try something else next.
            break
        }

        // Feed the pending decoded audio buffer to the audio encoder.
        while (mPendingAudioDecoderOutputBufferIndex != -1) {
            if (VERBOSE) {
                L.d { TAG + "audio decoder: attempting to process pending buffer: " + mPendingAudioDecoderOutputBufferIndex }
            }
            val encoderInputBufferIndex = mAudioEncoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
            if (encoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) L.d { TAG + "no audio encoder input buffer" }
                break
            }
            if (VERBOSE) {
                L.d { TAG + "audio encoder: returned input buffer: " + encoderInputBufferIndex }
            }
            val encoderInputBuffer = mAudioEncoderInputBuffers[encoderInputBufferIndex]
            val size = mAudioDecoderOutputBufferInfo.size
            val presentationTime = mAudioDecoderOutputBufferInfo.presentationTimeUs
            if (VERBOSE) {
                L.d { TAG + "audio decoder: processing pending buffer: " + mPendingAudioDecoderOutputBufferIndex }
            }
            if (VERBOSE) {
                L.d { TAG + "audio decoder: pending buffer of size " + size }
                L.d { TAG + "audio decoder: pending buffer for time " + presentationTime }
            }
            if (size >= 0) {
                val decoderOutputBuffer = mAudioDecoderOutputBuffers[mPendingAudioDecoderOutputBufferIndex].duplicate()
                decoderOutputBuffer.position(mAudioDecoderOutputBufferInfo.offset)
                decoderOutputBuffer.limit(mAudioDecoderOutputBufferInfo.offset + size)
                encoderInputBuffer.position(0)
                encoderInputBuffer.put(decoderOutputBuffer)

                mAudioEncoder.queueInputBuffer(
                    encoderInputBufferIndex,
                    0,
                    size,
                    presentationTime,
                    mAudioDecoderOutputBufferInfo.flags
                )
            }
            mAudioDecoder.releaseOutputBuffer(mPendingAudioDecoderOutputBufferIndex, false)
            mPendingAudioDecoderOutputBufferIndex = -1
            if ((mAudioDecoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (VERBOSE) L.d { TAG + "audio decoder: EOS" }
                mAudioDecoderDone = true
            }
            // We enqueued a pending frame, let's try something else next.
            break
        }

        // Poll frames from the audio encoder and send them to the muxer.
        while (!mAudioEncoderDone && (mEncoderOutputAudioFormat == null || mMuxer != null)) {
            val encoderOutputBufferIndex = mAudioEncoder.dequeueOutputBuffer(mAudioEncoderOutputBufferInfo, TIMEOUT_USEC.toLong())
            if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) L.d { TAG + "no audio encoder output buffer" }
                break
            }
            if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (VERBOSE) L.d { TAG + "audio encoder: output buffers changed" }
                mAudioEncoderOutputBuffers = mAudioEncoder.outputBuffers
                break
            }
            if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (VERBOSE) L.d { TAG + "audio encoder: output format changed" }
                Preconditions.checkState("audio encoder changed its output format again?", mOutputAudioTrack < 0)

                mEncoderOutputAudioFormat = mAudioEncoder.getOutputFormat()
                break
            }
            Preconditions.checkState("should have added track before processing output", mMuxer != null)
            if (VERBOSE) {
                L.d { TAG + "audio encoder: returned output buffer: " + encoderOutputBufferIndex }
                L.d { TAG + "audio encoder: returned buffer of size " + mAudioEncoderOutputBufferInfo.size }
            }
            val encoderOutputBuffer = mAudioEncoderOutputBuffers[encoderOutputBufferIndex]
            if ((mAudioEncoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                if (VERBOSE) L.d { TAG + "audio encoder: codec config buffer" }
                // Simply ignore codec config buffers.
                mAudioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
                break
            }
            if (VERBOSE) {
                L.d { TAG + "audio encoder: returned buffer for time " + mAudioEncoderOutputBufferInfo.presentationTimeUs }
            }
            if (mAudioEncoderOutputBufferInfo.size != 0) {
                mMuxer!!.writeSampleData(mOutputAudioTrack, encoderOutputBuffer, mAudioEncoderOutputBufferInfo)
                mMuxingAudioPresentationTime = Math.max(mMuxingAudioPresentationTime, mAudioEncoderOutputBufferInfo.presentationTimeUs)
            }
            if ((mAudioEncoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (VERBOSE) L.d { TAG + "audio encoder: EOS" }
                mAudioEncoderDone = true
            }
            mAudioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
            mAudioEncodedFrameCount++
            // We enqueued an encoded frame, let's try something else next.
            break
        }
    }

    @Throws(Exception::class)
    fun release() {
        var exception: Exception? = null
        try {
            mAudioExtractor.release()
        } catch (e: Exception) {
            L.e(e) { TAG + " error while releasing mAudioExtractor" }
            exception = e
        }
        try {
            mAudioDecoder.stop()
            mAudioDecoder.release()
        } catch (e: Exception) {
            L.e(e) { TAG + " error while releasing mAudioDecoder" }
            if (exception == null) {
                exception = e
            }
        }
        try {
            mAudioEncoder.stop()
            mAudioEncoder.release()
        } catch (e: Exception) {
            L.e(e) { TAG + " error while releasing mAudioEncoder" }
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
            "A{" +
                "extracted:%d(done:%b) " +
                "decoded:%d(done:%b) " +
                "encoded:%d(done:%b) " +
                "pending:%d " +
                "muxing:%b(track:%d} )",
            mAudioExtractedFrameCount, mAudioExtractorDone,
            mAudioDecodedFrameCount, mAudioDecoderDone,
            mAudioEncodedFrameCount, mAudioEncoderDone,
            mPendingAudioDecoderOutputBufferIndex,
            mMuxer != null, mOutputAudioTrack
        )
    }

    fun verifyEndState() {
        Preconditions.checkState("no frame should be pending", -1 == mPendingAudioDecoderOutputBufferIndex)
    }

    @SuppressLint("WrongConstant") // flags extracted from sample by MediaExtractor should be safe for MediaCodec.BufferInfo
    @Throws(IOException::class)
    private fun extractAndRemux() {
        if (mMuxer == null) {
            L.d { TAG + "audio remuxer: tried to execute before muxer was ready" }
            return
        }
        val size = mAudioExtractor.readSampleData(instanceSampleBuffer, 0)
        val presentationTime = mAudioExtractor.getSampleTime()
        val sampleFlags = mAudioExtractor.getSampleFlags()
        if (VERBOSE) {
            L.d { TAG + "audio extractor: returned buffer of size " + size }
            L.d { TAG + "audio extractor: returned buffer for time " + presentationTime }
            L.d { TAG + "audio extractor: returned buffer with flags " + Integer.toBinaryString(sampleFlags) }
        }
        mAudioExtractorDone = isAudioExtractorDone(size, presentationTime)

        if (mAudioExtractorDone) {
            if (VERBOSE) L.d { TAG + "audio encoder: EOS" }
            instanceBufferInfo.set(0, 0, presentationTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            mAudioEncoderDone = true
        } else {
            instanceBufferInfo.set(0, size, presentationTime, sampleFlags)
        }

        mMuxer!!.writeSampleData(mOutputAudioTrack, instanceSampleBuffer, instanceBufferInfo)

        if (VERBOSE) {
            L.d { TAG + "audio extractor: wrote sample at " + presentationTime }
        }

        mAudioExtractor.advance()

        mAudioExtractedFrameCount++
        mAudioEncodedFrameCount++
        mMuxingAudioPresentationTime = Math.max(mMuxingAudioPresentationTime, presentationTime)
    }

    private fun isAudioExtractorDone(size: Int, presentationTime: Long): Boolean {
        return presentationTime == -1L || size < 0 || (mTimeTo > 0 && presentationTime > mTimeTo * 1000)
    }

    companion object {
        private const val TAG = "media-converter"
        private const val VERBOSE = false // lots of logging

        private const val OUTPUT_AUDIO_MIME_TYPE = VideoConstants.AUDIO_MIME_TYPE // Advanced Audio Coding
        private val OUTPUT_AUDIO_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC // MediaCodecInfo.CodecProfileLevel.AACObjectHE;

        private const val SAMPLE_BUFFER_SIZE = 16 * 1024
        private const val TIMEOUT_USEC = 10000

        @Throws(IOException::class)
        fun create(
            input: MediaInput,
            timeFrom: Long,
            timeTo: Long,
            audioBitrate: Int,
            allowSkipTranscode: Boolean
        ): AudioTrackConverter? {
            val audioExtractor = input.createExtractor()
            val audioInputTrack = getAndSelectAudioTrackIndex(audioExtractor)
            if (audioInputTrack == -1) {
                audioExtractor.release()
                return null
            }
            return AudioTrackConverter(audioExtractor, audioInputTrack, timeFrom, timeTo, audioBitrate, allowSkipTranscode)
        }

        private fun createAudioDecoder(inputFormat: MediaFormat): MediaCodec {
            val decoder = MediaCodec.createDecoderByType(MediaConverter.getMimeTypeFor(inputFormat))
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            return decoder
        }

        private fun createAudioEncoder(codecInfo: MediaCodecInfo, format: MediaFormat): MediaCodec {
            val encoder = MediaCodec.createByCodecName(codecInfo.name)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            return encoder
        }

        private fun getAndSelectAudioTrackIndex(extractor: MediaExtractor): Int {
            for (index in 0 until extractor.trackCount) {
                if (VERBOSE) {
                    val currentIndex = index
                    L.d { TAG + "format for track " + currentIndex + " is " + MediaConverter.getMimeTypeFor(extractor.getTrackFormat(currentIndex)) }
                }
                if (isAudioFormat(extractor.getTrackFormat(index))) {
                    extractor.selectTrack(index)
                    return index
                }
            }
            return -1
        }

        private fun isAudioFormat(format: MediaFormat): Boolean {
            return MediaConverter.getMimeTypeFor(format).startsWith("audio/")
        }

        /**
         * HE-AAC input bitstreams exhibit bad decoder behavior: the decoder's output buffer's presentation timestamp is way larger than the input sample's.
         * This mismatch propagates throughout the transcoding pipeline and results in slowed, distorted audio in the output file.
         * To sidestep this: AAC and its variants are a supported output codec, and HE-AAC bitrates are almost always lower than our target bitrate,
         * so we can pass through the input bitstream unaltered, relying on consumers of the output file to render HE-AAC correctly.
         */
        private fun formatCanSkipTranscode(audioFormat: MediaFormat, desiredBitrate: Int): Boolean {
            return try {
                val inputBitrate = audioFormat.getInteger(MediaFormat.KEY_BIT_RATE)
                val inputMimeType = audioFormat.getString(MediaFormat.KEY_MIME)
                OUTPUT_AUDIO_MIME_TYPE == inputMimeType && inputBitrate <= desiredBitrate
            } catch (exception: NullPointerException) {
                if (VERBOSE) {
                    L.d { TAG + "could not find bitrate in mediaFormat, can't skip transcoding." }
                }
                false
            }
        }
    }
}
