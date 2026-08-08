package com.difft.android.video.videoconverter.muxer

import org.mp4parser.boxes.iso14496.part12.SampleDescriptionBox
import org.mp4parser.boxes.iso14496.part15.HevcConfigurationBox
import org.mp4parser.boxes.iso14496.part15.HevcDecoderConfigurationRecord
import org.mp4parser.boxes.sampleentry.VisualSampleEntry
import org.mp4parser.muxer.tracks.CleanInputStream
import org.mp4parser.muxer.tracks.h265.H265NalUnitHeader
import org.mp4parser.muxer.tracks.h265.H265NalUnitTypes
import org.mp4parser.muxer.tracks.h265.SequenceParameterSetRbsp
import org.mp4parser.streaming.extensions.DimensionTrackExtension
import org.mp4parser.streaming.extensions.SampleFlagsSampleExtension
import org.mp4parser.streaming.input.AbstractStreamingTrack
import org.mp4parser.streaming.input.StreamingSampleImpl
import org.mp4parser.tools.ByteBufferByteChannel
import org.mp4parser.tools.IsoTypeReader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Arrays

internal abstract class HevcTrack @Throws(IOException::class) constructor(csd: List<ByteBuffer>) : AbstractStreamingTrack(), H265NalUnitTypes {

    private val bufferedNals = ArrayList<ByteBuffer>()
    private var vclNalUnitSeenInAU = false
    private var isIdr = true
    private var currentPresentationTimeUs: Long = 0
    private val stsd: SampleDescriptionBox

    init {
        val sps = ArrayList<ByteBuffer>()
        val pps = ArrayList<ByteBuffer>()
        val vps = ArrayList<ByteBuffer>()
        var spsStruct: SequenceParameterSetRbsp? = null
        for (nal in csd) {
            val unitHeader = getNalUnitHeader(nal)
            nal.position(0)
            // collect sps/vps/pps
            when (unitHeader.nalUnitType) {
                H265NalUnitTypes.NAL_TYPE_PPS_NUT -> pps.add(nal.duplicate())
                H265NalUnitTypes.NAL_TYPE_VPS_NUT -> vps.add(nal.duplicate())
                H265NalUnitTypes.NAL_TYPE_SPS_NUT -> {
                    sps.add(nal.duplicate())
                    nal.position(2)
                    spsStruct = SequenceParameterSetRbsp(CleanInputStream(Channels.newInputStream(ByteBufferByteChannel(nal.slice()))))
                }
                H265NalUnitTypes.NAL_TYPE_PREFIX_SEI_NUT -> {
                    //new SEIMessage(new BitReaderBuffer(nal.slice()));
                }
            }
        }

        stsd = SampleDescriptionBox()
        stsd.addBox(createSampleEntry(sps, pps, vps, spsStruct))

    }

    override fun getTimescale(): Long {
        return 90000
    }

    override fun getHandler(): String {
        return "vide"
    }

    override fun getLanguage(): String {
        return "\u0060\u0060\u0060" // 0 in Iso639
    }

    override fun getSampleDescriptionBox(): SampleDescriptionBox {
        return stsd
    }

    override fun close() {
    }

    @Throws(IOException::class)
    internal fun consumeLastNal() {
        wrapUp(bufferedNals, currentPresentationTimeUs)
    }

