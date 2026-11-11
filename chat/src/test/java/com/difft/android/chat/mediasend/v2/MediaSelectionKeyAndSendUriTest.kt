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
import org.junit.Assert.assertNotSame
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

    
    private class RewritingTransform(private val outputPath: String) : MediaTransform {
        var invocations: Int = 0
        override fun transform(context: Context, media: LocalMedia): LocalMedia {
            invocations++
            media.realPath = outputPath
            return media
        }
    }

    



    private class NoOpTransform : MediaTransform {
        var invocations: Int = 0
        override fun transform(context: Context, media: LocalMedia): LocalMedia {
            invocations++
            return media
        }
    }

    

    




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
        
        val written = mapOf<MediaKey, Any>(
            video.mediaKey() to trim,
            image.mediaKey() to ImageEditorFragment.Data(),
        )

        val state = MediaSelectionState(
            selectedMedia = listOf(video, image),
            focusedMedia = video,
            editorStateMap = written,
        )

        
        assertEquals(trim, state.getOrCreateVideoTrimData(video.mediaKey()))
        
        assertEquals(trim, state.getOrCreateVideoTrimData(MediaKey(video.readableUri())))
        
        val models = repository.buildModelsToTransform(listOf(video, image), written, SentMediaQuality.HIGH)
        assertTrue(models[video].toString(), models[video] is VideoTrimTransform)
        assertTrue(models[image].toString(), models[image] is ImageEditorModelRenderMediaTransform)
    }

    

    







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
        assertSame(edited, result.updated.keys.first())
        assertNotSame(edited, result.updated.getValue(edited).media)
        assertEquals("/storage/emulated/0/DCIM/a.jpg", edited.realPath)

        val editedSendUri = requireNotNull(result.updated[edited]).sendUri
        assertEquals(Uri.fromFile(File(outputPath)), editedSendUri)
        assertEquals("file", editedSendUri.scheme)
        assertNotEquals(sourceUriOfEdited, editedSendUri)

        assertEquals(untouched.readableUri(), requireNotNull(result.updated[untouched]).sendUri)
        assertEquals("content", requireNotNull(result.updated[untouched]).sendUri.scheme)
        assertSame(untouched, requireNotNull(result.updated[untouched]).media)
    }

    

    




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
        assertSame(media, result.updated.keys.single())
        assertNotSame(media, result.updated.getValue(media).media)
        assertEquals(Uri.parse("content://media/external/video/media/7"), sendUri)
        assertEquals("content", sendUri.scheme)
        assertNotEquals(Uri.fromFile(File("/storage/emulated/0/Movies/keep.mp4")), sendUri)
    }

    @Test
    fun `sandbox transform keeps the selected source and its editor key unchanged`() {
        val media = LocalMediaBuilder.sandbox("/data/user/0/com.difft.android/files/source.png", "image/png")
        val sourceKey = media.mediaKey()
        val outputPath = "/data/user/0/com.difft.android/files/draft_blobs/output.jpg"
        val transform = RewritingTransform(outputPath)

        val result = repository.transformMediaSync(context, listOf(media), mapOf(media to transform))

        assertTrue(result.failures.isEmpty())
        assertSame(media, result.updated.keys.single())
        assertNotSame(media, result.updated.getValue(media).media)
        assertEquals(outputPath, result.updated.getValue(media).media.realPath)
        assertEquals(Uri.fromFile(File(outputPath)), result.updated.getValue(media).sendUri)
        assertEquals("/data/user/0/com.difft.android/files/source.png", media.realPath)
        assertEquals(sourceKey, media.mediaKey())
    }

    @Test
    fun `transform failure after mutation leaves source metadata available for retry`() {
        val media = LocalMediaBuilder.gallery(mime = "image/png", realPath = "/gallery/source.png")
        val sourceUri = media.readableUri()
        harness.registerReadable(sourceUri, ByteArray(4))
        val transform = object : MediaTransform {
            override fun transform(context: Context, media: LocalMedia): LocalMedia {
                media.path = "content://attempt/output"
                media.realPath = "/output/failed.jpg"
                media.mimeType = "image/jpeg"
                media.fileName = "failed.jpg"
                throw IllegalStateException("transform failed")
            }
        }

        val failed = repository.transformMediaSync(context, listOf(media), mapOf(media to transform))

        assertTrue(failed.updated.isEmpty())
        assertEquals(1, failed.failures.size)
        assertEquals(sourceUri, media.readableUri())
        assertEquals("/gallery/source.png", media.realPath)
        assertEquals("image/png", media.mimeType)
        assertEquals("source.png", media.fileName)
        val retry = repository.transformMediaSync(context, listOf(media), mapOf(media to NoOpTransform()))
        assertTrue(retry.failures.isEmpty())
        assertEquals(sourceUri, retry.updated.getValue(media).sendUri)
        assertEquals("image/png", retry.updated.getValue(media).media.mimeType)
        assertEquals("source.png", retry.updated.getValue(media).media.fileName)
    }

    

    




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
