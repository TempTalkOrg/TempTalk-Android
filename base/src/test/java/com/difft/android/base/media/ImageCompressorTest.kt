package com.difft.android.base.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.Glide
import com.difft.android.base.glide.GlideCacheKeyManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ImageCompressorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var outputDir: File
    private val worker = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        outputDir = temporaryFolder.newFolder("output")
        Glide.tearDown()
        mockkObject(GlideCacheKeyManager)
        every { GlideCacheKeyManager.getKeyOrNull(any()) } returns null
    }

    @After
    fun tearDown() {
        try {
            worker.shutdownNow()
            assertTrue("Compression worker must stop before mocks are removed", worker.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            Glide.tearDown()
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun largeJpegIsReencodedWithinStandardDimensionsAndDropsExif() {
        val source = writeImage(temporaryFolder.newFile("large.jpg"), 4000, 3000)
        ExifInterface(source).apply {
            setLatLong(12.5, 34.5)
            setAttribute(ExifInterface.TAG_USER_COMMENT, "fixture comment")
            setAttribute(ExifInterface.TAG_MAKE, "fixture camera")
            saveAttributes()
        }

        val result = compressed(compress(Uri.fromFile(source), "image/jpeg"))

        assertEquals(1600, result.width)
        assertEquals(1200, result.height)
        assertEquals(0, result.tier)
        assertOutput(result, "image/jpeg", "jpg")
        assertTrue(result.size <= ImageCompressionPreset.STANDARD.maxBytes)
        ExifInterface(result.file).apply {
            assertNull(latLong)
            assertNull(getAttribute(ExifInterface.TAG_USER_COMMENT))
            assertNull(getAttribute(ExifInterface.TAG_MAKE))
        }
    }

    @Test
    fun exifRotationIsAppliedBeforeReportingOutputDimensions() {
        val source = writeImage(temporaryFolder.newFile("rotated.jpg"), 4000, 3000)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val result = compressed(compress(Uri.fromFile(source), "image/jpeg"))

        assertEquals(1200, result.width)
        assertEquals(1600, result.height)
        assertOutput(result, "image/jpeg", "jpg")
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface(result.file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
    }

    @Test
    fun transparentPngKeepsActualAlpha() {
        val source = writeImage(temporaryFolder.newFile("transparent.png"), format = Bitmap.CompressFormat.PNG) {
            it.eraseColor(Color.RED)
            it.setPixel(5, 6, Color.TRANSPARENT)
        }

        val result = compressed(compress(Uri.fromFile(source), "image/png"))

        assertOutput(result, "image/png", "png")
        val decoded = BitmapFactory.decodeFile(result.file.path)!!
        try {
            assertEquals(0, Color.alpha(decoded.getPixel(5, 6)))
            assertEquals(Color.RED, decoded.getPixel(0, 0))
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun opaqueRgbaPngIsEncodedAsJpeg() {
        val source = writeImage(temporaryFolder.newFile("opaque.png"), format = Bitmap.CompressFormat.PNG)
        val decoded = BitmapFactory.decodeFile(source.path)!!
        try {
            assertTrue("Fixture must exercise an opaque bitmap with an alpha channel", decoded.hasAlpha())
        } finally {
            decoded.recycle()
        }

        assertOutput(compressed(compress(Uri.fromFile(source), "image/png")), "image/jpeg", "jpg")
    }

    @Test
    fun pngFormatDecisionScansPixelsWhenAlphaChannelIsPresent() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            bitmap.setHasAlpha(true)
            assertTrue(bitmap.hasAlpha())
            assertEquals(ImageCompressor.OutputFormat.JPEG, ImageCompressor.outputFormat("image/png", bitmap))
            bitmap.setPixel(0, 7, Color.argb(127, 0, 0, 255))
            assertEquals(ImageCompressor.OutputFormat.PNG, ImageCompressor.outputFormat("image/png", bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun webpRemainsWebp() {
        val source = writeImage(temporaryFolder.newFile("source.webp"), format = Bitmap.CompressFormat.WEBP_LOSSY)

        assertOutput(compressed(compress(Uri.fromFile(source), "image/webp")), "image/webp", "webp")
    }

    @Test
    fun contentUriIsReadThroughContentResolver() {
        val source = writeImage(temporaryFolder.newFile("content.jpg"))
        val uri = Uri.parse("content://com.difft.android.base.media.test/image/1")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { source.inputStream() }

        val result = compressed(compress(uri, "image/jpeg"))

        assertOutput(result, "image/jpeg", "jpg")
        assertEquals(64, result.width)
        assertEquals(48, result.height)
    }

    @Test
    fun corruptSourceReturnsDecodeFailureWithoutOutput() {
        val source = temporaryFolder.newFile("broken.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertEquals(ImageCompressor.Result.Failed(ImageCompressor.FailureReason.DECODE), compress(Uri.fromFile(source), "image/jpeg"))
        assertTrue(outputDir.listFiles()!!.isEmpty())
    }

    @Test
    fun byteLimitSelectsLaterTierAndUsesLastTierWhenNothingFits() {
        val source = writeImage(temporaryFolder.newFile("noise.png"), 64, 32, Bitmap.CompressFormat.PNG) { bitmap ->
            val random = Random(1)
            val pixels = IntArray(64 * 32) { (random.nextInt() and 0x00ffffff) or 0x7f000000 }
            bitmap.setPixels(pixels, 0, 64, 0, 0, 64, 32)
        }
        val uri = Uri.fromFile(source)
        val targets = intArrayOf(64, 32, 16)
        val first = compressed(compress(uri, "image/png", targets))
        val limit = (first.size / 2).toInt()

        val fitting = compressed(compress(uri, "image/png", targets, limit))
        assertEquals(1, fitting.tier)
        assertEquals(32, fitting.width)
        assertEquals(16, fitting.height)
        assertTrue(fitting.size <= limit)

        val fallback = compressed(compress(uri, "image/png", targets, 1))
        assertEquals(2, fallback.tier)
        assertEquals(16, fallback.width)
        assertEquals(8, fallback.height)
        assertTrue(fallback.size > 1)
    }

    @Test
    fun mimeMappingHandlesHeicHeifAvifAndUnknownTypes() {
        val source = Uri.fromFile(writeImage(temporaryFolder.newFile("source.jpg")))
        listOf("image/heic", "image/heif", "image/avif", " IMAGE/JPG ").forEach { mime ->
            assertOutput(compressed(compress(source, mime)), "image/jpeg", "jpg")
        }
        listOf(null, "image/other").forEach { mime ->
            assertOutput(compressed(compress(source, mime)), "image/png", "png")
        }
    }

    @Test
    fun invalidTargetsAndUnwritableOutputAreFailures() {
        val source = Uri.fromFile(writeImage(temporaryFolder.newFile("source.jpg")))
        assertEquals(ImageCompressor.Result.Failed(ImageCompressor.FailureReason.INVALID_ARGUMENT), compress(source, "image/jpeg", intArrayOf()))
        assertEquals(ImageCompressor.Result.Failed(ImageCompressor.FailureReason.INVALID_ARGUMENT), compress(source, "image/jpeg", intArrayOf(32, 64)))
        outputDir = temporaryFolder.newFile("not-a-directory")
        assertEquals(ImageCompressor.Result.Failed(ImageCompressor.FailureReason.IO), compress(source, "image/jpeg"))
    }

    private fun compress(
        source: Uri,
        mime: String?,
        targets: IntArray = ImageCompressionPreset.STANDARD.targets,
        maxBytes: Int = ImageCompressionPreset.STANDARD.maxBytes
    ): ImageCompressor.Result {
        val future = worker.submit(Callable {
            ImageCompressor.compress(context, source, mime, targets, maxBytes, ImageCompressionPreset.STANDARD.quality, outputDir)
        })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        
        while (!future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Image compression did not finish", future.isDone)
        return future.get(1, TimeUnit.SECONDS).also { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun compressed(result: ImageCompressor.Result): ImageCompressor.Result.Compressed {
        assertTrue("Expected compressed image, got $result", result is ImageCompressor.Result.Compressed)
        return result as ImageCompressor.Result.Compressed
    }

    private fun assertOutput(result: ImageCompressor.Result.Compressed, mime: String, extension: String) {
        assertEquals(mime, result.mime)
        assertEquals(extension, result.file.extension)
        assertEquals(result.file.length(), result.size)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.file.path, options)
        assertEquals(mime, options.outMimeType)
        assertEquals(result.width, options.outWidth)
        assertEquals(result.height, options.outHeight)
    }
}
