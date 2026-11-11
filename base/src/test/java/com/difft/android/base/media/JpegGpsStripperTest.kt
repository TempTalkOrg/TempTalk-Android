package com.difft.android.base.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JpegGpsStripperTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun gpsAndCommentsAreRemovedWhileOrientationXmpAndPixelsSurvive() {
        val source = writeImage(temporaryFolder.newFile("gps.jpg")) { bitmap ->
            val random = Random(7)
            val pixels = IntArray(bitmap.width * bitmap.height) { random.nextInt() or (0xff shl 24) }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"
        ExifInterface(source).apply {
            setLatLong(12.5, 34.5)
            setAltitude(10.0)
            setAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE, "1/1,2/1,3/1")
            setAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE, "4/1,5/1,6/1")
            setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "fixture")
            setAttribute(ExifInterface.TAG_USER_COMMENT, "fixture comment")
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "fixture description")
            setAttribute(ExifInterface.TAG_SUBJECT_LOCATION, "1,2")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MAKE, "fixture camera")
            setAttribute(ExifInterface.TAG_XMP, xmp)
            saveAttributes()
        }
        ExifInterface(source).apply {
            assertNotNull(latLong)
            assertNotNull(getAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE))
            assertNotNull(getAttribute(ExifInterface.TAG_SUBJECT_LOCATION))
        }
        val originalPixels = pixels(source)

        assertEquals(JpegGpsStripper.StripResult.Stripped, strip(source))

        ExifInterface(source).apply {
            assertNull(latLong)
            
            ExifInterface::class.java.fields.filter { it.name.startsWith("TAG_GPS_") }.forEach { field ->
                assertNull(field.name, getAttribute(field.get(null) as String))
            }
            assertNull(getAttribute("GPSInfoIFDPointer"))
            assertNull(getAttribute(ExifInterface.TAG_USER_COMMENT))
            assertNull(getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
            assertNull(getAttribute(ExifInterface.TAG_SUBJECT_LOCATION))
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertEquals("fixture camera", getAttribute(ExifInterface.TAG_MAKE))
            assertEquals(xmp, getAttribute(ExifInterface.TAG_XMP))
        }
        assertArrayEquals(originalPixels, pixels(source))
    }

    @Test
    fun pngReturnsNotJpegWithoutChangingBytes() {
        val source = writeImage(temporaryFolder.newFile("source.png"), format = Bitmap.CompressFormat.PNG)
        val original = source.readBytes()

        assertEquals(JpegGpsStripper.StripResult.NotJpeg, strip(source))
        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun truncatedJpegAndMissingFileReturnIoFailure() {
        val truncated = temporaryFolder.newFile("truncated.jpg").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
        }
        val expected = JpegGpsStripper.StripResult.Failed(JpegGpsStripper.FailureReason.IO)

        assertEquals(expected, strip(truncated))
        assertEquals(expected, strip(File(temporaryFolder.root, "missing.jpg")))
    }

    @Test
    fun saveExceptionAndOutOfMemoryBecomeFailures() {
        val source = writeImage(temporaryFolder.newFile("source.jpg"))
        mockkConstructor(ExifInterface::class)
        try {
            every { anyConstructed<ExifInterface>().saveAttributes() } throws IOException("fixture")
            assertEquals(JpegGpsStripper.StripResult.Failed(JpegGpsStripper.FailureReason.IO), strip(source))
            every { anyConstructed<ExifInterface>().saveAttributes() } throws OutOfMemoryError("fixture")
            assertEquals(JpegGpsStripper.StripResult.Failed(JpegGpsStripper.FailureReason.OOM), strip(source))
        } finally {
            unmockkConstructor(ExifInterface::class)
        }
    }

    @Test
    fun remainingGpsAfterSaveIsAVerificationFailure() {
        val source = writeImage(temporaryFolder.newFile("source.jpg"))
        mockkConstructor(ExifInterface::class)
        try {
            every { anyConstructed<ExifInterface>().getAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE) } returns "1/1,2/1,3/1"
            assertEquals(JpegGpsStripper.StripResult.Failed(JpegGpsStripper.FailureReason.VERIFY), strip(source))
        } finally {
            unmockkConstructor(ExifInterface::class)
        }
    }

    private fun pixels(file: File): IntArray {
        val bitmap = BitmapFactory.decodeFile(file.path)!!
        return try {
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun strip(file: File): JpegGpsStripper.StripResult {
        val worker = Executors.newSingleThreadExecutor()
        return try {
            worker.submit(Callable { JpegGpsStripper.strip(file) }).get(5, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
            check(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
