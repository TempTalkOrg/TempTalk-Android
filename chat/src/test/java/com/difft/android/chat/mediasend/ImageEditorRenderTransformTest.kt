package com.difft.android.chat.mediasend

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.imageeditor.core.model.EditorModel
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.ContentSourceHarness
import com.difft.android.test.harness.LogCapture
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import top.zibin.luban.Luban
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * T46 — the standard-quality compression path reads its source through the resolver.
 *
 * Luban's `load(String)` overload treats its argument as a bare file path, so passing a MediaStore
 * URI as a string silently produced an unreadable source. `load(Uri)` uses
 * `ContentResolver.openInputStream`, which is what [ContentSourceHarness.opens] proves here.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ImageEditorRenderTransformTest {

    private lateinit var context: Context
    private lateinit var harness: ContentSourceHarness

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        harness = ContentSourceHarness(context)
    }

    @Test
    fun `standard quality compression opens the source content uri through the resolver`() {
        val media = LocalMediaBuilder.gallery(
            id = 5L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/5",
            realPath = "/storage/emulated/0/DCIM/Camera/img_5.jpg",
        )
        val source = Uri.parse(media.path)
        harness.registerReadable(source, ByteArray(200_000) { 0x1 })

        val transform = ImageEditorModelRenderMediaTransform(null, null, SentMediaQuality.STANDARD)
        transform.transform(context, media)

        assertTrue("resolver opens were ${harness.opens}", harness.opens.contains(source))
    }

    // ---------------------------------------------------------------- T59

    /**
     * T59 — a failed render must not fall back to the untouched original.
     *
     * Reaching the render branch means the user cropped, drew on or captioned the image, so sending
     * the original instead is sending something they never composed. The cleanup in `finally` still
     * has to run on the propagating path, which the recycle assertion pins.
     *
     * The throw is raised from the compress call rather than from `render()` itself: `bitmap` is
     * assigned from the render result, so a throw inside `render()` leaves it null and the recycle
     * contract becomes unobservable. Both calls sit in the same try block and both mean "the render
     * step failed".
     */
    @Test
    fun `failed render propagates instead of silently sending the original`() {
        val media = LocalMediaBuilder.gallery(
            id = 6L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/6",
            realPath = "/storage/emulated/0/DCIM/Camera/img_6.jpg",
        )
        val originalRealPath = media.realPath
        val rendered = mockk<Bitmap>(relaxed = true)
        every { rendered.compress(any(), any(), any()) } throws RuntimeException("render failed")
        val model = mockk<EditorModel>()
        every { model.render(any(), any(), any()) } returns rendered

        val transform = ImageEditorModelRenderMediaTransform(model, null, SentMediaQuality.HIGH)

        assertThrows(RuntimeException::class.java) { transform.transform(context, media) }
        assertEquals(originalRealPath, media.realPath)
        verify(exactly = 1) { rendered.recycle() }
    }

    // ---------------------------------------------------------------- T60

    /**
     * T60 — compression is a size optimisation, so failing it after a successful render must not
     * fail the send: the user's edits already exist in the rendered output.
     */
    @Test
    fun `compression failure after a successful render keeps the rendered output`() {
        File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY)).mkdirs()
        val media = LocalMediaBuilder.gallery(
            id = 7L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/7",
            realPath = "/storage/emulated/0/DCIM/Camera/img_7.jpg",
        )
        givenCompressionFails()
        val model = mockk<EditorModel>()
        every { model.render(any(), any(), any()) } returns mockk(relaxed = true)

        val log = LogCapture()
        log.recording {
            ImageEditorModelRenderMediaTransform(model, null, SentMediaQuality.STANDARD)
                .transform(context, media)
            awaitLine("the compression downgrade line") { it.contains("compress skipped after render") }
        }

        assertTrue(
            media.realPath,
            media.realPath.startsWith(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY))
        )
    }

    // ---------------------------------------------------------------- T61

    /**
     * T61 — with nothing rendered, the compressor was reading the source, so a failure there IS a
     * source read failure and has to be reported while the review screen is still up.
     */
    @Test
    fun `compression failure with nothing rendered propagates as a source read failure`() {
        val media = LocalMediaBuilder.gallery(
            id = 8L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/8",
            realPath = "/storage/emulated/0/DCIM/Camera/img_8.jpg",
        )
        givenCompressionFails()

        assertThrows(IOException::class.java) {
            ImageEditorModelRenderMediaTransform(null, null, SentMediaQuality.STANDARD)
                .transform(context, media)
        }
    }

    // ---------------------------------------------------------------- T62

    /**
     * T62 — after a render, the compressor must read the render output, not the source.
     *
     * `readableUri()` is a source-only contract: it reads `path`, which a render never rewrites. So
     * compressing through it would re-read the untouched gallery original and hand that to the send,
     * silently discarding the crop / drawing / text the user just composed — invisible to the T46 row
     * above, which only covers the unedited path.
     */
    @Test
    fun `standard quality compression after a render reads the render output not the source`() {
        val draftDir = FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY)
        File(draftDir).mkdirs()
        val media = LocalMediaBuilder.gallery(
            id = 9L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/9",
            realPath = "/storage/emulated/0/DCIM/Camera/img_9.jpg",
        )
        val sourceUri = Uri.parse(media.path)
        val loaded = givenCompressionSucceeds(File(draftDir, "compressed_9.jpg"))
        val model = mockk<EditorModel>()
        every { model.render(any(), any(), any()) } returns mockk(relaxed = true)

        ImageEditorModelRenderMediaTransform(model, null, SentMediaQuality.STANDARD)
            .transform(context, media)

        val compressInput = loaded.captured
        assertEquals("compressed the source instead of the render", "file", compressInput.scheme)
        assertNotEquals(sourceUri, compressInput)
        assertTrue(
            compressInput.toString(),
            compressInput.path.orEmpty().startsWith(draftDir)
        )
    }

    // ---------------------------------------------------------------- T63

    /**
     * T63 — with nothing rendered there is no output to read, so the compressor must still go
     * through the resolver: a `file://` URI over the bare `realPath` is exactly what scoped storage
     * makes unreadable.
     */
    @Test
    fun `standard quality compression with nothing rendered loads the resolved source uri`() {
        val draftDir = FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY)
        File(draftDir).mkdirs()
        val media = LocalMediaBuilder.gallery(
            id = 10L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/10",
            realPath = "/storage/emulated/0/DCIM/Camera/img_10.jpg",
        )
        val loaded = givenCompressionSucceeds(File(draftDir, "compressed_10.jpg"))

        ImageEditorModelRenderMediaTransform(null, null, SentMediaQuality.STANDARD)
            .transform(context, media)

        assertEquals(media.readableUri(), loaded.captured)
        assertEquals(Uri.parse(media.path), loaded.captured)
    }

    /**
     * Stubs the compressor to succeed with [output] and captures the URI it was asked to read.
     *
     * The whole builder chain has to be stubbed because the terminal `get()` is what performs the
     * read; capturing `load` is the only way to observe *which* source the compressor was pointed at,
     * which a resolver-open assertion cannot distinguish once a render output also exists.
     */
    private fun givenCompressionSucceeds(output: File): CapturingSlot<Uri> {
        val loaded = slot<Uri>()
        mockkStatic(Luban::class)
        val builder = mockk<Luban.Builder>()
        every { Luban.with(any()) } returns builder
        every { builder.load(capture(loaded)) } returns builder
        every { builder.ignoreBy(any()) } returns builder
        every { builder.setTargetDir(any()) } returns builder
        every { builder.setRenameListener(any()) } returns builder
        every { builder.get() } returns mutableListOf(output)
        return loaded
    }

    /**
     * Makes the compressor report an unreadable source.
     *
     * The upstream compressor swallows a failing source internally and simply produces nothing, so
     * an unreadable registration cannot reach our catch block. Stubbing its terminal call is the
     * only way to exercise the two branches that decide between propagating and degrading.
     */
    private fun givenCompressionFails() {
        mockkStatic(Luban::class)
        val builder = mockk<Luban.Builder>()
        every { Luban.with(any()) } returns builder
        every { builder.load(any<Uri>()) } returns builder
        every { builder.ignoreBy(any()) } returns builder
        every { builder.setTargetDir(any()) } returns builder
        every { builder.setRenameListener(any()) } returns builder
        every { builder.get() } throws IOException("cannot read source")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }
}
