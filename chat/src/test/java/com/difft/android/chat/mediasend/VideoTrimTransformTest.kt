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
        
        assertTrue(result.realPath, result.realPath.endsWith(".mp4"))
        assertNotEquals("/storage/emulated/0/Movies/v.mp4", result.realPath)
        assertEquals("content://media/external/video/media/999", result.path)
        
        assertEquals("content", remuxSource.captured.scheme)
        
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) }
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) }
    }

    private fun mkvBelowThreshold(fileName: String = "clip.mkv") = LocalMediaBuilder.gallery(
        id = 998L,
        mime = "video/x-matroska",
        contentUri = "content://media/external/video/media/998",
        realPath = "/storage/emulated/0/Movies/clip.mkv",
        size = 1_500_000L,
        durationMs = 8_000L,
    ).apply { this.fileName = fileName }

    
    @Test
    fun `T8 successful remux relabels the output as mp4`() {
        every { VideoRemuxer.remux(any(), any()) } returns true

        val result = VideoTrimTransform(VideoTrimData(isDurationEdited = false), SentMediaQuality.STANDARD)
            .transform(RuntimeEnvironment.getApplication(), mkvBelowThreshold())

        assertEquals("video/mp4", result.mimeType)
        assertEquals("clip.mp4", result.fileName)
    }

    
    @Test
    fun `T8 successful remux without a file name names the output after path`() {
        every { VideoRemuxer.remux(any(), any()) } returns true

        val result = VideoTrimTransform(VideoTrimData(isDurationEdited = false), SentMediaQuality.STANDARD)
            .transform(RuntimeEnvironment.getApplication(), mkvBelowThreshold(fileName = ""))

        assertEquals("video/mp4", result.mimeType)
        assertEquals("998.mp4", result.fileName)
    }

    
    @Test
    fun `T9 failed remux keeps the original type and name`() {
        every { VideoRemuxer.remux(any(), any()) } returns false

        val result = VideoTrimTransform(VideoTrimData(isDurationEdited = false), SentMediaQuality.STANDARD)
            .transform(RuntimeEnvironment.getApplication(), mkvBelowThreshold())

        assertEquals("/storage/emulated/0/Movies/clip.mkv", result.realPath)
        assertEquals("video/x-matroska", result.mimeType)
        assertEquals("clip.mkv", result.fileName)
    }

    @Test
    fun `relabel keeps a dotless base name and falls back when nothing names the file`() {
        val noExtension = LocalMediaBuilder.sandbox(realPath = "/data/x/clip", mime = "video/quicktime")
        VideoTrimTransform.relabelAsMp4(noExtension)
        assertEquals("clip.mp4", noExtension.fileName)

        val nameless = LocalMediaBuilder.sandbox(realPath = "/data/x/a.mov", mime = "video/quicktime").apply { fileName = "" }
        VideoTrimTransform.relabelAsMp4(nameless)
        assertEquals("video.mp4", nameless.fileName)
        assertEquals("video/mp4", nameless.mimeType)
    }
}
