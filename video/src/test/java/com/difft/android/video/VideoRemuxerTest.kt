package com.difft.android.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.difft.android.video.exceptions.VideoSourceException
import com.difft.android.video.interfaces.MediaInput
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException










@RunWith(RobolectricTestRunner::class)
class VideoRemuxerTest {

    private val outputs = mutableListOf<File>()

    @After
    fun tearDown() {
        unmockkConstructor(MediaMuxer::class)
        outputs.forEach { it.delete() }
        outputs.clear()
    }

    private fun outputPath(): String =
        File.createTempFile("remux", ".mp4").let {
            outputs += it
            
            
            it.delete()
            it.absolutePath
        }

    private fun source(mediaInput: MediaInput): VideoSource = mockk(relaxed = true) {
        every { this@mockk.mediaInput } returns mediaInput
        every { scheme } returns "content"
    }

    
    @Test
    fun `unreadable source throws and leaves no output file`() {
        val ioError = IOException("cannot open")
        val mediaInput = mockk<MediaInput>()
        every { mediaInput.createExtractor() } throws ioError
        val out = outputPath()

        try {
            VideoRemuxer.remux(source(mediaInput), out)
            fail("expected VideoSourceException for an unreadable source")
        } catch (e: VideoSourceException) {
            assertSame(ioError, e.cause)
        }

        assertFalse("a bind failure must not leave a partial output", File(out).exists())
    }

    



    @Test
    fun `post bind failure returns false and deletes the partial output`() {
        val extractor = mockk<MediaExtractor>(relaxed = true)
        every { extractor.trackCount } returns 1
        every { extractor.getTrackFormat(0) } returns MediaFormat.createVideoFormat("video/avc", 640, 480)
        val mediaInput = mockk<MediaInput>(relaxed = true)
        every { mediaInput.createExtractor() } returns extractor
        mockkConstructor(MediaMuxer::class)
        every { anyConstructed<MediaMuxer>().addTrack(any()) } throws IllegalStateException("boom")
        every { anyConstructed<MediaMuxer>().stop() } returns Unit
        every { anyConstructed<MediaMuxer>().release() } returns Unit
        val out = outputPath()

        val result = VideoRemuxer.remux(source(mediaInput), out)

        assertFalse("a readable source must not be reported as a hard failure", result)
        verify(exactly = 1) { mediaInput.createExtractor() }
        assertFalse("the partial output must be cleaned up", File(out).exists())
    }

    
    @Test
    fun `muxer is not constructed before the source is bound`() {
        val mediaInput = mockk<MediaInput>()
        every { mediaInput.createExtractor() } throws IOException("cannot open")
        mockkConstructor(MediaMuxer::class)
        val out = outputPath()

        try {
            VideoRemuxer.remux(source(mediaInput), out)
            fail("expected VideoSourceException for an unreadable source")
        } catch (e: VideoSourceException) {
            assertTrue(e.message, e.message!!.contains("remux"))
        }

        verify(exactly = 0) { anyConstructed<MediaMuxer>().start() }
    }
}
