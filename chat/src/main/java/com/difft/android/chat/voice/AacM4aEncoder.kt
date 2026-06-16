package com.difft.android.chat.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.difft.android.base.log.lumberjack.L
import java.io.File

/**
 * AAC m4a encoder helper for [DualCandidateVoiceRecorder].
 *
 * Each instance owns one [MediaCodec] + [MediaMuxer]. [feed] accepts
 * normalized float32 PCM in [-1, 1]; we scale to int16 and hand the
 * bytes to the codec.
 *
 * Output drain is non-blocking on the hot path: after queueing input,
 * we call `drainOutput(blockMs = 0)` which returns immediately if no
 * output is ready. Output that arrives between feeds is picked up by the
 * next feed's drain (or by [signalEndOfStream]'s blocking drain). This
 * is the major performance win versus the demo's original 10 ms drain
 * timeout — without it the processor falls behind the mic and the
 * channel backlog grows linearly with recording length.
 */
internal class AacM4aEncoder(
    private val outputFile: File,
    private val sampleRate: Int,
    channels: Int,
    bitRate: Int,
    private val fileDeleter: (File) -> Unit,
) {
    private val format: MediaFormat = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels,
    ).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var trackIndex: Int = -1
    private var muxerStarted: Boolean = false
    private var presentationSampleIndex: Long = 0
    private var sentEos: Boolean = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private val name = outputFile.name

    fun start() {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun feed(floats: FloatArray) {
        val bytes = ByteArray(floats.size * 2)
        var i = 0
        while (i < floats.size) {
            val s = (floats[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            i++
        }
        writeBytesToCodec(bytes, isEnd = false)
    }

    fun signalEndOfStream(deleteFile: Boolean) {
        try {
            L.i { "[DualCandidateVoiceRecorder] encoder[$name] signal eos" }
            writeBytesToCodec(ByteArray(0), isEnd = true)
        } catch (t: Throwable) {
            L.e(t) { "[DualCandidateVoiceRecorder] encoder[$name] eos error" }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Throwable) {}
            try { muxer.release() } catch (_: Throwable) {}
            if (deleteFile) fileDeleter(outputFile)
            L.i {
                "[DualCandidateVoiceRecorder] encoder[$name] released " +
                    "exists=${outputFile.exists()} size=${outputFile.length()}"
            }
        }
    }

    private fun writeBytesToCodec(bytes: ByteArray, isEnd: Boolean) {
        var written = 0
        while (written < bytes.size || (isEnd && !sentEos)) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drainOutput(untilEos = false)
                continue
            }
            val buf = codec.getInputBuffer(idx) ?: continue
            buf.clear()
            val chunk = minOf(buf.remaining(), bytes.size - written)
            if (chunk > 0) {
                buf.put(bytes, written, chunk)
                written += chunk
            }
            val flags = if (isEnd && written >= bytes.size) {
                sentEos = true
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            } else 0
            val ptsUs = sampleIndexToPtsUs(presentationSampleIndex)
            codec.queueInputBuffer(idx, 0, chunk, ptsUs, flags)
            presentationSampleIndex += (chunk / 2)
            drainOutput(untilEos = false)
            if (flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                drainOutput(untilEos = true)
                break
            }
        }
    }

    private fun sampleIndexToPtsUs(samples: Long): Long {
        return samples * 1_000_000L / sampleRate.toLong()
    }

    private fun drainOutput(untilEos: Boolean) {
        // Hot-path non-EOS drains use timeout 0 (non-blocking poll). Any
        // output that isn't ready right now will be picked up by the
        // next feed's drain.
        //
        // EOS path uses 10 ms per-dequeue timeout AND a wall-clock
        // deadline so we never spin forever. Observed in-call (LiveKit
        // already publishing the local mic) the `c2.android.aac.encoder`
        // sometimes never emits BUFFER_FLAG_END_OF_STREAM after the
        // empty `queueInputBuffer(... EOS)`, and an unbounded loop here
        // locked the cleanup coroutine, which made
        // `VoiceRecorderView.stopRecording()` hang on `awaitResult()`
        // and the UI stuck in "release to send". Bounded wait lets
        // `codec.stop()` / `muxer.stop()` in the `finally` block
        // finalise whatever frames we have — worst case the file is
        // missing the last ~40 ms of trailing audio, which is much
        // better than the file never being delivered.
        val timeoutUs: Long = if (untilEos) 10_000L else 0L
        val deadlineMs: Long = if (untilEos) System.currentTimeMillis() + EOS_DRAIN_TIMEOUT_MS else Long.MAX_VALUE
        while (true) {
            if (untilEos && System.currentTimeMillis() > deadlineMs) {
                L.w {
                    "[DualCandidateVoiceRecorder] encoder[$name] EOS drain timed out " +
                        "after ${EOS_DRAIN_TIMEOUT_MS} ms — finalising muxer with whatever frames we have"
                }
                return
            }
            val idx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return else continue
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)
                    if (out != null && bufferInfo.size > 0 && muxerStarted) {
                        out.position(bufferInfo.offset)
                        out.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, out, bufferInfo)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!untilEos) return
            }
        }
    }

    private companion object {
        /**
         * Wall-clock cap on how long we wait for the encoder to emit
         * its EOS marker after the empty `BUFFER_FLAG_END_OF_STREAM`
         * input. 1.5 s comfortably covers normal-load Pixel-class
         * device; the in-call hang we observed was indefinite, so the
         * exact value matters less than just *having* a bound.
         */
        const val EOS_DRAIN_TIMEOUT_MS = 1_500L
    }
}
