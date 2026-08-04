package com.difft.android.video.videoconverter.muxer

import com.difft.android.base.log.lumberjack.L
import org.mp4parser.boxes.iso14496.part12.SampleDescriptionBox
import org.mp4parser.boxes.iso14496.part15.AvcConfigurationBox
import org.mp4parser.boxes.sampleentry.VisualSampleEntry
import org.mp4parser.streaming.SampleExtension
import org.mp4parser.streaming.StreamingSample
import org.mp4parser.streaming.extensions.CompositionTimeSampleExtension
import org.mp4parser.streaming.extensions.CompositionTimeTrackExtension
import org.mp4parser.streaming.extensions.DimensionTrackExtension
import org.mp4parser.streaming.extensions.SampleFlagsSampleExtension
import org.mp4parser.streaming.input.AbstractStreamingTrack
import org.mp4parser.streaming.input.StreamingSampleImpl
import org.mp4parser.streaming.input.h264.H264NalUnitHeader
import org.mp4parser.streaming.input.h264.H264NalUnitTypes
import org.mp4parser.streaming.input.h264.spspps.PictureParameterSet
import org.mp4parser.streaming.input.h264.spspps.SeqParameterSet
import org.mp4parser.streaming.input.h264.spspps.SliceHeader
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections

internal abstract class AvcTrack(spsBuffer: ByteBuffer, ppsBuffer: ByteBuffer) : AbstractStreamingTrack() {

    private var maxDecFrameBuffering = 16
    private val decFrameBuffer: MutableList<StreamingSample> = ArrayList()
    private val decFrameBuffer2: MutableList<StreamingSample> = ArrayList()

    private val spsIdToSpsBytes = LinkedHashMap<Int, ByteBuffer>()
    private val spsIdToSps = LinkedHashMap<Int, SeqParameterSet>()
    private val ppsIdToPpsBytes = LinkedHashMap<Int, ByteBuffer>()
    private val ppsIdToPps = LinkedHashMap<Int, PictureParameterSet>()

    private var timescale = 90000
    private var frametick = 3000

    private val stsd: SampleDescriptionBox

    private val bufferedNals: MutableList<ByteBuffer> = ArrayList()
    private var fvnd: FirstVclNalDetector? = null
    private var sliceNalUnitHeader: H264NalUnitHeader? = null
    private var currentPresentationTimeUs: Long = 0

