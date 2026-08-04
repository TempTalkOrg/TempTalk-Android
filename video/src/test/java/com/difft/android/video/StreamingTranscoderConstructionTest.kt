package com.difft.android.video

import android.media.MediaMetadataRetriever
import com.difft.android.video.exceptions.VideoSourceException
import com.difft.android.video.interfaces.MediaInput
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T36, T37 — [StreamingTranscoder] construction contract.
 *
 * [VideoSource] is mocked rather than backed by a shadowed codec stack: the two properties under
 * test (an unknown size must throw, a bind failure must keep its cause and still release the
 * retriever) are decided entirely by the constructor, so a fake codec would only add setup that
 * the assertions never touch.
 *
 * Robolectric because `MediaMetadataRetriever` is constructed here; the plain JVM android.jar stub
 * throws from its constructor.
 *
 * Run: :video:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
class StreamingTranscoderConstructionTest {

    private lateinit var mediaInput: MediaInput

    @Before
    fun setUp() {
        mediaInput = mockk(relaxed = true)
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit
    }

    @After
    fun tearDown() {
        unmockkConstructor(MediaMetadataRetriever::class)
    }

    private fun source(sizeBytes: Long): VideoSource = mockk(relaxed = true) {
        every { this@mockk.mediaInput } returns this@StreamingTranscoderConstructionTest.mediaInput
        every { this@mockk.sizeBytes } returns sizeBytes
        every { scheme } returns "content"
    }

    /**
     * T36 — an unknown input size must throw instead of falling through. Trim options supply the
     * duration so the failure is unambiguously the size check.
     *
     * The `createExtractor` verify is the "no transcode work started" assertion: `transcode()`
     * cannot have run if no extractor was ever created.
     */
    @Test
    fun `unknown input size throws instead of silently skipping transcode`() {
        val source = source(sizeBytes = VideoSource.UNKNOWN_SIZE)

        try {
            StreamingTranscoder(source, TranscoderOptions(0L, 8_000_000L), TranscodingPreset.LEVEL_1, 50L * 1024 * 1024, true)
            fail("expected VideoSourceException for an unknown input size")
        } catch (e: VideoSourceException) {
            assertTrue(e.message, e.message!!.contains("input size"))
        }

        verify(exactly = 1) { source.bindTo(any()) }
        verify(exactly = 0) { mediaInput.createExtractor() }
    }

    /**
     * T37 — a bind failure is wrapped but keeps its cause, and the retriever is released even on
     * that path (it holds a descriptor the media provider handed us).
     */
    @Test
    fun `bind failure is wrapped with its cause and releases the retriever`() {
        val boom = RuntimeException("boom")
        val source = source(sizeBytes = 1_500_000L)
        every { source.bindTo(any()) } throws boom

        try {
            StreamingTranscoder(source, null, TranscodingPreset.LEVEL_1, 50L * 1024 * 1024, true)
            fail("expected VideoSourceException for an unreadable datasource")
        } catch (e: VideoSourceException) {
            assertSame(boom, e.cause)
        }

        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }
}
