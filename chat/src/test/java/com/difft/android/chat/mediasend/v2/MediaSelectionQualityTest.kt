package com.difft.android.chat.mediasend.v2

import com.difft.android.chat.mediasend.v2.review.MediaReviewFragment
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.PushMediaConstraints
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.mms.videoConstraintsQuality
import com.difft.android.chat.util.Util
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.ContentSourceHarness
import com.difft.android.test.harness.LogCapture
import com.difft.android.video.TranscodingPreset
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSelectionQualityTest {
    @Before
    fun setUp() {
        mockkStatic(Util::class)
        every { Util.isLowMemory(any()) } returns false
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun selectionAndReviewDefaultToStandard() {
        assertEquals(SentMediaQuality.STANDARD, MediaSelectionState().quality)
        val field = MediaReviewFragment::class.java.getDeclaredField("sentMediaQuality").apply { isAccessible = true }
        assertEquals(SentMediaQuality.STANDARD, field.get(MediaReviewFragment()))
    }

    @Test
    fun bothImageQualitiesKeepHighVideoConstraints() {
        SentMediaQuality.entries.forEach { quality ->
            assertEquals(SentMediaQuality.HIGH, quality.videoConstraintsQuality())
            assertEquals(TranscodingPreset.LEVEL_3, MediaSelectionState(quality = quality).transcodingPreset)
        }
    }

    @Test
    fun lowMemoryVideoConstraintsRemainUnchanged() {
        every { Util.isLowMemory(any()) } returns true
        SentMediaQuality.entries.forEach { quality ->
            assertEquals(TranscodingPreset.LEVEL_1, MediaSelectionState(quality = quality).transcodingPreset)
        }
    }

    @Test
    fun standardPresetValuesRemainUnchanged() {
        val standard = PushMediaConstraints.MediaConfig.LEVEL_1
        val lowMemory = PushMediaConstraints.MediaConfig.LEVEL_1_LOW_MEMORY
        assertArrayEquals(intArrayOf(1600, 1024, 768, 512), standard.imageSizeTargets)
        assertArrayEquals(intArrayOf(768, 512), lowMemory.imageSizeTargets)
        listOf(standard, lowMemory).forEach {
            assertEquals(70, it.qualitySetting)
            assertEquals(1024 * 1024, it.maxImageFileSize)
        }
    }

    @Test
    fun gifWithoutEditorStateKeepsSourceAndEmitsOneImageResult() {
        val context = RuntimeEnvironment.getApplication()
        val media = LocalMediaBuilder.gallery(mime = "image/gif")
        ContentSourceHarness(context).registerReadable(media.readableUri(), byteArrayOf(1, 2, 3, 4))
        val log = LogCapture()
        log.recording {
            val result = MediaSelectionRepository(context).transformMediaSync(context, listOf(media), emptyMap())
            assertEquals(media.readableUri(), result.updated.values.single().sendUri)
            awaitLine("GIF passthrough") { it.contains("compress result=skipped reason=animated") }
            assertEquals(1, messages.count { it.contains("[MediaSend] compress result=") })
        }
    }

    @Test
    fun unreadableImageWithoutEditorStateEmitsFailedImageResult() {
        val context = RuntimeEnvironment.getApplication()
        val media = LocalMediaBuilder.gallery(mime = "image/gif")
        ContentSourceHarness(context).registerUnreadable(media.readableUri(), "/denied/source.gif")
        val log = LogCapture()
        log.recording {
            val result = MediaSelectionRepository(context).transformMediaSync(context, listOf(media), emptyMap())
            assertEquals(1, result.failures.size)
            assertTrue(result.updated.isEmpty())
            awaitLine("unreadable image") { it.contains("compress result=failed reason=source") }
        }
    }
}