    init {

        handlePPS(ppsBuffer)

        val sps = handleSPS(spsBuffer)

        var width = (sps.pic_width_in_mbs_minus1 + 1) * 16
        var mult = 2
        if (sps.frame_mbs_only_flag) {
            mult = 1
        }
        var height = 16 * (sps.pic_height_in_map_units_minus1 + 1) * mult
        if (sps.frame_cropping_flag) {
            var chromaArrayType = 0
            if (!sps.residual_color_transform_flag) {
                chromaArrayType = sps.chroma_format_idc.getId()
            }
            var cropUnitX = 1
            var cropUnitY = mult
            if (chromaArrayType != 0) {
                cropUnitX = sps.chroma_format_idc.getSubWidth()
                cropUnitY = sps.chroma_format_idc.getSubHeight() * mult
            }

            width -= cropUnitX * (sps.frame_crop_left_offset + sps.frame_crop_right_offset)
            height -= cropUnitY * (sps.frame_crop_top_offset + sps.frame_crop_bottom_offset)
        }


        val visualSampleEntry = VisualSampleEntry("avc1")
        visualSampleEntry.setDataReferenceIndex(1)
        visualSampleEntry.setDepth(24)
        visualSampleEntry.setFrameCount(1)
        visualSampleEntry.setHorizresolution(72.0)
        visualSampleEntry.setVertresolution(72.0)
        val dte = this.getTrackExtension(DimensionTrackExtension::class.java)
        if (dte == null) {
            this.addTrackExtension(DimensionTrackExtension(width, height))
        }
        visualSampleEntry.setWidth(width)
        visualSampleEntry.setHeight(height)

        visualSampleEntry.setCompressorname("AVC Coding")

        val avcConfigurationBox = AvcConfigurationBox()

        avcConfigurationBox.setSequenceParameterSets(Collections.singletonList(spsBuffer))
        avcConfigurationBox.setPictureParameterSets(Collections.singletonList(ppsBuffer))
        avcConfigurationBox.setAvcLevelIndication(sps.level_idc)
        avcConfigurationBox.setAvcProfileIndication(sps.profile_idc)
        avcConfigurationBox.setBitDepthLumaMinus8(sps.bit_depth_luma_minus8)
        avcConfigurationBox.setBitDepthChromaMinus8(sps.bit_depth_chroma_minus8)
        avcConfigurationBox.setChromaFormat(sps.chroma_format_idc.getId())
        avcConfigurationBox.setConfigurationVersion(1)
        avcConfigurationBox.setLengthSizeMinusOne(3)


        avcConfigurationBox.setProfileCompatibility(
            (if (sps.constraint_set_0_flag) 128 else 0) +
                (if (sps.constraint_set_1_flag) 64 else 0) +
                (if (sps.constraint_set_2_flag) 32 else 0) +
                (if (sps.constraint_set_3_flag) 16 else 0) +
                (if (sps.constraint_set_4_flag) 8 else 0) +
                (sps.reserved_zero_2bits and 0x3L).toInt()
        )

        visualSampleEntry.addBox(avcConfigurationBox)
        stsd = SampleDescriptionBox()
        stsd.addBox(visualSampleEntry)

        var _timescale: Int
        var _frametick: Int
        if (sps.vuiParams != null) {
            _timescale = sps.vuiParams.time_scale shr 1 // Not sure why, but I found this in several places, and it works...
            _frametick = sps.vuiParams.num_units_in_tick
            if (_timescale == 0 || _frametick == 0) {
                val logTimescale = _timescale
                val logFrametick = _frametick
                L.w { TAG + "vuiParams contain invalid values: time_scale: " + logTimescale + " and frame_tick: " + logFrametick + ". Setting frame rate to 30fps" }
                _timescale = 0
                _frametick = 0
            }
            if (_frametick > 0) {
                if (_timescale / _frametick > 100) {
                    val logTimescale = _timescale
                    val logFrametick = _frametick
                    L.w { TAG + "Framerate is " + (logTimescale / logFrametick) + ". That is suspicious." }
                }
            } else {
                val logFrametick = _frametick
                L.w { TAG + "Frametick is " + logFrametick + ". That is suspicious." }
            }
            if (sps.vuiParams.bitstreamRestriction != null) {
                maxDecFrameBuffering = sps.vuiParams.bitstreamRestriction.max_dec_frame_buffering
            }
        } else {
            L.w { TAG + "Can't determine frame rate as SPS does not contain vuiParama" }
            _timescale = 0
            _frametick = 0
        }
        if (_timescale != 0 && _frametick != 0) {
            timescale = _timescale
            frametick = _frametick
        }
        if (sps.pic_order_cnt_type == 0) {
            addTrackExtension(CompositionTimeTrackExtension())
        } else if (sps.pic_order_cnt_type == 1) {
            throw MuxingException("Have not yet imlemented pic_order_cnt_type 1")
        }
    }

