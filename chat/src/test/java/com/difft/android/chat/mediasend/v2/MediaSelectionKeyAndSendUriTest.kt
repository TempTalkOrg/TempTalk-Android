package com.difft.android.chat.mediasend.v2

import android.content.Context
import android.net.Uri
import com.difft.android.chat.mediasend.ImageEditorModelRenderMediaTransform
import com.difft.android.chat.mediasend.MediaKey
import com.difft.android.chat.mediasend.MediaTransform
import com.difft.android.chat.mediasend.VideoTrimTransform
import com.difft.android.chat.mediasend.mediaKey
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.mediasend.v2.videos.VideoTrimData
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.scribbles.ImageEditorFragment
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.ContentSourceHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * T4 / T5 / T25 / T26 / T27 — editor-state key identity and send-URI resolution.
 *
 * All rows but T27 stay at the pure-function layer on purpose: `Store.update` dispatches onto
 * `Dispatchers.Default`, which no test dispatcher rule replaces, so asserting on state right after
 * a view model call would be intermittently wrong. T27 does need the real view model, and polls
 * through the public accessor with a bounded wait rather than assuming the update already ran.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSelectionKeyAndSendUriTest {

    private lateinit var repository: MediaSelectionRepository
    private lateinit var context: Context
    private lateinit var harness: ContentSourceHarness

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = MediaSelectionRepository(context)
        harness = ContentSourceHarness(context)
    }

    /** Rewrites `realPath` to its output and returns the same instance, as the real transforms do. */
    private class RewritingTransform(private val outputPath: String) : MediaTransform {
        var invocations: Int = 0
        override fun transform(context: Context, media: LocalMedia): LocalMedia {
            invocations++
            media.realPath = outputPath
            return media
        }
    }

    /**
     * Runs but keeps the original media, which is what three production branches do: transcode not
     * required, fast remux failed, and nothing to render.
     */
    private class NoOpTransform : MediaTransform {
        var invocations: Int = 0
        override fun transform(context: Context, media: LocalMedia): LocalMedia {
            invocations++
            return media
        }
    }

    // ---------------------------------------------------------------- T4

    /**
     * T4 — the state map written under a media key is found again by the repository. If the lookup
     * still went through the bare `realPath`, this map would miss and the user's trim would be
     * dropped without a trace.
     */
    @Test
    fun `state map lookup hits for the key the item derives`() {
        val video = LocalMediaBuilder.gallery(
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/12345",
            realPath = "/storage/emulated/0/Movies/a.mp4",
        )
        val stateMap = mapOf<MediaKey, Any>(
            video.mediaKey() to VideoTrimData(true, 8_000_000L, 0L, 4_000_000L)
        )

        val models = repository.buildModelsToTransform(listOf(video), stateMap, SentMediaQuality.HIGH)

        assertEquals(1, models.size)
        assertTrue(models[video].toString(), models[video] is VideoTrimTransform)
    }

    // ---------------------------------------------------------------- T5

    /**
     * T5 — the key written on the view-model side is the same key each of the three readers looks
     * up: `MediaSelectionState` (timeline / size hint), the page-fragment side whose key comes from
     * ARG_URI, and the repository at the send boundary.
     */
    @Test
    fun `key written on the selection side is found by every reader`() {
        val video = LocalMediaBuilder.gallery(
            id = 1L,
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/1",
            realPath = "/storage/emulated/0/Movies/v.mp4",
        )
        val image = LocalMediaBuilder.gallery(
            id = 2L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/2",
            realPath = "/storage/emulated/0/DCIM/i.jpg",
        )
        val trim = VideoTrimData(true, 8_000_000L, 1_000_000L, 5_000_000L)
        // Exactly the derivation addMedia / onEditVideoDuration use when writing the map.
        val written = mapOf<MediaKey, Any>(
            video.mediaKey() to trim,
            image.mediaKey() to ImageEditorFragment.Data(),
        )

        val state = MediaSelectionState(
            selectedMedia = listOf(video, image),
            focusedMedia = video,
            editorStateMap = written,
        )

        // Reader 1: state, keyed from the media itself.
        assertEquals(trim, state.getOrCreateVideoTrimData(video.mediaKey()))
        // Reader 2: the page-fragment side, keyed from the URI the pager put into ARG_URI.
        assertEquals(trim, state.getOrCreateVideoTrimData(MediaKey(video.readableUri())))
        // Reader 3: the repository at the send boundary.
        val models = repository.buildModelsToTransform(listOf(video, image), written, SentMediaQuality.HIGH)
        assertTrue(models[video].toString(), models[video] is VideoTrimTransform)
        assertTrue(models[image].toString(), models[image] is ImageEditorModelRenderMediaTransform)
    }

    // ---------------------------------------------------------------- T25

    /**
     * T25 — a transform that wrote new bytes must be sent from those bytes, never from the source.
     *
     * This is the row that pins the whole point of resolving the send URI at the transform
     * boundary: `readableUri()` after a transform still returns the *pre-edit* content URI,
     * because no transform rewrites `path`. Re-deriving it downstream would silently send the
     * unedited original for every cropped, drawn-on or trimmed item.
     */
    @Test
    fun `send uri points at the transform output and not at the source`() {
        val edited = LocalMediaBuilder.gallery(
            id = 1L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/1",
            realPath = "/storage/emulated/0/DCIM/a.jpg",
        )
        val untouched = LocalMediaBuilder.gallery(
            id = 2L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/2",
            realPath = "/storage/emulated/0/DCIM/b.jpg",
        )
        val sourceUriOfEdited = edited.readableUri()
        // The untouched item is read for real at the transform boundary, so it has to be readable.
        harness.registerReadable(untouched.readableUri(), ByteArray(4))
        val outputPath = "/data/user/0/com.difft.android/files/draft_attachments/out.jpg"
        val transform = RewritingTransform(outputPath)

        val result = repository.transformMediaSync(
            context,
            listOf(edited, untouched),
            mapOf(edited to transform),
        )

        assertEquals(1, transform.invocations)
        assertEquals(listOf(edited, untouched), result.updated.keys.toList())

        val editedSendUri = requireNotNull(result.updated[edited]).sendUri
        assertEquals(Uri.fromFile(File(outputPath)), editedSendUri)
        assertEquals("file", editedSendUri.scheme)
        assertNotEquals(sourceUriOfEdited, editedSendUri)

        assertEquals(untouched.readableUri(), requireNotNull(result.updated[untouched]).sendUri)
        assertEquals("content", requireNotNull(result.updated[untouched]).sendUri.scheme)
        assertSame(untouched, requireNotNull(result.updated[untouched]).media)
    }

    // ---------------------------------------------------------------- T26

    /**
     * T26 — a transform that ran but kept the original media falls back to the normalized source
     * URI. Falling back to a file URI over the bare `realPath` here is exactly the unreadable
     * shape under scoped storage, so this branch would keep failing on the reporting devices.
     */
    @Test
    fun `send uri falls back to the source content uri when the transform kept the original`() {
        val media = LocalMediaBuilder.gallery(
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/7",
            realPath = "/storage/emulated/0/Movies/keep.mp4",
        )
        harness.registerReadable(media.readableUri(), ByteArray(4))
        val transform = NoOpTransform()

        val result = repository.transformMediaSync(context, listOf(media), mapOf(media to transform))

        val sendUri = requireNotNull(result.updated[media]).sendUri
        assertEquals(1, transform.invocations)
        assertEquals(Uri.parse("content://media/external/video/media/7"), sendUri)
        assertEquals("content", sendUri.scheme)
        assertNotEquals(Uri.fromFile(File("/storage/emulated/0/Movies/keep.mp4")), sendUri)
    }

    // ---------------------------------------------------------------- T27

    /**
     * T27 — removal drops the entry under the same key it was written with, and leaves the other
     * item's editor state alone. The only deletion site in the key domain, and the one the design
     * flags as easy to leave behind.
     */
    @Test
    fun `removeMedia drops the editor state under the media key`() {
        val kept = LocalMediaBuilder.gallery(
            id = 1L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/1",
            realPath = "/storage/emulated/0/DCIM/keep.jpg",
        )
        val removed = LocalMediaBuilder.gallery(
            id = 2L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/2",
            realPath = "/storage/emulated/0/DCIM/drop.jpg",
        )
        val viewModel = MediaSelectionViewModel(listOf(kept, removed), repository)
        // Store.update runs on Dispatchers.Default, which no dispatcher rule replaces.
        awaitUntil("initial editor states") {
            viewModel.getEditorState(kept.mediaKey()) != null &&
                viewModel.getEditorState(removed.mediaKey()) != null
        }

        viewModel.removeMedia(removed)

        awaitUntil("removed entry is gone") { viewModel.getEditorState(removed.mediaKey()) == null }
        assertNull(viewModel.getEditorState(removed.mediaKey()))
        assertNotNull(viewModel.getEditorState(kept.mediaKey()))
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("$what not reached within ${timeoutMs}ms")
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10L
    }
}
