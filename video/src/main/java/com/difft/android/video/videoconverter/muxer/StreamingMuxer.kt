package com.difft.android.video.videoconverter.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import com.difft.android.base.log.lumberjack.L
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.DecoderSpecificInfo
import org.mp4parser.streaming.StreamingTrack
import com.difft.android.video.interfaces.Muxer
import com.difft.android.video.videoconverter.utils.MediaCodecCompat
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

class StreamingMuxer(private val outputStream: OutputStream) : Muxer {

    private val tracks: MutableList<MediaCodecTrack> = ArrayList()
    private var mp4Writer: Mp4Writer? = null

    @Throws(IOException::class)
    override fun start() {
        val source: MutableList<StreamingTrack> = ArrayList()
        for (track in tracks) {
            source.add(track as StreamingTrack)
        }
        mp4Writer = Mp4Writer(source, Channels.newChannel(outputStream))
    }

    @Throws(IOException::class)
    override fun stop() {
        if (mp4Writer == null) {
            throw IllegalStateException("calling stop prior to start")
        }
        for (track in tracks) {
            track.finish()
        }
        mp4Writer!!.close()
        mp4Writer = null
    }

    @Throws(IOException::class)
    override fun addTrack(format: MediaFormat): Int {

        val mime = format.getString(MediaFormat.KEY_MIME)
        when (mime) {
            "video/avc" ->
                tracks.add(MediaCodecAvcTrack(format))
            "audio/mp4a-latm" ->
                tracks.add(MediaCodecAacTrack.create(format))
            "video/hevc" ->
                tracks.add(MediaCodecHevcTrack(format))
            else ->
                throw IllegalArgumentException("unknown track format")
        }
        return tracks.size - 1
    }

    @Throws(IOException::class)
    override fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        tracks.get(trackIndex).writeSampleData(byteBuf, bufferInfo)
    }

    override fun release() {
    }

    override fun supportsAudioRemux(): Boolean {
        return true
    }

    private interface MediaCodecTrack {
        @Throws(IOException::class)
        fun writeSampleData(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo)

        @Throws(IOException::class)
        fun finish()
    }

    private class MediaCodecAvcTrack(format: MediaFormat) : AvcTrack(
        Utils.subBuffer(format.getByteBuffer("csd-0")!!, 4),
        Utils.subBuffer(format.getByteBuffer("csd-1")!!, 4)
    ), MediaCodecTrack {

        @Throws(IOException::class)
        override fun writeSampleData(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
            val nals = H264Utils.getNals(byteBuf)
            for (nal in nals) {
                consumeNal(Utils.clone(nal), bufferInfo.presentationTimeUs)
            }
        }

        @Throws(IOException::class)
        override fun finish() {
            consumeLastNal()
        }
    }

    private class MediaCodecHevcTrack @Throws(IOException::class) constructor(format: MediaFormat) : HevcTrack(
        H264Utils.getNals(format.getByteBuffer("csd-0")!!)
    ), MediaCodecTrack {

        @Throws(IOException::class)
        override fun writeSampleData(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
            val nals = H264Utils.getNals(byteBuf)
            for (nal in nals) {
                consumeNal(Utils.clone(nal), bufferInfo.presentationTimeUs)
            }
        }

        @Throws(IOException::class)
        override fun finish() {
            consumeLastNal()
        }
    }

    private class MediaCodecAacTrack private constructor(
        avgBitrate: Long,
        maxBitrate: Long,
        sampleRate: Int,
        channelCount: Int,
        aacProfile: Int,
        decoderSpecificInfo: DecoderSpecificInfo?
    ) : AacTrack(avgBitrate, maxBitrate, sampleRate, channelCount, aacProfile, decoderSpecificInfo), MediaCodecTrack {

        @Throws(IOException::class)
        override fun writeSampleData(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
            val buffer = ByteArray(bufferInfo.size)
            byteBuf.position(bufferInfo.offset)
            byteBuf.get(buffer, 0, bufferInfo.size)
            processSample(ByteBuffer.wrap(buffer))
        }

        override fun finish() {
        }

        companion object {
            private const val TAG = "StreamingMuxer"

            fun create(format: MediaFormat): MediaCodecAacTrack {
                val bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                val maxBitrate: Int
                if (format.containsKey(MediaCodecCompat.MEDIA_FORMAT_KEY_MAX_BIT_RATE)) {
                    maxBitrate = format.getInteger(MediaCodecCompat.MEDIA_FORMAT_KEY_MAX_BIT_RATE)
                } else {
                    maxBitrate = bitrate
                }

                val filledDecoderSpecificInfo: DecoderSpecificInfo?
                if (format.containsKey(MediaCodecCompat.MEDIA_FORMAT_KEY_MAX_BIT_RATE)) {
                    val csd = format.getByteBuffer(MediaCodecCompat.MEDIA_FORMAT_KEY_CODEC_SPECIFIC_DATA_0)

                    val decoderSpecificInfo = DecoderSpecificInfo()
                    var parseSuccess = false
                    try {
                        decoderSpecificInfo.parseDetail(csd)
                        parseSuccess = true
                    } catch (e: IOException) {
                        L.w(e) { TAG + " Could not parse AAC codec-specific data!" }
                    }
                    if (parseSuccess) {
                        filledDecoderSpecificInfo = decoderSpecificInfo
                    } else {
                        filledDecoderSpecificInfo = null
                    }
                } else {
                    filledDecoderSpecificInfo = null
                }

                return MediaCodecAacTrack(
                    bitrate.toLong(), maxBitrate.toLong(),
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    format.getInteger(MediaFormat.KEY_AAC_PROFILE), filledDecoderSpecificInfo
                )
            }
        }
    }
}
