package com.difft.android.chat.mediasend.v2

import android.content.Context
import android.net.Uri
import com.difft.android.chat.mediasend.ImageEditorModelRenderMediaTransform
import com.difft.android.chat.mediasend.MediaFailureReason
import com.difft.android.chat.mediasend.MediaKey
import com.difft.android.chat.mediasend.VideoTrimTransform
import com.difft.android.chat.mediasend.mediaKey
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.mediasend.v2.videos.VideoTrimData
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.scribbles.ImageEditorFragment
import com.difft.android.imageeditor.core.model.EditorModel
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.ContentSourceHarness
import com.difft.android.video.exceptions.VideoSourceException
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/**
 * T22 — the whole send chain in one pass, on the routing and assembly layer.
 *
 * This is the only automated row that covers a healthy device end to end: three items of the three
 * shapes that actually reach `send()`, one of which fails, going through the real
 * `buildModelsToTransform` -> `transformMediaSync` -> `resolveSendUri` path rather than any of them
 * in isolation. What it pins that no per-unit row can:
 *
 *  - bytes are really taken through [android.content.ContentResolver] and not from a bare path,
 *    which is the entire point of the URI migration ([ContentSourceHarness.opens] is the only
 *    available witness — the shadow resolver keeps no read log);
 *  - one item failing no longer discards the batch, and the caption survives it;
 *  - an edited item is sent from the edit output while an untouched one is sent from its source.
 *
 * The video's failure is *injected*, not hoped for: `ShadowMediaMetadataRetriever` does not throw
 * from `setDataSource` by default, so without an explicit exception the row would be asserting on
 * whichever accidental failure the shadow codec happened to produce.
 *
 * Deliberately NOT covered here: transcoded/compressed output equivalence and descriptor `statSize`
 * truth. Both need a real codec and belong to the on-device rows.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSendChainIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var repository: MediaSelectionRepository
    private lateinit var harness: ContentSourceHarness

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = MediaSelectionRepository(context)
        harness = ContentSourceHarness(context)
    }

    @After
    fun tearDown() {
        ShadowMediaMetadataRetriever.reset()
        DataSource.reset()
        unmockkAll()
    }

    @Test
    fun `send resolves the readable items through the resolver and reports only the one that failed`() {
        // (1) Gallery video with a trim: reaches the transcoder, which cannot bind its data source.
        val video = LocalMediaBuilder.gallery(
            id = 1L,
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/1",
            realPath = "/storage/emulated/0/Movies/v.mp4",
            size = 8_000_000L,
            durationMs = 8_000L,
        )
        // (2) Gallery image with editor state: renders, then compresses, and must be sent from the
        // output rather than from the source.
        val image = LocalMediaBuilder.gallery(
            id = 2L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/2",
            realPath = "/storage/emulated/0/DCIM/Camera/img_2.jpg",
            size = 200_000L,
        )
        // (3) Sandbox pass-through, no transformer: the shape whose immunity to scoped storage must
        // not be broken. Backed by a real file, because the pass-through probe opens it for real.
        val sandboxFile = temp.newFile("x.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val passThrough = LocalMediaBuilder.sandbox(realPath = sandboxFile.path)

        val videoSource = video.readableUri()
        val imageSource = image.readableUri()
        harness.registerReadable(videoSource, ByteArray(64))
        // Registered and above the compressor's 100 kB floor purely so that a regression to
        // compressing the *source* of an edited item shows up as an open below, instead of passing
        // silently. Nothing in this row is supposed to read it.
        harness.registerReadable(imageSource, ByteArray(200_000) { 0x1 })
        val injected = givenDataSourceCannotBeBound(videoSource)

        val stateMap = mapOf<MediaKey, Any>(
            video.mediaKey() to VideoTrimData(true, 8_000_000L, 0L, 4_000_000L),
            image.mediaKey() to givenEditorState(),
        )
        val selected = listOf(video, image, passThrough)

        // (3) The editor-state lookup hits for both edited items under the key each one derives, and
        // the untouched item has no transformer. A miss here is the silent-edit-loss failure mode.
        val models = repository.buildModelsToTransform(selected, stateMap, SentMediaQuality.STANDARD)
        assertTrue(models[video].toString(), models[video] is VideoTrimTransform)
        assertTrue(models[image].toString(), models[image] is ImageEditorModelRenderMediaTransform)
        assertNull(models[passThrough])

        val outcome = runBlocking {
            repository.send(selected, stateMap, SentMediaQuality.STANDARD, "caption")
        }

        // (1) Two of three items are sendable, in the input's success order. Not three: the video
        // failed. Not zero: a single failure no longer discards the batch.
        assertEquals(2, outcome.result.media.size)
        assertEquals(listOf(image, passThrough), outcome.result.media.map { it.media })

        // (2) The gallery source that this chain does read was opened through the resolver, and
        // nothing was read from a file:// URI — a bare-path read is exactly what the reporting
        // devices cannot do.
        assertTrue("resolver opens were ${harness.opens}", harness.opens.contains(videoSource))
        // The edited image's compression reads the render output, never the source: re-reading the
        // source there is the silent-edit-loss failure mode, because `path` still points at the
        // untouched original after a render.
        assertTrue("resolver opens were ${harness.opens}", imageSource !in harness.opens)
        assertTrue(
            "resolver opens were ${harness.opens}",
            harness.opens.none { it.scheme == "file" }
        )

        // (4) The untouched item is passed through by identity and sent from its own normalized URI.
        val sandboxSendable = outcome.result.media.last()
        assertSame(passThrough, sandboxSendable.media)
        assertEquals(passThrough.readableUri(), sandboxSendable.sendUri)

        // (5) The caption belongs to the message, not to any one attachment, so a failed item must
        // not take the typed text with it.
        assertEquals("caption", outcome.result.body)

        // (6) The video is reported as one failed item rather than thrown out of send(). Its source
        // is readable in this harness and its cause chain carries a VideoSourceException, so the
        // reason is the format one — not the read one, which is a different row's scenario.
        assertEquals(1, outcome.failures.size)
        val failure = outcome.failures.single()
        assertEquals(MediaFailureReason.MEDIA_UNSUPPORTED, failure.reason)
        assertEquals(1, failure.position)
        assertTrue(
            causeTypes(failure.cause),
            causeChainOf(failure.cause).any { it is VideoSourceException }
        )
        // The failure is the injected bind failure and nothing else. Without this the row would
        // also pass on the descriptor-size branch, which fails for a reason the harness never
        // arranged — and H4 would then be decoration.
        assertTrue(
            causeTypes(failure.cause),
            causeChainOf(failure.cause).any { it === injected }
        )

        // (7) The edited image is sent from the render/compress output, never from the source URI:
        // re-deriving it downstream is what would silently send the unedited original.
        val imageSendable = outcome.result.media.first()
        assertEquals("file", imageSendable.sendUri.scheme)
        assertNotEquals(imageSource, imageSendable.sendUri)
    }

    /**
     * H4 — a deterministic bind failure for [uri]. `RuntimeException` is the only type the shadow
     * accepts, which is also what the transcoder catches and wraps.
     */
    private fun givenDataSourceCannotBeBound(uri: Uri): RuntimeException =
        RuntimeException("codec unavailable in CI").also {
            ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(context, uri), it)
        }

    /**
     * Editor state carrying a model, stubbed rather than serialized: a real [EditorModel] would need
     * a rendering pass this row is not about, and the assertion is that the state map is *found* and
     * turned into a render transform.
     */
    private fun givenEditorState(): ImageEditorFragment.Data {
        val model = mockk<EditorModel>()
        every { model.render(any(), any(), any()) } returns mockk(relaxed = true)
        return mockk<ImageEditorFragment.Data>().also { every { it.readModel() } returns model }
    }

    private fun causeChainOf(t: Throwable?): Sequence<Throwable> =
        generateSequence(t) { cur -> cur.cause?.takeIf { it !== cur } }.take(MAX_CAUSE_DEPTH)

    private fun causeTypes(t: Throwable?): String =
        causeChainOf(t).joinToString("<-") { it.javaClass.simpleName }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}
