package com.difft.android.chat.mediasend

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.difft.android.base.media.AnimatedImageDetector
import com.difft.android.base.media.ImageCompressor
import com.difft.android.base.media.JpegGpsStripper
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.util.Util
import com.difft.android.imageeditor.core.model.EditorModel
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.ContentSourceHarness
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.OutputStream
import java.util.UUID

abstract class ImageTransformTestBase {
    protected lateinit var context: Context
    protected lateinit var root: File
    protected lateinit var draftDir: File
    protected lateinit var harness: ContentSourceHarness
    protected val loaded = slot<Uri>()
    protected val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 4, 5)
    protected val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3, 4)

    @Before
    fun setUpImages() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.filesDir, "image-transform-${UUID.randomUUID()}").apply { mkdirs() }
        draftDir = File(root, "draft_blobs").apply { mkdirs() }
        harness = ContentSourceHarness(context)
        mockkObject(FileUtil)
        every { FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY) } returns draftDir.path + File.separator
        mockkStatic(Util::class)
        every { Util.isLowMemory(any()) } returns false
        mockkObject(AnimatedImageDetector, ImageCompressor, JpegGpsStripper)
        every { AnimatedImageDetector.isAnimated(any<Context>(), any(), any()) } returns false
        every { ImageCompressor.compress(any(), capture(loaded), any(), any(), any(), any(), any()) } returns
            ImageCompressor.Result.Failed(ImageCompressor.FailureReason.DECODE)
        every { JpegGpsStripper.strip(any()) } returns JpegGpsStripper.StripResult.Stripped
    }

    @After
    fun tearDownImages() {
        try {
            FileUtil.invalidateFileValidityUnder(root.path)
            root.deleteRecursively()
        } finally {
            unmockkAll()
        }
    }

    protected fun gallery(mime: String = "image/jpeg", name: String = "photo.jpg", bytes: ByteArray = jpeg): Pair<LocalMedia, File> {
        val file = File(root, "gallery/${UUID.randomUUID()}/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val media = LocalMediaBuilder.gallery(
            contentUri = "content://media/external/images/media/${UUID.randomUUID()}",
            realPath = file.path, mime = mime, size = bytes.size.toLong()
        ).apply { width = 4000; height = 3000; fileName = name }
        harness.registerReadable(Uri.parse(media.path), bytes)
        return media to file
    }

    protected fun draft(mime: String = "image/jpeg", name: String = "camera.jpg", bytes: ByteArray = jpeg): Pair<LocalMedia, File> {
        val file = File(draftDir, name).apply { writeBytes(bytes) }
        return LocalMediaBuilder.sandbox(file.path, mime).apply {
            size = bytes.size.toLong(); width = 4000; height = 3000
        } to file
    }

    protected fun givenCompressionSucceeds(mime: String = "image/jpeg", extension: String = "jpg"): ImageCompressor.Result.Compressed {
        val file = File(draftDir, "${UUID.randomUUID()}.$extension").apply { writeBytes(if (mime == "image/png") png else jpeg) }
        val result = ImageCompressor.Result.Compressed(file, mime, 1600, 1200, file.length(), 0)
        every { ImageCompressor.compress(any(), capture(loaded), any(), any(), any(), any(), any()) } returns result
        return result
    }

    protected fun renderedModel(): Pair<EditorModel, Bitmap> {
        val bitmap = mockk<Bitmap>()
        every { bitmap.width } returns 640
        every { bitmap.height } returns 480
        every { bitmap.compress(any(), any(), any()) } answers {
            thirdArg<OutputStream>().write(jpeg)
            true
        }
        every { bitmap.recycle() } returns Unit
        val model = mockk<EditorModel>()
        every { model.render(any(), any(), any()) } returns bitmap
        return model to bitmap
    }
}