    @Throws(IOException::class)
    internal fun consumeNal(nal: ByteBuffer, presentationTimeUs: Long) {

        val unitHeader = getNalUnitHeader(nal)
        val isVcl = isVcl(unitHeader)
        //
        if (vclNalUnitSeenInAU) { // we need at least 1 VCL per AU
            // This branch checks if we encountered the start of a samples/AU
            if (isVcl) {
                if ((nal.get(2).toInt() and -128) != 0) { // this is: first_slice_segment_in_pic_flag  u(1)
                    wrapUp(bufferedNals, presentationTimeUs)
                }
            } else {
                when (unitHeader.nalUnitType) {
                    H265NalUnitTypes.NAL_TYPE_PREFIX_SEI_NUT,
                    H265NalUnitTypes.NAL_TYPE_AUD_NUT,
                    H265NalUnitTypes.NAL_TYPE_PPS_NUT,
                    H265NalUnitTypes.NAL_TYPE_VPS_NUT,
                    H265NalUnitTypes.NAL_TYPE_SPS_NUT,
                    H265NalUnitTypes.NAL_TYPE_RSV_NVCL41,
                    H265NalUnitTypes.NAL_TYPE_RSV_NVCL42,
                    H265NalUnitTypes.NAL_TYPE_RSV_NVCL43,
                    H265NalUnitTypes.NAL_TYPE_RSV_NVCL44,
                    H265NalUnitTypes.NAL_TYPE_UNSPEC48,
                    H265NalUnitTypes.NAL_TYPE_UNSPEC49,
                    H265NalUnitTypes.NAL_TYPE_UNSPEC50,
                    H265NalUnitTypes.NAL_TYPE_UNSPEC51,
                    H265NalUnitTypes.NAL_TYPE_UNSPEC52,
                    H265NalUnitTypes.NAL_TYPE_UNSPEC53,
                    H265NalUnitTypes.NAL_TYPE_UNSPEC54,
                    H265NalUnitTypes.NAL_TYPE_UNSPEC55,

                    H265NalUnitTypes.NAL_TYPE_EOB_NUT, // a bit special but also causes a sample to be formed
                    H265NalUnitTypes.NAL_TYPE_EOS_NUT ->
                        wrapUp(bufferedNals, presentationTimeUs)
                }
            }
        }


        when (unitHeader.nalUnitType) {
            H265NalUnitTypes.NAL_TYPE_SPS_NUT,
            H265NalUnitTypes.NAL_TYPE_VPS_NUT,
            H265NalUnitTypes.NAL_TYPE_PPS_NUT,
            H265NalUnitTypes.NAL_TYPE_EOB_NUT,
            H265NalUnitTypes.NAL_TYPE_EOS_NUT,
            H265NalUnitTypes.NAL_TYPE_AUD_NUT,
            H265NalUnitTypes.NAL_TYPE_FD_NUT -> {
                // ignore these
            }
            else ->
                bufferedNals.add(nal)
        }

        if (isVcl) {
            isIdr = unitHeader.nalUnitType == H265NalUnitTypes.NAL_TYPE_IDR_W_RADL || unitHeader.nalUnitType == H265NalUnitTypes.NAL_TYPE_IDR_N_LP
            vclNalUnitSeenInAU = true
        }
    }

    @Throws(IOException::class)
    private fun wrapUp(nals: MutableList<ByteBuffer>, presentationTimeUs: Long) {

        val duration = presentationTimeUs - currentPresentationTimeUs
        currentPresentationTimeUs = presentationTimeUs

        val sample = StreamingSampleImpl(
            nals, getTimescale() * Math.max(0L, duration) / 1000000L
        )

        val sampleFlagsSampleExtension = SampleFlagsSampleExtension()
        sampleFlagsSampleExtension.setSampleIsNonSyncSample(!isIdr)

        sample.addSampleExtension(sampleFlagsSampleExtension)

        sampleSink.acceptSample(sample, this)

        vclNalUnitSeenInAU = false
        isIdr = true
        nals.clear()
    }

