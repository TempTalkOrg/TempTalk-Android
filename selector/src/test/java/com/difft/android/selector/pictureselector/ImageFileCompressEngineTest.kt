package com.difft.android.selector.pictureselector

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import com.difft.android.base.media.ImageCompressor
import com.difft.android.selector.interfaces.OnKeyValueResultCallbackListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class ImageFileCompressEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var cache: File
    private var cacheAvailable = true
    private val compressionCalls = CopyOnWriteArrayList<CompressionCall>()
    private var outcome: ImageCompressor.Result? = null

    private data class CompressionCall(
        val uri: Uri, val targets: IntArray, val maxBytes: Int, val quality: Int,
        val directory: File, val onMain: Boolean
    )
    private data class Callback(val source: String?, val path: String?, val onMain: Boolean)

    @Before
    fun setUp() {
        resolver = mockk()
        every { resolver.getType(any()) } returns "image/jpeg"
        every { resolver.openAssetFileDescriptor(any(), "r") } returns null
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        cache = File(temporaryFolder.root, "Android/data/com.difft.avatar/cache").apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
            override fun getExternalCacheDir(): File? = if (cacheAvailable) cache else null
        }
        mockkObject(ImageCompressor)
        every { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) } answers {
            compressionCalls += CompressionCall(
                secondArg(), arg(3), arg(4), arg(5), arg(6), Looper.myLooper() == Looper.getMainLooper()
            )
            val result = outcome ?: ImageCompressor.Result.Failed(ImageCompressor.FailureReason.DECODE)
            if (result is ImageCompressor.Result.Compressed) result.file.writeBytes(ByteArray(result.size.toInt()))
            result
        }
    }

    @After
    fun tearDown() {
        
        shadowOf(Looper.getMainLooper()).idle()
        unmockkAll()
    }

    @Test
    fun tinyFilesGifAndNonImagesReturnSourceOnceWithoutCompression() {
        val sources = listOf(file("tiny.jpg", 100 * 1024 - 1), file("empty.png", 0), file("animated.GIF"), file("document.txt"))

        val callbacks = run(*sources.map(Uri::fromFile).toTypedArray())

        assertEquals(sources.map { it.path }, callbacks.map { it.source })
        assertEquals(sources.map { it.path }, callbacks.map { it.path })
        assertTrue(compressionCalls.isEmpty())
    }

    @Test
    fun exactThresholdCompressesWithStandardPresetAndMainThreadCallback() {
        val source = file("crop with spaces.jpg", 100 * 1024)
        val output = File(cache, "avatar_compress/result.jpg")
        outcome = ImageCompressor.Result.Compressed(output, "image/jpeg", 1600, 1200, 500, 0)

        val callback = run(Uri.fromFile(source)).single()

        assertEquals(source.path, callback.source)
        assertNotEquals(Uri.fromFile(source).toString(), callback.source)
        assertEquals(output.path, callback.path)
        assertTrue(output.isFile)
        assertTrue(callback.path!!.contains("Android/data/"))
        assertTrue(callback.path!!.contains("/avatar_compress/"))
        val invocation = compressionCalls.single()
        assertEquals(Uri.fromFile(source), invocation.uri)
        assertArrayEquals(intArrayOf(1600, 1024, 768, 512), invocation.targets)
        assertEquals(70, invocation.quality)
        assertEquals(1024 * 1024, invocation.maxBytes)
        assertEquals(output.parentFile, invocation.directory)
        assertTrue(invocation.directory.isDirectory)
        assertFalse(invocation.onMain)
    }

    @Test
    fun contentKeyKeepsEncodedUriAndUnknownSizeIsCompressed() {
        val source = Uri.parse("content://provider/avatar/folder%20name/photo.jpg?pick=1")

        val callback = run(source).single()

        assertEquals(source.toString(), callback.source)
        assertEquals(source.toString(), callback.path)
        assertNotEquals(source.path, callback.source)
        assertEquals(source, compressionCalls.single().uri)
    }

    @Test
    fun contentGifMimeSkipsEvenWithoutFileExtension() {
        val source = Uri.parse("content://provider/avatar/42")
        every { resolver.getType(source) } returns "image/gif"

        val callback = run(source).single()

        assertEquals(source.toString(), callback.source)
        assertEquals(source.toString(), callback.path)
        assertTrue(compressionCalls.isEmpty())
    }

    @Test
    fun missingMimeAndSizeStillAttemptCompression() {
        val source = Uri.parse("content://provider/avatar/42")
        every { resolver.getType(source) } returns null

        run(source)

        assertEquals(source, compressionCalls.single().uri)
        verify { ImageCompressor.compress(any(), source, null, any(), any(), any(), any()) }
    }

    @Test
    fun assetDescriptorLengthHasPriorityAndIsClosed() {
        val source = Uri.parse("content://provider/avatar/42")
        val descriptor = mockk<AssetFileDescriptor>()
        every { descriptor.length } returns 512
        every { descriptor.close() } returns Unit
        every { resolver.openAssetFileDescriptor(source, "r") } returns descriptor

        assertEquals(source.toString(), run(source).single().path)

        assertTrue(compressionCalls.isEmpty())
        verify(exactly = 1) { descriptor.close() }
        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun unknownDescriptorLengthFallsBackToCursorSizeAndClosesBoth() {
        val source = Uri.parse("content://provider/avatar/42")
        val descriptor = mockk<AssetFileDescriptor>()
        every { descriptor.length } returns -1
        every { descriptor.close() } returns Unit
        every { resolver.openAssetFileDescriptor(source, "r") } returns descriptor
        val cursor = MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf(100 * 1024 - 1)) }
        every { resolver.query(source, any(), any(), any(), any()) } returns cursor

        assertEquals(source.toString(), run(source).single().path)

        assertTrue(compressionCalls.isEmpty())
        assertTrue(cursor.isClosed)
        verify(exactly = 1) { descriptor.close() }
    }

    @Test
    fun metadataProbeFailureLeavesSizeUnknownAndCompresses() {
        val source = Uri.parse("content://provider/avatar/42")
        every { resolver.openAssetFileDescriptor(source, "r") } throws IOException("unavailable")
        every { resolver.query(source, any(), any(), any(), any()) } throws SecurityException("unavailable")

        assertEquals(source.toString(), run(source).single().path)

        assertEquals(source, compressionCalls.single().uri)
    }

    @Test
    fun nullCursorSizeIsUnknownRatherThanTiny() {
        val source = Uri.parse("content://provider/avatar/42")
        val cursor = MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(null)) }
        every { resolver.query(source, any(), any(), any(), any()) } returns cursor

        run(source)

        assertEquals(1, compressionCalls.size)
        assertTrue(cursor.isClosed)
    }

    @Test
    fun failedCompressionReturnsCroppedSourceInsteadOfNull() {
        val source = file("CROP_avatar.jpg")
        outcome = ImageCompressor.Result.Failed(ImageCompressor.FailureReason.OOM)

        val callback = run(Uri.fromFile(source)).single()

        assertEquals(source.path, callback.source)
        assertEquals(source.path, callback.path)
    }

    @Test
    fun oneSourceThrowingDoesNotDropOtherCallbacks() {
        val first = file("first.jpg")
        val second = file("second.jpg")
        every { ImageCompressor.compress(any(), Uri.fromFile(first), any(), any(), any(), any(), any()) } throws IOException("fixture")

        val callbacks = run(Uri.fromFile(first), Uri.fromFile(second))

        assertEquals(listOf(first.path, second.path), callbacks.map { it.source })
        assertEquals(listOf(first.path, second.path), callbacks.map { it.path })
        assertEquals(1, compressionCalls.size)
    }

    @Test
    fun duplicateInputsStillReceiveOneCallbackPerListEntry() {
        val source = Uri.fromFile(file("same.jpg"))

        val callbacks = run(source, source)

        assertEquals(2, compressionCalls.size)
        assertEquals(listOf(source.path, source.path), callbacks.map { it.source })
    }

    @Test
    fun missingExternalCacheReturnsSource() {
        cacheAvailable = false
        val source = file("crop.jpg")

        assertEquals(source.path, run(Uri.fromFile(source)).single().path)
        assertTrue(compressionCalls.isEmpty())
    }

    @Test
    fun emptyInputHasNoCallback() {
        assertTrue(run().isEmpty())
        assertTrue(compressionCalls.isEmpty())
    }

    private fun file(name: String, size: Int = 120 * 1024): File =
        File(temporaryFolder.root, name).apply { writeBytes(ByteArray(size)) }

    private fun run(vararg sources: Uri): List<Callback> {
        val callbacks = CopyOnWriteArrayList<Callback>()
        ImageFileCompressEngine().onStartCompress(context, ArrayList(sources.toList()), object : OnKeyValueResultCallbackListener {
            override fun onCallback(srcPath: String?, resultPath: String?) {
                callbacks += Callback(srcPath, resultPath, Looper.myLooper() == Looper.getMainLooper())
            }
        })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (callbacks.size < sources.size && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("one callback per source", sources.size, callbacks.size)
        assertTrue("callbacks must run on main", callbacks.all { it.onMain })
        return callbacks
    }
}