    override fun getTimescale(): Long {
        return timescale.toLong()
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
    internal fun consumeNal(nal: ByteBuffer, presentationTimeUs: Long) {

        val nalUnitHeader = getNalUnitHeader(nal)
        when (nalUnitHeader.nal_unit_type) {
            H264NalUnitTypes.CODED_SLICE_NON_IDR,
            H264NalUnitTypes.CODED_SLICE_DATA_PART_A,
            H264NalUnitTypes.CODED_SLICE_DATA_PART_B,
            H264NalUnitTypes.CODED_SLICE_DATA_PART_C,
            H264NalUnitTypes.CODED_SLICE_IDR -> {
                val current = FirstVclNalDetector(nal, nalUnitHeader.nal_ref_idc, nalUnitHeader.nal_unit_type)
                if (fvnd != null && fvnd!!.isFirstInNew(current)) {
                    pushSample(createSample(bufferedNals, fvnd!!.sliceHeader, sliceNalUnitHeader!!, presentationTimeUs - currentPresentationTimeUs), false, false)
                    bufferedNals.clear()
                }
                currentPresentationTimeUs = Math.max(currentPresentationTimeUs, presentationTimeUs)
                sliceNalUnitHeader = nalUnitHeader
                fvnd = current
                bufferedNals.add(nal)
            }

            H264NalUnitTypes.SEI,
            H264NalUnitTypes.AU_UNIT_DELIMITER -> {
                if (fvnd != null) {
                    pushSample(createSample(bufferedNals, fvnd!!.sliceHeader, sliceNalUnitHeader!!, presentationTimeUs - currentPresentationTimeUs), false, false)
                    bufferedNals.clear()
                    fvnd = null
                }
                bufferedNals.add(nal)
            }

            H264NalUnitTypes.SEQ_PARAMETER_SET -> {
                if (fvnd != null) {
                    pushSample(createSample(bufferedNals, fvnd!!.sliceHeader, sliceNalUnitHeader!!, presentationTimeUs - currentPresentationTimeUs), false, false)
                    bufferedNals.clear()
                    fvnd = null
                }
                handleSPS(nal)
            }

            H264NalUnitTypes.PIC_PARAMETER_SET -> {
                if (fvnd != null) {
                    pushSample(createSample(bufferedNals, fvnd!!.sliceHeader, sliceNalUnitHeader!!, presentationTimeUs - currentPresentationTimeUs), false, false)
                    bufferedNals.clear()
                    fvnd = null
                }
                handlePPS(nal)
            }

            H264NalUnitTypes.END_OF_SEQUENCE,
            H264NalUnitTypes.END_OF_STREAM -> return

            H264NalUnitTypes.SEQ_PARAMETER_SET_EXT -> throw IOException("Sequence parameter set extension is not yet handled. Needs TLC.")

            else -> L.w { TAG + "Unknown NAL unit type: " + nalUnitHeader.nal_unit_type }
        }
    }

    @Throws(IOException::class)
    internal fun consumeLastNal() {
        if (fvnd != null) {
            pushSample(createSample(bufferedNals, fvnd!!.sliceHeader, sliceNalUnitHeader!!, 0L), true, true)
        }
    }

    @Throws(IOException::class)
    private fun pushSample(ss: StreamingSample?, all: Boolean, force: Boolean) {
        if (ss != null) {
            decFrameBuffer.add(ss)
        }
        if (all) {
            while (decFrameBuffer.size > 0) {
                pushSample(null, false, true)
            }
        } else {
            if ((decFrameBuffer.size - 1 > maxDecFrameBuffering) || force) {
                val first = decFrameBuffer.removeAt(0)
                val poct0se = first.getSampleExtension(PictureOrderCountType0SampleExtension::class.java)
                if (poct0se == null) {
                    sampleSink.acceptSample(first, this)
                } else {
                    var delay = 0
                    for (streamingSample in decFrameBuffer) {
                        if (poct0se.getPoc() > streamingSample.getSampleExtension(PictureOrderCountType0SampleExtension::class.java).getPoc()) {
                            delay++
                        }
                    }
                    for (streamingSample in decFrameBuffer2) {
                        if (poct0se.getPoc() < streamingSample.getSampleExtension(PictureOrderCountType0SampleExtension::class.java).getPoc()) {
                            delay--
                        }
                    }
                    decFrameBuffer2.add(first)
                    if (decFrameBuffer2.size > maxDecFrameBuffering) {
                        decFrameBuffer2.removeAt(0).removeSampleExtension(PictureOrderCountType0SampleExtension::class.java)
                    }

                    first.addSampleExtension(CompositionTimeSampleExtension.create((delay * frametick).toLong()))
                    sampleSink.acceptSample(first, this)
                }
            }
        }

    }

    private fun createSampleFlagsSampleExtension(nu: H264NalUnitHeader, sliceHeader: SliceHeader): SampleFlagsSampleExtension {
        val sampleFlagsSampleExtension = SampleFlagsSampleExtension()
        if (nu.nal_ref_idc == 0) {
            sampleFlagsSampleExtension.setSampleIsDependedOn(2)
        } else {
            sampleFlagsSampleExtension.setSampleIsDependedOn(1)
        }
        if ((sliceHeader.slice_type == SliceHeader.SliceType.I) || (sliceHeader.slice_type == SliceHeader.SliceType.SI)) {
            sampleFlagsSampleExtension.setSampleDependsOn(2)
        } else {
            sampleFlagsSampleExtension.setSampleDependsOn(1)
        }
        sampleFlagsSampleExtension.setSampleIsNonSyncSample(H264NalUnitTypes.CODED_SLICE_IDR != nu.nal_unit_type)
        return sampleFlagsSampleExtension
    }

    private fun createPictureOrderCountType0SampleExtension(sliceHeader: SliceHeader): PictureOrderCountType0SampleExtension? {
        if (sliceHeader.sps.pic_order_cnt_type == 0) {
            return PictureOrderCountType0SampleExtension(
                sliceHeader,
                if (decFrameBuffer.size > 0)
                    decFrameBuffer.get(decFrameBuffer.size - 1).getSampleExtension(PictureOrderCountType0SampleExtension::class.java)
                else
                    null
            )
/*            decFrameBuffer.add(ssi);
            if (decFrameBuffer.size() - 1 > maxDecFrameBuffering) { // just added one
                drainDecPictureBuffer(false);
            }*/
        } else if (sliceHeader.sps.pic_order_cnt_type == 1) {
            throw MuxingException("pic_order_cnt_type == 1 needs to be implemented")
        } else if (sliceHeader.sps.pic_order_cnt_type == 2) {
            return null // no ctts
        }
        throw MuxingException("I don't know sliceHeader.sps.pic_order_cnt_type of " + sliceHeader.sps.pic_order_cnt_type)
    }


    private fun createSample(nals: List<ByteBuffer>, sliceHeader: SliceHeader, nu: H264NalUnitHeader, sampleDurationNs: Long): StreamingSample {
        val sampleDuration = getTimescale() * Math.max(0L, sampleDurationNs) / 1000000L
        val ss: StreamingSample = StreamingSampleImpl(nals, sampleDuration)
        ss.addSampleExtension(createSampleFlagsSampleExtension(nu, sliceHeader))
        val pictureOrderCountType0SampleExtension: SampleExtension? = createPictureOrderCountType0SampleExtension(sliceHeader)
        if (pictureOrderCountType0SampleExtension != null) {
            ss.addSampleExtension(pictureOrderCountType0SampleExtension)
        }
        return ss
    }

    private fun handlePPS(nal: ByteBuffer) {
        nal.position(1)
        try {
            val _pictureParameterSet = PictureParameterSet.read(nal)
            val oldPpsSameId = ppsIdToPpsBytes.get(_pictureParameterSet.pic_parameter_set_id)
            if (oldPpsSameId != null && !oldPpsSameId.equals(nal)) {
                throw MuxingException("OMG - I got two SPS with same ID but different settings! (AVC3 is the solution)")
            } else {
                ppsIdToPpsBytes.put(_pictureParameterSet.pic_parameter_set_id, nal)
                ppsIdToPps.put(_pictureParameterSet.pic_parameter_set_id, _pictureParameterSet)
            }
        } catch (e: IOException) {
            throw MuxingException("That's surprising to get IOException when working on ByteArrayInputStream", e)
        }


    }

    private fun handleSPS(nal: ByteBuffer): SeqParameterSet {
        nal.position(1)
        try {
            val seqParameterSet = SeqParameterSet.read(nal)
            val oldSpsSameId = spsIdToSpsBytes.get(seqParameterSet.seq_parameter_set_id)
            if (oldSpsSameId != null && !oldSpsSameId.equals(nal)) {
                throw MuxingException("OMG - I got two SPS with same ID but different settings!")
            } else {
                spsIdToSpsBytes.put(seqParameterSet.seq_parameter_set_id, nal)
                spsIdToSps.put(seqParameterSet.seq_parameter_set_id, seqParameterSet)
            }
            return seqParameterSet
        } catch (e: IOException) {
            throw MuxingException("That's surprising to get IOException when working on ByteArrayInputStream", e)
        }

    }

    inner class FirstVclNalDetector(nal: ByteBuffer, nal_ref_idc: Int, nal_unit_type: Int) {

        val sliceHeader: SliceHeader
        val frame_num: Int
        val pic_parameter_set_id: Int
        val field_pic_flag: Boolean
        val bottom_field_flag: Boolean
        val nal_ref_idc: Int
        val pic_order_cnt_type: Int
        val delta_pic_order_cnt_bottom: Int
        val pic_order_cnt_lsb: Int
        val delta_pic_order_cnt_0: Int
        val delta_pic_order_cnt_1: Int
        val idr_pic_id: Int

        init {
            val sh = SliceHeader(nal, spsIdToSps, ppsIdToPps, nal_unit_type == 5)
            this.sliceHeader = sh
            this.frame_num = sh.frame_num
            this.pic_parameter_set_id = sh.pic_parameter_set_id
            this.field_pic_flag = sh.field_pic_flag
            this.bottom_field_flag = sh.bottom_field_flag
            this.nal_ref_idc = nal_ref_idc
            this.pic_order_cnt_type = spsIdToSps.get(ppsIdToPps.get(sh.pic_parameter_set_id)!!.seq_parameter_set_id)!!.pic_order_cnt_type
            this.delta_pic_order_cnt_bottom = sh.delta_pic_order_cnt_bottom
            this.pic_order_cnt_lsb = sh.pic_order_cnt_lsb
            this.delta_pic_order_cnt_0 = sh.delta_pic_order_cnt_0
            this.delta_pic_order_cnt_1 = sh.delta_pic_order_cnt_1
            this.idr_pic_id = sh.idr_pic_id
        }

        fun isFirstInNew(nu: FirstVclNalDetector): Boolean {
            if (nu.frame_num != frame_num) {
                return true
            }
            if (nu.pic_parameter_set_id != pic_parameter_set_id) {
                return true
            }
            if (nu.field_pic_flag != field_pic_flag) {
                return true
            }
            if (nu.field_pic_flag) {
                if (nu.bottom_field_flag != bottom_field_flag) {
                    return true
                }
            }
            if (nu.nal_ref_idc != nal_ref_idc) {
                return true
            }
            if (nu.pic_order_cnt_type == 0 && pic_order_cnt_type == 0) {
                if (nu.pic_order_cnt_lsb != pic_order_cnt_lsb) {
                    return true
                }
                if (nu.delta_pic_order_cnt_bottom != delta_pic_order_cnt_bottom) {
                    return true
                }
            }
            if (nu.pic_order_cnt_type == 1 && pic_order_cnt_type == 1) {
                if (nu.delta_pic_order_cnt_0 != delta_pic_order_cnt_0) {
                    return true
                }
                if (nu.delta_pic_order_cnt_1 != delta_pic_order_cnt_1) {
                    return true
                }
            }
            return false
        }
    }

    class PictureOrderCountType0SampleExtension(
        currentSlice: SliceHeader,
        previous: PictureOrderCountType0SampleExtension?
    ) : SampleExtension {
        var picOrderCntMsb: Int
        var picOrderCountLsb: Int

        init {
            var prevPicOrderCntLsb = 0
            var prevPicOrderCntMsb = 0
            if (previous != null) {
                prevPicOrderCntLsb = previous.picOrderCountLsb
                prevPicOrderCntMsb = previous.picOrderCntMsb
            }

            val maxPicOrderCountLsb = (1 shl (currentSlice.sps.log2_max_pic_order_cnt_lsb_minus4 + 4))
            // System.out.print(" pic_order_cnt_lsb " + pic_order_cnt_lsb + " " + max_pic_order_count);
            picOrderCountLsb = currentSlice.pic_order_cnt_lsb
            picOrderCntMsb = 0
            if ((picOrderCountLsb < prevPicOrderCntLsb) && ((prevPicOrderCntLsb - picOrderCountLsb) >= (maxPicOrderCountLsb / 2))) {
                picOrderCntMsb = prevPicOrderCntMsb + maxPicOrderCountLsb
            } else if ((picOrderCountLsb > prevPicOrderCntLsb) && ((picOrderCountLsb - prevPicOrderCntLsb) > (maxPicOrderCountLsb / 2))) {
                picOrderCntMsb = prevPicOrderCntMsb - maxPicOrderCountLsb
            } else {
                picOrderCntMsb = prevPicOrderCntMsb
            }
        }

        fun getPoc(): Int {
            return picOrderCntMsb + picOrderCountLsb
        }

        override fun toString(): String {
            return "picOrderCntMsb=" + picOrderCntMsb + ", picOrderCountLsb=" + picOrderCountLsb
        }
    }

    companion object {
        private const val TAG = "AvcTrack"

        private fun getNalUnitHeader(nal: ByteBuffer): H264NalUnitHeader {
            val nalUnitHeader = H264NalUnitHeader()
            val type = nal.get(0).toInt()
            nalUnitHeader.nal_ref_idc = (type shr 5) and 3
            nalUnitHeader.nal_unit_type = type and 0x1f
            return nalUnitHeader
        }
    }
}