    private fun createSampleEntry(
        sps: ArrayList<ByteBuffer>,
        pps: ArrayList<ByteBuffer>,
        vps: ArrayList<ByteBuffer>,
        spsStruct: SequenceParameterSetRbsp?
    ): VisualSampleEntry {
        val visualSampleEntry = VisualSampleEntry("hvc1")
        visualSampleEntry.setDataReferenceIndex(1)
        visualSampleEntry.setDepth(24)
        visualSampleEntry.setFrameCount(1)
        visualSampleEntry.setHorizresolution(72.0)
        visualSampleEntry.setVertresolution(72.0)
        visualSampleEntry.setCompressorname("HEVC Coding")

        val hevcConfigurationBox = HevcConfigurationBox()
        hevcConfigurationBox.getHevcDecoderConfigurationRecord().setConfigurationVersion(1)

        if (spsStruct != null) {
            visualSampleEntry.setWidth(spsStruct.pic_width_in_luma_samples)
            visualSampleEntry.setHeight(spsStruct.pic_height_in_luma_samples)
            val dte = this.getTrackExtension(DimensionTrackExtension::class.java)
            if (dte == null) {
                this.addTrackExtension(DimensionTrackExtension(spsStruct.pic_width_in_luma_samples, spsStruct.pic_height_in_luma_samples))
            }
            val hevcDecoderConfigurationRecord = hevcConfigurationBox.getHevcDecoderConfigurationRecord()
            hevcDecoderConfigurationRecord.setChromaFormat(spsStruct.chroma_format_idc)
            hevcDecoderConfigurationRecord.setGeneral_profile_idc(spsStruct.general_profile_idc)
            hevcDecoderConfigurationRecord.setGeneral_profile_compatibility_flags(spsStruct.general_profile_compatibility_flags)
            hevcDecoderConfigurationRecord.setGeneral_constraint_indicator_flags(spsStruct.general_constraint_indicator_flags)
            hevcDecoderConfigurationRecord.setGeneral_level_idc(spsStruct.general_level_idc.toInt())
            hevcDecoderConfigurationRecord.setGeneral_tier_flag(spsStruct.general_tier_flag)
            hevcDecoderConfigurationRecord.setGeneral_profile_space(spsStruct.general_profile_space)
            hevcDecoderConfigurationRecord.setBitDepthChromaMinus8(spsStruct.bit_depth_chroma_minus8)
            hevcDecoderConfigurationRecord.setBitDepthLumaMinus8(spsStruct.bit_depth_luma_minus8)
            hevcDecoderConfigurationRecord.setTemporalIdNested(spsStruct.sps_temporal_id_nesting_flag)
        }

        hevcConfigurationBox.getHevcDecoderConfigurationRecord().setLengthSizeMinusOne(3)

        val vpsArray = HevcDecoderConfigurationRecord.Array()
        vpsArray.array_completeness = false
        vpsArray.nal_unit_type = H265NalUnitTypes.NAL_TYPE_VPS_NUT
        vpsArray.nalUnits = ArrayList<ByteArray>()
        for (vp in vps) {
            vpsArray.nalUnits.add(Utils.toArray(vp))
        }

        val spsArray = HevcDecoderConfigurationRecord.Array()
        spsArray.array_completeness = false
        spsArray.nal_unit_type = H265NalUnitTypes.NAL_TYPE_SPS_NUT
        spsArray.nalUnits = ArrayList<ByteArray>()
        for (sp in sps) {
            spsArray.nalUnits.add(Utils.toArray(sp))
        }

        val ppsArray = HevcDecoderConfigurationRecord.Array()
        ppsArray.array_completeness = false
        ppsArray.nal_unit_type = H265NalUnitTypes.NAL_TYPE_PPS_NUT
        ppsArray.nalUnits = ArrayList<ByteArray>()
        for (pp in pps) {
            ppsArray.nalUnits.add(Utils.toArray(pp))
        }

        hevcConfigurationBox.getArrays().addAll(Arrays.asList(spsArray, vpsArray, ppsArray))

        visualSampleEntry.addBox(hevcConfigurationBox)
        return visualSampleEntry
    }

    private fun isVcl(nalUnitHeader: H265NalUnitHeader): Boolean {
        return nalUnitHeader.nalUnitType >= 0 && nalUnitHeader.nalUnitType <= 31
    }

    companion object {
        private fun getNalUnitHeader(nal: ByteBuffer): H265NalUnitHeader {
            nal.position(0)
            val nalUnitHeaderValue = IsoTypeReader.readUInt16(nal)
            val nalUnitHeader = H265NalUnitHeader()
            nalUnitHeader.forbiddenZeroFlag = (nalUnitHeaderValue and 0x8000) shr 15
            nalUnitHeader.nalUnitType = (nalUnitHeaderValue and 0x7E00) shr 9
            nalUnitHeader.nuhLayerId = (nalUnitHeaderValue and 0x1F8) shr 3
            nalUnitHeader.nuhTemporalIdPlusOne = (nalUnitHeaderValue and 0x7)
            return nalUnitHeader
        }
    }
}
