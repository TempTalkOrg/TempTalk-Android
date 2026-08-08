/* *
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
 * https://github.com/sannies/mp4parser/blob/4ed724754cde751c3f27fdda51f288df4f4c5db5/streaming/src/main/java/org/mp4parser/streaming/output/mp4/StandardMp4Writer.java
 *
 * This file has been modified by Signal.
 */
package com.difft.android.video.videoconverter.muxer

import com.difft.android.base.log.lumberjack.L
import org.mp4parser.Box
import org.mp4parser.boxes.iso14496.part12.ChunkOffsetBox
import org.mp4parser.boxes.iso14496.part12.CompositionTimeToSample
import org.mp4parser.boxes.iso14496.part12.FileTypeBox
import org.mp4parser.boxes.iso14496.part12.MediaHeaderBox
import org.mp4parser.boxes.iso14496.part12.MovieBox
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox
import org.mp4parser.boxes.iso14496.part12.SampleSizeBox
import org.mp4parser.boxes.iso14496.part12.SampleTableBox
import org.mp4parser.boxes.iso14496.part12.SampleToChunkBox
import org.mp4parser.boxes.iso14496.part12.SyncSampleBox
import org.mp4parser.boxes.iso14496.part12.TimeToSampleBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import org.mp4parser.boxes.iso14496.part12.TrackHeaderBox
import org.mp4parser.streaming.StreamingSample
import org.mp4parser.streaming.StreamingTrack
import org.mp4parser.streaming.extensions.CompositionTimeSampleExtension
import org.mp4parser.streaming.extensions.CompositionTimeTrackExtension
import org.mp4parser.streaming.extensions.SampleFlagsSampleExtension
import org.mp4parser.streaming.extensions.TrackIdTrackExtension
import org.mp4parser.streaming.output.SampleSink
import org.mp4parser.streaming.output.mp4.DefaultBoxes
import org.mp4parser.tools.CastUtils
import org.mp4parser.tools.Mp4Arrays
import org.mp4parser.tools.Mp4Math
import org.mp4parser.tools.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates an MP4 file with ftyp, mdat+, moov order.
 * A very special property of this variant is that it written sequentially. You can start transferring the
 * data while the `sink` receives it. (in contrast to typical implementations which need random
 * access to write length fields at the beginning of the file)
 */
