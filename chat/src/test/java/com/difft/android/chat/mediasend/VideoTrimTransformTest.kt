package com.difft.android.chat.mediasend

import android.media.MediaMetadataRetriever
import com.difft.android.chat.mediasend.v2.videos.VideoTrimData
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.video.VideoRemuxer
import com.difft.android.video.VideoSource
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T40 — [VideoTrimTransform] fast-remux branch.
 *
 * The branch decision now comes from the MediaStore size / duration, so no `MediaMetadataRetriever`
 * may be constructed to reach it. That is both the scoped-storage fix (the bare path may be
 * unreadable) and one main-thread Binder round trip removed.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class VideoTrimTransformTest {

    @Before
    fun setUp() {
        mockkObject(VideoRemuxer)
        mockkConstructor(MediaMetadataRetriever::class)
    }

    @After
    fun tearDown() {
        unmockkObject(VideoRemuxer)
        unmockkConstructor(MediaMetadataRetriever::class)
    }

    /**
     * A 1.5 MB / 8 s video is 1.5 Mbps, under the LEVEL_1 target of 1.378 Mbps * 1.2, so neither
     * trimming nor compression is required and the fast remux path is taken.
     */
    @Test
    fun `untrimmed video below the bitrate threshold takes fast remux without opening a retriever`() {
        val remuxSource = slot<VideoSource>()
        every { VideoRemuxer.remux(capture(remuxSource), any()) } returns true
        val media = LocalMediaBuilder.gallery(
            id = 999L,
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/999",
            realPath = "/storage/emulated/0/Movies/v.mp4",
            size = 1_500_000L,
            durationMs = 8_000L,
        )
        val transform = VideoTrimTransform(VideoTrimData(isDurationEdited = false), SentMediaQuality.STANDARD)

        val result = transform.transform(RuntimeEnvironment.getApplication(), media)

        verify(exactly = 1) { VideoRemuxer.remux(any(), any()) }
        // The remuxed output replaces realPath; path still points at the original source.
        assertTrue(result.realPath, result.realPath.endsWith(".mp4"))
        assertNotEquals("/storage/emulated/0/Movies/v.mp4", result.realPath)
        assertEquals("content://media/external/video/media/999", result.path)
        // The source is dispatched through VideoSource, so the content URI is what gets read.
        assertEquals("content", remuxSource.captured.scheme)
        // No retriever anywhere on the branch decision: size and duration came from MediaStore.
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) }
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) }
    }
}
