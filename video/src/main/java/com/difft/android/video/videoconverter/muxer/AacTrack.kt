package com.difft.android.video.videoconverter.muxer

import android.util.SparseIntArray
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.AudioSpecificConfig
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.DecoderConfigDescriptor
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.DecoderSpecificInfo
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.ESDescriptor
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.SLConfigDescriptor
import org.mp4parser.boxes.iso14496.part12.SampleDescriptionBox
import org.mp4parser.boxes.iso14496.part14.ESDescriptorBox
import org.mp4parser.boxes.sampleentry.AudioSampleEntry
import org.mp4parser.streaming.extensions.DefaultSampleFlagsTrackExtension
import org.mp4parser.streaming.input.AbstractStreamingTrack
import org.mp4parser.streaming.input.StreamingSampleImpl
import java.io.IOException
import java.nio.ByteBuffer

internal abstract class AacTrack(
    avgBitrate: Long,
    maxBitrate: Long,
    private val sampleRate: Int,
    channelCount: Int,
    aacProfile: Int,
    decoderSpecificInfo: DecoderSpecificInfo?
) : AbstractStreamingTrack() {

    private val stsd: SampleDescriptionBox

    init {
        val defaultSampleFlagsTrackExtension = DefaultSampleFlagsTrackExtension()
        defaultSampleFlagsTrackExtension.setIsLeading(2)
        defaultSampleFlagsTrackExtension.setSampleDependsOn(2)
        defaultSampleFlagsTrackExtension.setSampleIsDependedOn(2)
        defaultSampleFlagsTrackExtension.setSampleHasRedundancy(2)
        defaultSampleFlagsTrackExtension.setSampleIsNonSyncSample(false)
        this.addTrackExtension(defaultSampleFlagsTrackExtension)

        stsd = SampleDescriptionBox()
        val audioSampleEntry = AudioSampleEntry("mp4a")
        if (channelCount == 7) {
            audioSampleEntry.setChannelCount(8)
        } else {
            audioSampleEntry.setChannelCount(channelCount)
        }
        audioSampleEntry.setSampleRate(sampleRate.toLong())
        audioSampleEntry.setDataReferenceIndex(1)
        audioSampleEntry.setSampleSize(16)


        val esds = ESDescriptorBox()
        val descriptor = ESDescriptor()
        descriptor.setEsId(0)

        val slConfigDescriptor = SLConfigDescriptor()
        slConfigDescriptor.setPredefined(2)
        descriptor.setSlConfigDescriptor(slConfigDescriptor)

        val decoderConfigDescriptor = DecoderConfigDescriptor()
        decoderConfigDescriptor.setObjectTypeIndication(0x40 /*Audio ISO/IEC 14496-3*/)
        decoderConfigDescriptor.setStreamType(5 /*audio stream*/)
        decoderConfigDescriptor.setBufferSizeDB(1536)
        decoderConfigDescriptor.setMaxBitRate(maxBitrate)
        decoderConfigDescriptor.setAvgBitRate(avgBitrate)

        val audioSpecificConfig = AudioSpecificConfig()
        audioSpecificConfig.setOriginalAudioObjectType(aacProfile)
        audioSpecificConfig.setSamplingFrequencyIndex(SAMPLING_FREQUENCY_INDEX_MAP.get(sampleRate))
        audioSpecificConfig.setChannelConfiguration(channelCount)
        decoderConfigDescriptor.setAudioSpecificInfo(audioSpecificConfig)

        if (decoderSpecificInfo != null) {
            decoderConfigDescriptor.setDecoderSpecificInfo(decoderSpecificInfo)
        }

        descriptor.setDecoderConfigDescriptor(decoderConfigDescriptor)

        esds.setEsDescriptor(descriptor)

        audioSampleEntry.addBox(esds)
        stsd.addBox(audioSampleEntry)
    }

    override fun getTimescale(): Long {
        return sampleRate.toLong()
    }

    override fun getHandler(): String {
        return "soun"
    }

    override fun getLanguage(): String {
        return "\u0060\u0060\u0060" // 0 in Iso639
    }

    @Synchronized
    override fun getSampleDescriptionBox(): SampleDescriptionBox {
        return stsd
    }

    override fun close() {
    }

    @Throws(IOException::class)
    internal fun processSample(frame: ByteBuffer) {
        sampleSink.acceptSample(StreamingSampleImpl(frame, 1024L), this)
    }

    companion object {
        private val SAMPLING_FREQUENCY_INDEX_MAP = SparseIntArray().apply {
            put(96000, 0)
            put(88200, 1)
            put(64000, 2)
            put(48000, 3)
            put(44100, 4)
            put(32000, 5)
            put(24000, 6)
            put(22050, 7)
            put(16000, 8)
            put(12000, 9)
            put(11025, 10)
            put(8000, 11)
        }
    }
}
