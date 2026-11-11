package com.difft.android.chat.mediasend

import android.content.Context
import android.net.Uri
import com.difft.android.base.media.AnimatedImageDetector
import com.difft.android.base.media.ImageCompressor
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.util.Util
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.LogCapture
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ImageEditorRenderTransformTest : ImageTransformTestBase() {
    @Test
    fun standardHeicUpdatesMetadataAndKeepsGallerySource() {
        val (media, original) = gallery("image/heic", "photo.heic", png)
        val output = givenCompressionSucceeds()

        assertSame(media, transform(media))

        assertEquals(output.file.path, media.realPath)
        assertEquals("image/jpeg", media.mimeType)
        assertEquals("photo.jpg", media.fileName)
        assertEquals(output.size, media.size)
        assertEquals(1600, media.width)
        assertEquals(1200, media.height)
        assertTrue(original.exists())
        assertArrayEquals(png, original.readBytes())
        assertEquals(Uri.parse(media.path), loaded.captured)
        assertTrue(harness.opens.contains(loaded.captured))
        verify { ImageCompressor.compress(any(), any(), "image/heic", intArrayOf(1600, 1024, 768, 512), 1024 * 1024, 70, draftDir) }
    }

    @Test
    fun transparentPngOutputKeepsPngLabel() {
        val (media, _) = gallery("image/png", "photo.png", png)
        val output = givenCompressionSucceeds("image/png", "png")

        transform(media)

        assertEquals("image/png", media.mimeType)
        assertEquals("photo.png", media.fileName)
        assertEquals(output.file.path, media.realPath)
        assertEquals(output.size, media.size)
        assertEquals(output.width, media.width)
        assertEquals(output.height, media.height)
    }

    @Test
    fun opaquePngOutputUsesJpegLabelAndDraftInputIsDeleted() {
        val (media, original) = draft("image/png", "pasted.png", png)
        val output = givenCompressionSucceeds()

        transform(media)

        assertEquals("image/jpeg", media.mimeType)
        assertEquals("pasted.jpg", media.fileName)
        assertEquals(output.size, media.size)
        assertFalse(original.exists())
        assertTrue(output.file.exists())
    }

    @Test
    fun blankFileNameGetsUuidAndMatchingExtension() {
        val (media, _) = gallery()
        media.fileName = ""
        givenCompressionSucceeds("image/png", "png")

        transform(media)

        assertTrue(media.fileName, media.fileName.matches(Regex("[0-9a-f-]{36}\\.png")))
    }

    @Test
    fun lowMemoryConstraintsReachTheCompressor() {
        every { Util.isLowMemory(any()) } returns true
        val (media, _) = gallery()
        givenCompressionSucceeds()

        transform(media)

        verify { ImageCompressor.compress(any(), any(), any(), intArrayOf(768, 512), 1024 * 1024, 70, draftDir) }
    }

    @Test
    fun animatedImageSkipsCompressionAndKeepsDraftBytes() {
        val (media, original) = draft("image/webp", "animated.webp", png)
        every { AnimatedImageDetector.isAnimated(any<Context>(), any(), any()) } returns true

        transform(media)

        assertEquals(original.path, media.realPath)
        assertArrayEquals(png, original.readBytes())
        verify(exactly = 0) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun compressorFailureKeepsSourceAndMetadata() {
        val (media, original) = draft("image/png", "pasted.png", png)

        transform(media)

        assertEquals(original.path, media.realPath)
        assertEquals("image/png", media.mimeType)
        assertEquals("pasted.png", media.fileName)
        assertEquals(png.size.toLong(), media.size)
        assertEquals(4000, media.width)
        assertArrayEquals(png, original.readBytes())
    }

    @Test
    fun similarlyNamedDraftSiblingIsNotDeleted() {
        val file = File(root, "draft_blobs_other/source.jpg").apply { parentFile!!.mkdirs(); writeBytes(jpeg) }
        val media = LocalMediaBuilder.sandbox(file.path)
        givenCompressionSucceeds()

        transform(media)

        assertTrue(file.exists())
    }

    @Test
    fun unreadableSourceFailsTheItemForBothQualities() {
        val (media, _) = gallery()
        harness.registerUnreadable(Uri.parse(media.path), "/denied/source.jpg")

        SentMediaQuality.entries.forEach { quality ->
            assertThrows(FileNotFoundException::class.java) { transform(media, quality = quality) }
        }
        verify(exactly = 0) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun deniedSourceFailsTheItemForBothQualities() {
        val (media, _) = gallery()
        shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.parse(media.path)) { throw SecurityException("denied") }

        SentMediaQuality.entries.forEach { quality ->
            assertThrows(SecurityException::class.java) { transform(media, quality = quality) }
        }
    }

    @Test
    fun failedRenderPropagatesAndRecyclesBitmap() {
        val (media, original) = gallery()
        val (model, bitmap) = renderedModel()
        every { bitmap.compress(any(), any(), any()) } throws IllegalStateException("render failed")

        assertThrows(IllegalStateException::class.java) {
            ImageEditorModelRenderMediaTransform(model, null, SentMediaQuality.HIGH).transform(context, media)
        }

        assertEquals(original.path, media.realPath)
        verify(exactly = 1) { bitmap.recycle() }
    }

    @Test
    fun standardCompressesRenderedOutputAndDeletesOnlyTheIntermediate() {
        val (media, original) = gallery("image/heic", "photo.heic")
        val output = givenCompressionSucceeds()
        val (model, bitmap) = renderedModel()

        ImageEditorModelRenderMediaTransform(
            model, null, SentMediaQuality.STANDARD, preserveSourceForRetry = true
        ).transform(context, media)

        assertEquals("file", loaded.captured.scheme)
        assertNotEquals(Uri.parse(media.path), loaded.captured)
        assertFalse(File(loaded.captured.path!!).exists())
        assertTrue(original.exists())
        assertEquals(output.file.path, media.realPath)
        assertEquals("photo.jpg", media.fileName)
        verify { ImageCompressor.compress(any(), any(), "image/jpeg", any(), any(), any(), any()) }
        verify(exactly = 1) { bitmap.recycle() }
    }

    @Test
    fun compressionFailureAfterRenderingKeepsEdits() {
        val (media, original) = gallery()
        val (model, bitmap) = renderedModel()

        ImageEditorModelRenderMediaTransform(model, null, SentMediaQuality.STANDARD).transform(context, media)

        assertNotEquals(original.path, media.realPath)
        assertArrayEquals(jpeg, File(media.realPath).readBytes())
        assertEquals(640, media.width)
        assertEquals(480, media.height)
        assertEquals("image/jpeg", media.mimeType)
        assertEquals(Uri.fromFile(File(media.realPath)), loaded.captured)
        verify(exactly = 1) { bitmap.recycle() }
    }

    @Test
    fun renderedInputBecomingUnreadableKeepsTheRender() {
        val (media, original) = gallery()
        val (model, _) = renderedModel()
        val deniedContext = io.mockk.mockk<Context>()
        val resolver = io.mockk.mockk<android.content.ContentResolver>()
        every { deniedContext.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } throws FileNotFoundException("denied")
        
        every { deniedContext.applicationContext } returns context

        ImageEditorModelRenderMediaTransform(model, null, SentMediaQuality.STANDARD).transform(deniedContext, media)

        assertNotEquals(original.path, media.realPath)
        assertTrue(File(media.realPath).exists())
        verify(exactly = 0) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun resultDiagnosticContainsOnlySafeFields() {
        val (media, _) = gallery("image/heic", "private-photo.heic")
        givenCompressionSucceeds()
        val log = LogCapture()
        log.recording {
            transform(media)
            val line = awaitLine("image result") { it.contains("[MediaSend] compress result=") }
            assertTrue(line, line.contains("result=compressed reason=standard"))
            assertTrue(line, line.contains("out=1600x1200 tier=0 fmt=jpeg meta=gps_stripped"))
            assertFalse(line, line.contains("private-photo"))
            assertFalse(line, line.contains("content://"))
            assertFalse(line, line.contains(root.path))
        }
    }

    private fun transform(
        media: com.difft.android.selector.entity.LocalMedia,
        quality: SentMediaQuality = SentMediaQuality.STANDARD
    ) = ImageEditorModelRenderMediaTransform(null, null, quality).transform(context, media)
}
