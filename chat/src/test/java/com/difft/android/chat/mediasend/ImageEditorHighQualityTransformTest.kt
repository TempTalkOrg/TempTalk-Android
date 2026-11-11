package com.difft.android.chat.mediasend

import com.difft.android.base.media.ImageCompressor
import com.difft.android.base.media.JpegGpsStripper
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.harness.LogCapture
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ImageEditorHighQualityTransformTest : ImageTransformTestBase() {
    @Test
    fun galleryJpegIsCopiedStrippedAndKeepsItsMimeAndFileName() {
        val (media, original) = gallery("image/jpg", "camera.jpeg")
        val stripped = jpeg + byteArrayOf(9, 10)
        val target = slot<File>()
        every { JpegGpsStripper.strip(capture(target)) } answers {
            firstArg<File>().writeBytes(stripped)
            JpegGpsStripper.StripResult.Stripped
        }

        transform(media)

        assertNotEquals(original.path, media.realPath)
        assertEquals(target.captured.path, media.realPath)
        assertEquals("jpeg", target.captured.extension)
        assertTrue(target.captured.canonicalPath.startsWith(draftDir.canonicalPath + File.separator))
        assertEquals(stripped.size.toLong(), media.size)
        assertEquals("image/jpg", media.mimeType)
        assertEquals("camera.jpeg", media.fileName)
        assertArrayEquals(jpeg, original.readBytes())
        assertArrayEquals(stripped, File(media.realPath).readBytes())
        verify(exactly = 0) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun failedStripDeletesGalleryCopyAndSendsOriginal() {
        val (media, original) = gallery()
        val target = slot<File>()
        every { JpegGpsStripper.strip(capture(target)) } answers {
            firstArg<File>().writeBytes(byteArrayOf())
            JpegGpsStripper.StripResult.Failed(JpegGpsStripper.FailureReason.IO)
        }
        val log = LogCapture()
        log.recording {
            transform(media)
            awaitLine("metadata fallback") { it.contains("compress result=failed reason=io") && it.contains("meta=failed") }
        }

        assertEquals(original.path, media.realPath)
        assertArrayEquals(jpeg, original.readBytes())
        assertEquals(jpeg.size.toLong(), media.size)
        assertFalse(target.captured.exists())
        assertTrue(draftDir.listFiles()!!.isEmpty())
    }

    @Test
    fun privateDraftIsStrippedInPlaceAndBackupIsRemoved() {
        val (media, original) = draft()
        val stripped = jpeg + byteArrayOf(6)
        every { JpegGpsStripper.strip(any()) } answers {
            firstArg<File>().writeBytes(stripped)
            JpegGpsStripper.StripResult.Stripped
        }

        transform(media)

        verify(exactly = 1) { JpegGpsStripper.strip(original.canonicalFile) }
        assertEquals(original.canonicalPath, media.realPath)
        assertArrayEquals(stripped, original.readBytes())
        assertEquals(stripped.size.toLong(), media.size)
        assertEquals(listOf(original), draftDir.listFiles()!!.toList())
    }

    @Test
    fun failedInPlaceStripRestoresOriginalBytesAndRemovesBackup() {
        val (media, original) = draft()
        every { JpegGpsStripper.strip(any()) } answers {
            firstArg<File>().writeBytes(byteArrayOf(1))
            JpegGpsStripper.StripResult.Failed(JpegGpsStripper.FailureReason.VERIFY)
        }

        transform(media)

        assertEquals(original.canonicalPath, media.realPath)
        assertArrayEquals(jpeg, original.readBytes())
        assertEquals(jpeg.size.toLong(), media.size)
        assertEquals(listOf(original), draftDir.listFiles()!!.toList())
    }

    @Test
    fun copyFailureKeepsOriginalWithoutCallingStripper() {
        val (media, original) = gallery()
        val notDirectory = File(root, "blocked").apply { writeBytes(byteArrayOf(1)) }
        every { FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY) } returns notDirectory.path + File.separator

        transform(media)

        assertEquals(original.path, media.realPath)
        assertArrayEquals(jpeg, original.readBytes())
        verify(exactly = 0) { JpegGpsStripper.strip(any()) }
    }

    @Test
    fun nonJpegHeadersPassThroughEvenWithJpegMime() {
        listOf("image/png", "image/heic", "image/jpeg").forEach { mime ->
            val (media, original) = gallery(mime, "image.png", png)

            transform(media)

            assertEquals(original.path, media.realPath)
            assertEquals(mime, media.mimeType)
            assertEquals("image.png", media.fileName)
            assertArrayEquals(png, original.readBytes())
        }
        verify(exactly = 0) { JpegGpsStripper.strip(any()) }
        verify(exactly = 0) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun highEditedImageKeepsRenderWithoutCompressionOrStrip() {
        val (media, original) = gallery()
        val (model, bitmap) = renderedModel()

        ImageEditorModelRenderMediaTransform(model, null, SentMediaQuality.HIGH).transform(context, media)

        assertNotEquals(original.path, media.realPath)
        assertArrayEquals(jpeg, File(media.realPath).readBytes())
        assertEquals("image/jpeg", media.mimeType)
        assertEquals(640, media.width)
        assertEquals(480, media.height)
        verify(exactly = 1) { bitmap.recycle() }
        verify(exactly = 0) { JpegGpsStripper.strip(any()) }
        verify(exactly = 0) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun transform(media: LocalMedia) =
        ImageEditorModelRenderMediaTransform(null, null, SentMediaQuality.HIGH).transform(context, media)
}
