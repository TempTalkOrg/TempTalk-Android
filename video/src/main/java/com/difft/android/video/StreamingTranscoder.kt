package com.difft.android.video

import android.media.MediaMetadataRetriever
import com.difft.android.base.log.lumberjack.L
import com.google.common.io.CountingOutputStream
import com.difft.android.video.exceptions.VideoSizeException
import com.difft.android.video.exceptions.VideoSourceException
import com.difft.android.video.interfaces.MediaInput
import com.difft.android.video.interfaces.TranscoderCancelationSignal
import com.difft.android.video.videoconverter.MediaConverter
import com.difft.android.video.videoconverter.exceptions.EncodingException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Create a transcoder over an abstract [VideoSource].
 *
 * [VideoSource] is the single place that decides how the source binds to the platform, so a
 * `file://` source still reaches the native file path here while a `content://` source is read
 * through its provider's file descriptor. Both are the same code path from this point on.
 *
 * @param source The video source to transcode
 * @param upperSizeLimit A upper size to transcode to. The actual output size can be up to 10% smaller.
 */
class StreamingTranscoder
@Throws(IOException::class, VideoSourceException::class)
constructor(
    source: VideoSource,
    private val options: TranscoderOptions?,
    preset: TranscodingPreset,
    private val upperSizeLimit: Long,
    private val allowAudioRemux: Boolean
) {

    private val mediaInput: MediaInput = source.mediaInput
    private val inSize: Long
    private val duration: Long
    private val inputBitRate: Int
    private val targetQuality: TranscodingQuality
    private val transcodeRequired: Boolean
    private val fileSizeEstimate: Long

    val isTranscodeRequired: Boolean
        get() = transcodeRequired

    init {
        val mediaMetadataRetriever = MediaMetadataRetriever()
        duration = try {
            bindOrThrow(source, mediaMetadataRetriever)

            if (options != null && options.endTimeUs != 0L) {
                TimeUnit.MICROSECONDS.toMillis(options.endTimeUs - options.startTimeUs)
            } else {
                getDuration(mediaMetadataRetriever)
            }
        } finally {
            // The retriever used to stay open until finalization. On the content path the
            // descriptor it holds is one the media provider handed us, counted against that
            // process's descriptor budget.
            runCatching { mediaMetadataRetriever.release() }
                .onFailure { L.w { "$TAG retriever release failed: ${it.javaClass.simpleName}" } }
        }

        inSize = source.sizeBytes
        if (inSize <= 0) {
            // Must not fall through. For a compression-only request (options == null) an inSize of
            // 0 makes transcodeRequired false below, and the unreadable original would be sent on
            // untouched with no error anywhere.
            throw VideoSourceException("Unable to determine input size scheme=${source.scheme}")
        }
        inputBitRate = TranscodingQuality.bitRate(inSize, duration)
        targetQuality = TranscodingQuality.createFromPreset(preset, duration)

        transcodeRequired = inputBitRate >= targetQuality.targetTotalBitRate * 1.2 || inSize > upperSizeLimit || options != null
        if (!transcodeRequired) {
            L.i { TAG + "Video is within 20% of target bitrate, below the size limit, or no custom options." }
        }

        fileSizeEstimate = targetQuality.byteCountEstimate
    }

    @Throws(IOException::class, EncodingException::class)
    fun transcode(
        progress: Progress,
        stream: OutputStream,
        cancelationSignal: TranscoderCancelationSignal?
    ) {
        val durationSec = duration / 1000f

        val numberFormat = NumberFormat.getInstance(Locale.US)

        L.i {
            TAG + String.format(
                Locale.US,
                "Transcoding:\n" +
                    "Target bitrate : %s + %s = %s\n" +
                    "Target format  : %dp\n" +
                    "Video duration : %.1fs\n" +
                    "Size limit     : %s kB\n" +
                    "Estimate       : %s kB\n" +
                    "Input size     : %s kB\n" +
                    "Input bitrate  : %s bps",
                numberFormat.format(targetQuality.targetVideoBitRate),
                numberFormat.format(targetQuality.targetAudioBitRate),
                numberFormat.format(targetQuality.targetTotalBitRate),
                targetQuality.outputResolution,
                durationSec,
                numberFormat.format(upperSizeLimit / 1024),
                numberFormat.format(fileSizeEstimate / 1024),
                numberFormat.format(inSize / 1024),
                numberFormat.format(inputBitRate)
            )
        }

        val sizeLimitEnabled = 0 < upperSizeLimit

        if (sizeLimitEnabled && upperSizeLimit < fileSizeEstimate) {
            throw VideoSizeException("Size constraints could not be met!")
        }

        val startTime = System.currentTimeMillis()

        val converter = MediaConverter()

        converter.setInput(mediaInput)
        val outStream: CountingOutputStream = if (sizeLimitEnabled) {
            CountingOutputStream(LimitedSizeOutputStream(stream, upperSizeLimit))
        } else {
            CountingOutputStream(stream)
        }
        converter.setOutput(outStream)
        converter.setVideoResolution(targetQuality.outputResolution)
        converter.setVideoBitrate(targetQuality.targetVideoBitRate)
        converter.setAudioBitrate(targetQuality.targetAudioBitRate)
        converter.setAllowAudioRemux(allowAudioRemux)

        if (options != null) {
            if (options.endTimeUs > 0) {
                val timeFrom = options.startTimeUs / 1000
                val timeTo = options.endTimeUs / 1000
                converter.setTimeRange(timeFrom, timeTo)
                L.i { TAG + String.format(Locale.US, "Trimming:\nTotal duration: %d\nKeeping: %d..%d\nFinal duration:(%d)", duration, timeFrom, timeTo, timeTo - timeFrom) }
            }
        }

        converter.setListener { percent ->
            progress.onProgress(percent)
            cancelationSignal != null && cancelationSignal.isCanceled()
        }

        converter.convert()

        val outSize = outStream.count
        val encodeDurationSec = (System.currentTimeMillis() - startTime) / 1000f

        L.i {
            TAG + String.format(
                Locale.US,
                "Transcoding complete:\n" +
                    "Transcode time : %.1fs (%.1fx)\n" +
                    "Output size    : %s kB\n" +
                    "  of Original  : %.1f%%\n" +
                    "  of Estimate  : %.1f%%\n" +
                    "Output bitrate : %s bps",
                encodeDurationSec,
                durationSec / encodeDurationSec,
                numberFormat.format(outSize / 1024),
                (outSize * 100.0) / inSize,
                (outSize * 100.0) / fileSizeEstimate,
                numberFormat.format(TranscodingQuality.bitRate(outSize, duration))
            )
        }

        if (sizeLimitEnabled && outSize > upperSizeLimit) {
            throw VideoSizeException("Size constraints could not be met!")
        }

        stream.flush()
    }

    fun interface Progress {
        fun onProgress(percent: Int)
    }

    private class LimitedSizeOutputStream(
        inner: OutputStream,
        private val sizeLimit: Long
    ) : FilterOutputStream(inner) {

        private var written: Long = 0

        @Throws(IOException::class)
        override fun write(b: Int) {
            incWritten(1)
            out.write(b)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            incWritten(len)
            out.write(b, off, len)
        }

        @Throws(IOException::class)
        private fun incWritten(len: Int) {
            val newWritten = written + len
            if (newWritten > sizeLimit) {
                L.w { TAG + String.format(Locale.US, "File size limit hit. Wrote %d, tried to write %d more. Limit is %d", written, len, sizeLimit) }
                throw VideoSizeException("File size limit hit")
            }
            written = newWritten
        }
    }

    companion object {
        private const val TAG = "StreamingTranscoder"

        /** Binds [source] onto [retriever], translating a bind failure into [VideoSourceException]. */
        @Throws(VideoSourceException::class)
        private fun bindOrThrow(source: VideoSource, retriever: MediaMetadataRetriever) {
            try {
                source.bindTo(retriever)
            } catch (e: RuntimeException) {
                L.w(e) { "$TAG Unable to read datasource scheme=${source.scheme}" }
                throw VideoSourceException("Unable to read datasource", e)
            }
        }

        @Throws(VideoSourceException::class)
        private fun getDuration(mediaMetadataRetriever: MediaMetadataRetriever): Long {
            val durationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?: throw VideoSourceException("Cannot determine duration of video, null meta data")
            try {
                val duration = durationString.toLong()
                if (duration <= 0) {
                    throw VideoSourceException("Cannot determine duration of video, meta data: $durationString")
                }
                return duration
            } catch (e: NumberFormatException) {
                throw VideoSourceException("Cannot determine duration of video, meta data: $durationString", e)
            }
        }
    }
}
