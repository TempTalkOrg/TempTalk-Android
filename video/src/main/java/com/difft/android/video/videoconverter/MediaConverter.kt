/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file has been modified by Signal.
 */

package com.difft.android.video.videoconverter

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.video.interfaces.MediaInput
import com.difft.android.video.interfaces.Muxer
import com.difft.android.video.videoconverter.exceptions.EncodingException
import com.difft.android.video.videoconverter.muxer.StreamingMuxer
import java.io.IOException
import java.io.OutputStream

internal class MediaConverter {

    private var mInput: MediaInput? = null
    private var mOutput: Output? = null

    private var mTimeFrom: Long = 0
    private var mTimeTo: Long = 0
    private var mVideoResolution = 0
    private var mVideoBitrate = 2000000 // 2Mbps
    private var mVideoCodec = VIDEO_CODEC_H264
    private var mAudioBitrate = 128000 // 128Kbps
    private var mAllowAudioRemux = false

    private var mListener: Listener? = null
    private var mCancelled = false

    fun interface Listener {
        fun onProgress(percent: Int): Boolean
    }

    fun setInput(videoInput: MediaInput) {
        mInput = videoInput
    }

    fun setOutput(stream: OutputStream) {
        mOutput = StreamOutput(stream)
    }

    fun setTimeRange(timeFrom: Long, timeTo: Long) {
        mTimeFrom = timeFrom
        mTimeTo = timeTo

        if (timeTo > 0 && timeFrom >= timeTo) {
            throw IllegalArgumentException("timeFrom:$timeFrom timeTo:$timeTo")
        }
    }

    fun setVideoResolution(videoResolution: Int) {
        mVideoResolution = videoResolution
    }

    fun setVideoBitrate(videoBitrate: Int) {
        mVideoBitrate = videoBitrate
    }

    fun setAudioBitrate(audioBitrate: Int) {
        mAudioBitrate = audioBitrate
    }

    fun setListener(listener: Listener?) {
        mListener = listener
    }

    fun setAllowAudioRemux(allow: Boolean) {
        mAllowAudioRemux = allow
    }

    @WorkerThread
    @Throws(EncodingException::class, IOException::class)
    fun convert() {
        // Exception that may be thrown during release.
        var exception: Exception? = null
        var muxer: Muxer? = null
        var videoTrackConverter: VideoTrackConverter? = null
        var audioTrackConverter: AudioTrackConverter? = null

        try {
            muxer = mOutput!!.createMuxer()

            videoTrackConverter = VideoTrackConverter.create(mInput!!, mTimeFrom, mTimeTo, mVideoResolution, mVideoBitrate, mVideoCodec)
            audioTrackConverter = AudioTrackConverter.create(mInput!!, mTimeFrom, mTimeTo, mAudioBitrate, mAllowAudioRemux && muxer.supportsAudioRemux())

            if (videoTrackConverter == null && audioTrackConverter == null) {
                throw EncodingException("No video and audio tracks")
            }

            doExtractDecodeEditEncodeMux(
                videoTrackConverter,
                audioTrackConverter,
                muxer
            )
        } catch (e: EncodingException) {
            L.e(e) { TAG + " error converting" }
            exception = e
            throw e
        } catch (e: IOException) {
            L.e(e) { TAG + " error converting" }
            exception = e
            throw e
        } catch (e: Exception) {
            L.e(e) { TAG + " error converting" }
            exception = e
        } finally {
            if (VERBOSE) L.d { TAG + "releasing extractor, decoder, encoder, and muxer" }
            // Try to release everything we acquired, even if one of the releases fails, in which
            // case we save the first exception we got and re-throw at the end (unless something
            // other exception has already been thrown). This guarantees the first exception thrown
            // is reported as the cause of the error, everything is (attempted) to be released, and
            // all other exceptions appear in the logs.
            try {
                if (videoTrackConverter != null) {
                    videoTrackConverter.release()
                }
            } catch (e: Exception) {
                if (exception == null) {
                    exception = e
                }
            }
            try {
                if (audioTrackConverter != null) {
                    audioTrackConverter.release()
                }
            } catch (e: Exception) {
                if (exception == null) {
                    exception = e
                }
            }
            try {
                if (muxer != null) {
                    muxer.stop()
                    muxer.release()
                }
            } catch (e: Exception) {
                L.e(e) { TAG + " error while releasing muxer" }
                if (exception == null) {
                    exception = e
                }
            }
        }
        if (exception != null) {
            throw EncodingException("Transcode failed", exception)
        }
    }