internal class Mp4Writer @Throws(IOException::class) constructor(
    source: List<StreamingTrack>,
    private val sink: WritableByteChannel
) : DefaultBoxes(), SampleSink {

    private val source: MutableList<StreamingTrack> = ArrayList(source)
    private val creationTime = Date()


    /**
     * Contains the start time of the next segment in line that will be created.
     */
    private val nextChunkCreateStartTime: MutableMap<StreamingTrack, Long> = ConcurrentHashMap()

    /**
     * Contains the start time of the next segment in line that will be written.
     */
    private val nextChunkWriteStartTime: MutableMap<StreamingTrack, Long> = ConcurrentHashMap()

    /**
     * Contains the next sample's start time.
     */
    private val nextSampleStartTime: MutableMap<StreamingTrack, Long> = HashMap()

    /**
     * Buffers the samples per track until there are enough samples to form a Segment.
     */
    private val sampleBuffers: MutableMap<StreamingTrack, MutableList<StreamingSample>> = HashMap()
    private val trackBoxes: MutableMap<StreamingTrack, TrackBox> = HashMap()

    /**
     * Buffers segments until it's time for a segment to be written.
     */
    private val chunkBuffers: MutableMap<StreamingTrack, Queue<ChunkContainer>> = ConcurrentHashMap()
    private val chunkNumbers: MutableMap<StreamingTrack, Long> = HashMap()
    private val sampleNumbers: MutableMap<StreamingTrack, Long> = HashMap()
    private var bytesWritten: Long = 0

    init {
        val trackIds = HashSet<Long>()
        for (streamingTrack in source) {
            streamingTrack.setSampleSink(this)
            chunkNumbers[streamingTrack] = 1L
            sampleNumbers[streamingTrack] = 1L
            nextSampleStartTime[streamingTrack] = 0L
            nextChunkCreateStartTime[streamingTrack] = 0L
            nextChunkWriteStartTime[streamingTrack] = 0L
            sampleBuffers[streamingTrack] = ArrayList()
            chunkBuffers[streamingTrack] = LinkedList<ChunkContainer>()
            if (streamingTrack.getTrackExtension(TrackIdTrackExtension::class.java) != null) {
                val trackIdTrackExtension = streamingTrack.getTrackExtension(TrackIdTrackExtension::class.java)
                if (trackIds.contains(trackIdTrackExtension.getTrackId())) {
                    throw MuxingException("There may not be two tracks with the same trackID within one file")
                }
                trackIds.add(trackIdTrackExtension.getTrackId())
            }
        }
        for (streamingTrack in source) {
            if (streamingTrack.getTrackExtension(TrackIdTrackExtension::class.java) == null) {
                var maxTrackId = 0L
                for (trackId in trackIds) {
                    maxTrackId = Math.max(trackId, maxTrackId)
                }
                val tiExt = TrackIdTrackExtension(maxTrackId + 1)
                trackIds.add(tiExt.getTrackId())
                streamingTrack.addTrackExtension(tiExt)
            }
        }

        val minorBrands: MutableList<String> = LinkedList()
        minorBrands.add("isom")
        minorBrands.add("mp42")
        write(sink, FileTypeBox("mp42", 0L, minorBrands))
    }

    @Throws(IOException::class)
    override fun close() {
        for (streamingTrack in source) {
            writeChunkContainer(createChunkContainer(streamingTrack))
            streamingTrack.close()
        }
        write(sink, createMoov())
    }

    private fun createMoov(): Box {
        val movieBox = MovieBox()

        val mvhd = createMvhd()
        movieBox.addBox(mvhd)

        // update durations
        for (streamingTrack in source) {
            val tb = trackBoxes[streamingTrack]
            val mdhd = Path.getPath<MediaHeaderBox>(tb, "mdia[0]/mdhd[0]")
            mdhd.setCreationTime(creationTime)
            mdhd.setModificationTime(creationTime)
            val mediaHeaderDuration = nextSampleStartTime[streamingTrack]!!
            if (mediaHeaderDuration >= UInt32_MAX) {
                mdhd.setVersion(1)
            }
            mdhd.setDuration(mediaHeaderDuration)
            mdhd.setTimescale(streamingTrack.getTimescale())
            mdhd.setLanguage(streamingTrack.getLanguage())
            movieBox.addBox(tb)

            val tkhd = Path.getPath<TrackHeaderBox>(tb, "tkhd[0]")
            val duration = mediaHeaderDuration.toDouble() / streamingTrack.getTimescale()
            tkhd.setCreationTime(creationTime)
            tkhd.setModificationTime(creationTime)
            val trackHeaderDuration = (mvhd.getTimescale() * duration).toLong()
            if (trackHeaderDuration >= UInt32_MAX) {
                tkhd.setVersion(1)
            }
            tkhd.setDuration(trackHeaderDuration)
        }

        // metadata here
        return movieBox
    }

    private fun sortTracks() {
        Collections.sort(source) { o1, o2 ->
            // compare times and account for timestamps!
            val a = nextChunkWriteStartTime[o1]!! * o2.getTimescale()
            val b = nextChunkWriteStartTime[o2]!! * o1.getTimescale()
            Math.signum((a - b).toDouble()).toInt()
        }
    }

    override fun createMvhd(): MovieHeaderBox {
        val mvhd = MovieHeaderBox()
        mvhd.setVersion(1)
        mvhd.setCreationTime(creationTime)
        mvhd.setModificationTime(creationTime)


        var timescales = LongArray(0)
        var maxTrackId = 0L
        var duration = 0.0
        for (streamingTrack in source) {
            duration = Math.max(nextSampleStartTime[streamingTrack]!!.toDouble() / streamingTrack.getTimescale(), duration)
            timescales = Mp4Arrays.copyOfAndAppend(timescales, streamingTrack.getTimescale())
            maxTrackId = Math.max(streamingTrack.getTrackExtension(TrackIdTrackExtension::class.java).getTrackId(), maxTrackId)
        }


        mvhd.setTimescale(Mp4Math.lcm(timescales))
        mvhd.setDuration((Mp4Math.lcm(timescales) * duration).toLong())
        // find the next available trackId
        mvhd.setNextTrackId(maxTrackId + 1)
        return mvhd
    }

    @Throws(IOException::class)
    private fun write(out: WritableByteChannel, vararg boxes: Box) {
        for (box1 in boxes) {
            box1.getBox(out)
            bytesWritten += box1.getSize()
        }
    }

    /**
     * Tests if the currently received samples for a given track
     * are already a 'chunk' as we want to have it. The next
     * sample will not be part of the chunk
     * will be added to the fragment buffer later.
     *
     * @param streamingTrack track to test
     * @param next           the lastest samples
     * @return true if a chunk is to b e created.
     */
    private fun isChunkReady(streamingTrack: StreamingTrack, next: StreamingSample): Boolean {
        val ts = nextSampleStartTime[streamingTrack]!!
        val cfst = nextChunkCreateStartTime[streamingTrack]!!

        return ts >= cfst + 2 * streamingTrack.getTimescale()
        // chunk interleave of 2 seconds
    }

    @Throws(IOException::class)
    private fun writeChunkContainer(chunkContainer: ChunkContainer) {
        val tb = trackBoxes[chunkContainer.streamingTrack]
        val stco = Path.getPath<ChunkOffsetBox>(tb, "mdia[0]/minf[0]/stbl[0]/stco[0]")!!
        stco.setChunkOffsets(Mp4Arrays.copyOfAndAppend(stco.getChunkOffsets(), bytesWritten + 8))
        write(sink, chunkContainer.mdat)
    }

    @Throws(IOException::class)
    override fun acceptSample(
        streamingSample: StreamingSample,
        streamingTrack: StreamingTrack
    ) {

        var tb = trackBoxes[streamingTrack]
        if (tb == null) {
            tb = TrackBox()
            tb.addBox(createTkhd(streamingTrack))
            tb.addBox(createMdia(streamingTrack))
            trackBoxes[streamingTrack] = tb
        }

        if (isChunkReady(streamingTrack, streamingSample)) {

            val chunkContainer = createChunkContainer(streamingTrack)
            //System.err.println("Creating fragment for " + streamingTrack);
            sampleBuffers[streamingTrack]!!.clear()
            nextChunkCreateStartTime[streamingTrack] = nextChunkCreateStartTime[streamingTrack]!! + chunkContainer.duration
            val chunkQueue = chunkBuffers[streamingTrack]!!
            chunkQueue.add(chunkContainer)
            if (source[0] === streamingTrack) {

                // This will write AT LEAST the currently created fragment and possibly a few more
                while (true) {
                    val currentStreamingTrack = this.source[0]
                    val tracksFragmentQueue = chunkBuffers[currentStreamingTrack]!!
                    if (tracksFragmentQueue.isEmpty()) break
                    val currentFragmentContainer = tracksFragmentQueue.remove()
                    writeChunkContainer(currentFragmentContainer)
                    val logTrack = currentStreamingTrack
                    L.d { TAG + "write chunk " + logTrack.getHandler() + ". duration " + currentFragmentContainer.duration.toDouble() / logTrack.getTimescale() }
                    val ts = nextChunkWriteStartTime[currentStreamingTrack]!! + currentFragmentContainer.duration
                    nextChunkWriteStartTime[currentStreamingTrack] = ts
                    L.d { TAG + logTrack.getHandler() + " track advanced to " + ts.toDouble() / logTrack.getTimescale() }
                    sortTracks()
                }
            } else {
                L.d { TAG + streamingTrack.getHandler() + " track delayed, queue size is " + chunkQueue.size }
            }
        }

        sampleBuffers[streamingTrack]!!.add(streamingSample)
        nextSampleStartTime[streamingTrack] = nextSampleStartTime[streamingTrack]!! + streamingSample.getDuration()

    }

    private fun createChunkContainer(streamingTrack: StreamingTrack): ChunkContainer {

        val samples = sampleBuffers[streamingTrack]!!
        val chunkNumber = chunkNumbers[streamingTrack]!!
        chunkNumbers[streamingTrack] = chunkNumber + 1
        val cc = ChunkContainer()
        cc.streamingTrack = streamingTrack
        cc.mdat = Mdat(samples)
        cc.duration = nextSampleStartTime[streamingTrack]!! - nextChunkCreateStartTime[streamingTrack]!!
        val tb = trackBoxes[streamingTrack]
        val stbl = Path.getPath<SampleTableBox>(tb, "mdia[0]/minf[0]/stbl[0]")!!
        val stsc = Path.getPath<SampleToChunkBox>(stbl, "stsc[0]")!!
        if (stsc.getEntries().isEmpty()) {
            val entries = ArrayList<SampleToChunkBox.Entry>()
            stsc.setEntries(entries)
            entries.add(SampleToChunkBox.Entry(chunkNumber, samples.size.toLong(), 1))
        } else {
            val e = stsc.getEntries().get(stsc.getEntries().size - 1)
            if (e.getSamplesPerChunk() != samples.size.toLong()) {
                stsc.getEntries().add(SampleToChunkBox.Entry(chunkNumber, samples.size.toLong(), 1))
            }
        }
        var sampleNumber = sampleNumbers[streamingTrack]!!

        val stsz = Path.getPath<SampleSizeBox>(stbl, "stsz[0]")!!
        val stts = Path.getPath<TimeToSampleBox>(stbl, "stts[0]")!!
        var stss: SyncSampleBox? = Path.getPath<SyncSampleBox>(stbl, "stss[0]")
        var ctts: CompositionTimeToSample? = Path.getPath<CompositionTimeToSample>(stbl, "ctts[0]")
        if (streamingTrack.getTrackExtension(CompositionTimeTrackExtension::class.java) != null) {
            if (ctts == null) {
                ctts = CompositionTimeToSample()
                ctts.setEntries(ArrayList())

                val bs = ArrayList<Box>(stbl.getBoxes())
                bs.add(bs.indexOf(stts), ctts)
            }
        }

        val sampleSizes = LongArray(samples.size)
        var i = 0
        for (sample in samples) {
            sampleSizes[i++] = sample.getContent().limit().toLong()

            if (ctts != null) {
                ctts.getEntries().add(CompositionTimeToSample.Entry(1, CastUtils.l2i(sample.getSampleExtension(CompositionTimeSampleExtension::class.java).getCompositionTimeOffset())))
            }

            if (stts.getEntries().isEmpty()) {
                val entries = ArrayList<TimeToSampleBox.Entry>(stts.getEntries())
                entries.add(TimeToSampleBox.Entry(1, sample.getDuration()))
                stts.setEntries(entries)
            } else {
                val sttsEntry = stts.getEntries().get(stts.getEntries().size - 1)
                if (sttsEntry.getDelta() == sample.getDuration()) {
                    sttsEntry.setCount(sttsEntry.getCount() + 1)
                } else {
                    stts.getEntries().add(TimeToSampleBox.Entry(1, sample.getDuration()))
                }
            }
            val sampleFlagsSampleExtension = sample.getSampleExtension(SampleFlagsSampleExtension::class.java)
            if (sampleFlagsSampleExtension != null && sampleFlagsSampleExtension.isSyncSample()) {
                if (stss == null) {
                    stss = SyncSampleBox()
                    stbl.addBox(stss)
                }
                stss.setSampleNumber(Mp4Arrays.copyOfAndAppend(stss.getSampleNumber(), sampleNumber))
            }
            sampleNumber++

        }
        stsz.setSampleSizes(Mp4Arrays.copyOfAndAppend(stsz.getSampleSizes(), *sampleSizes))

        sampleNumbers[streamingTrack] = sampleNumber
        samples.clear()
        L.d { TAG + "chunk container created for " + streamingTrack.getHandler() + ". mdat size: " + cc.mdat.size + ". chunk duration is " + cc.duration.toDouble() / streamingTrack.getTimescale() }
        return cc
    }

    override fun createMdhd(streamingTrack: StreamingTrack): Box {
        val mdhd = MediaHeaderBox()
        mdhd.setCreationTime(creationTime)
        mdhd.setModificationTime(creationTime)
        //mdhd.setDuration(nextSampleStartTime.get(streamingTrack)); will update at the end, in createMoov
        mdhd.setTimescale(streamingTrack.getTimescale())
        mdhd.setLanguage(streamingTrack.getLanguage())
        return mdhd
    }

    override fun createTkhd(streamingTrack: StreamingTrack): Box {
        val tkhd = super.createTkhd(streamingTrack) as TrackHeaderBox
        tkhd.setEnabled(true)
        tkhd.setInMovie(true)
        return tkhd
    }

    private inner class Mdat(samplesParam: List<StreamingSample>) : Box {
        val samples: ArrayList<StreamingSample> = ArrayList(samplesParam)

        @JvmField
        var size: Long = 8

        init {
            for (sample in samplesParam) {
                size += sample.getContent().limit()
            }
        }

        override fun getType(): String {
            return "mdat"
        }

        override fun getSize(): Long {
            return size
        }

        @Throws(IOException::class)
        override fun getBox(writableByteChannel: WritableByteChannel) {
            writableByteChannel.write(
                ByteBuffer.wrap(
                    byteArrayOf(
                        ((size and 0xff000000L) shr 24).toByte(),
                        ((size and 0xff0000L) shr 16).toByte(),
                        ((size and 0xff00L) shr 8).toByte(),
                        (size and 0xffL).toByte(),
                        109, 100, 97, 116, // mdat

                    )
                )
            )
            for (sample in samples) {
                writableByteChannel.write(sample.getContent().rewind() as ByteBuffer)
            }
        }
    }

    private inner class ChunkContainer {
        lateinit var mdat: Mdat
        lateinit var streamingTrack: StreamingTrack
        var duration: Long = 0
    }

    companion object {
        private const val TAG = "Mp4Writer"
        private val UInt32_MAX: Long = (1L shl 32) - 1
    }
}