    /**
     * Does the actual work for extracting, decoding, encoding and muxing.
     */
    @Throws(IOException::class, TranscodingException::class)
    private fun doExtractDecodeEditEncodeMux(
        videoTrackConverter: VideoTrackConverter?,
        audioTrackConverter: AudioTrackConverter?,
        muxer: Muxer
    ) {
        var muxing = false
        var percentProcessed = 0
        val inputDuration = Math.max(
            if (videoTrackConverter == null) 0L else videoTrackConverter.mInputDuration,
            if (audioTrackConverter == null) 0L else audioTrackConverter.mInputDuration
        )

        while (!mCancelled &&
            ((videoTrackConverter != null && !videoTrackConverter.mVideoEncoderDone) ||
                (audioTrackConverter != null && !audioTrackConverter.mAudioEncoderDone))
        ) {
            if (VERBOSE) {
                val currentMuxing = muxing
                L.d {
                    TAG + "loop: " +
                        (if (videoTrackConverter == null) "" else videoTrackConverter.dumpState()) +
                        (if (audioTrackConverter == null) "" else audioTrackConverter.dumpState()) +
                        " muxing:" + currentMuxing
                }
            }

            if (videoTrackConverter != null && (audioTrackConverter == null || audioTrackConverter.mAudioExtractorDone || videoTrackConverter.mMuxingVideoPresentationTime <= audioTrackConverter.mMuxingAudioPresentationTime)) {
                videoTrackConverter.step()
            }

            if (audioTrackConverter != null && (videoTrackConverter == null || videoTrackConverter.mVideoExtractorDone || videoTrackConverter.mMuxingVideoPresentationTime >= audioTrackConverter.mMuxingAudioPresentationTime)) {
                audioTrackConverter.step()
            }

            if (inputDuration != 0L && mListener != null) {
                val timeFromUs = if (mTimeFrom <= 0) 0L else mTimeFrom * 1000
                val timeToUs = if (mTimeTo <= 0) inputDuration else mTimeTo * 1000
                val curPercentProcessed = (100 *
                    (Math.max(
                        if (videoTrackConverter == null) 0L else videoTrackConverter.mMuxingVideoPresentationTime,
                        if (audioTrackConverter == null) 0L else audioTrackConverter.mMuxingAudioPresentationTime
                    ) - timeFromUs) / (timeToUs - timeFromUs)).toInt()

                if (curPercentProcessed != percentProcessed) {
                    percentProcessed = curPercentProcessed
                    mCancelled = mCancelled || mListener!!.onProgress(percentProcessed)
                }
            }

            if (!muxing &&
                (videoTrackConverter == null || videoTrackConverter.mEncoderOutputVideoFormat != null) &&
                (audioTrackConverter == null || audioTrackConverter.mEncoderOutputAudioFormat != null)
            ) {
                if (videoTrackConverter != null) {
                    videoTrackConverter.setMuxer(muxer)
                }
                if (audioTrackConverter != null) {
                    audioTrackConverter.setMuxer(muxer)
                }
                L.d { TAG + "muxer: starting" }
                muxer.start()
                muxing = true
            }
        }

        // Basic sanity checks.
        if (videoTrackConverter != null) {
            videoTrackConverter.verifyEndState()
        }
        if (audioTrackConverter != null) {
            audioTrackConverter.verifyEndState()
        }
    }

    internal interface Output {
        @Throws(IOException::class)
        fun createMuxer(): Muxer
    }

    private class StreamOutput(val outputStream: OutputStream) : Output {

        override fun createMuxer(): Muxer {
            return StreamingMuxer(outputStream)
        }
    }

    companion object {
        private const val TAG = "media-converter"
        private const val VERBOSE = false // lots of logging

        const val VIDEO_CODEC_H264 = "video/avc"

        internal fun getMimeTypeFor(format: MediaFormat): String {
            return format.getString(MediaFormat.KEY_MIME)!!
        }

        /**
         * Returns the first codec capable of encoding the specified MIME type, or null if no match was
         * found.
         */
        internal fun selectCodec(mimeType: String): MediaCodecInfo? {
            val numCodecs = MediaCodecList.getCodecCount()
            for (i in 0 until numCodecs) {
                val codecInfo = MediaCodecList.getCodecInfoAt(i)

                if (!codecInfo.isEncoder) {
                    continue
                }

                val types = codecInfo.supportedTypes
                for (type in types) {
                    if (type.equals(mimeType, ignoreCase = true)) {
                        return codecInfo
                    }
                }
            }
            return null
        }
    }
}
