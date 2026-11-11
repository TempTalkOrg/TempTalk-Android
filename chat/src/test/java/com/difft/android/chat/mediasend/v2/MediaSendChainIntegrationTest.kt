package com.difft.android.chat.mediasend.v2

import android.content.Context
import android.net.Uri
import com.difft.android.base.media.ImageCompressor
import com.difft.android.base.utils.FileUtil
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
import io.mockk.mockkObject
import io.mockk.slot
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
import java.io.File










@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSendChainIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var repository: MediaSelectionRepository
    private lateinit var harness: ContentSourceHarness
    private lateinit var compressedImage: File
    private val compressionInput = slot<Uri>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = MediaSelectionRepository(context)
        harness = ContentSourceHarness(context)
        val draftDir = temp.newFolder("draft_blobs")
        compressedImage = temp.newFile("compressed.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        mockkObject(FileUtil, ImageCompressor)
        every { FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY) } returns draftDir.path + File.separator
        every { ImageCompressor.compress(any(), capture(compressionInput), any(), any(), any(), any(), any()) } returns
            ImageCompressor.Result.Compressed(compressedImage, "image/jpeg", 640, 480, compressedImage.length(), 0)
    }

    @After
    fun tearDown() {
        ShadowMediaMetadataRetriever.reset()
        DataSource.reset()
        unmockkAll()
    }

    @Test
    fun `send resolves the readable items through the resolver and reports only the one that failed`() {
        
        val video = LocalMediaBuilder.gallery(
            id = 1L,
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/1",
            realPath = "/storage/emulated/0/Movies/v.mp4",
            size = 8_000_000L,
            durationMs = 8_000L,
        )
        
        
        val image = LocalMediaBuilder.gallery(
            id = 2L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/2",
            realPath = "/storage/emulated/0/DCIM/Camera/img_2.jpg",
            size = 200_000L,
        )
        
        
        val sandboxFile = temp.newFile("x.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val passThrough = LocalMediaBuilder.sandbox(realPath = sandboxFile.path)

        val videoSource = video.readableUri()
        val imageSource = image.readableUri()
        harness.registerReadable(videoSource, ByteArray(64))
        
        harness.registerReadable(imageSource, ByteArray(200_000) { 0x1 })
        val injected = givenDataSourceCannotBeBound(videoSource)

        val stateMap = mapOf<MediaKey, Any>(
            video.mediaKey() to VideoTrimData(true, 8_000_000L, 0L, 4_000_000L),
            image.mediaKey() to givenEditorState(),
        )
        val selected = listOf(video, image, passThrough)

        
        
        val models = repository.buildModelsToTransform(selected, stateMap, SentMediaQuality.STANDARD)
        assertTrue(models[video].toString(), models[video] is VideoTrimTransform)
        assertTrue(models[image].toString(), models[image] is ImageEditorModelRenderMediaTransform)
        assertNull(models[passThrough])

        val outcome = runBlocking {
            repository.send(selected, stateMap, SentMediaQuality.STANDARD, "caption")
        }

        
        assertEquals(2, outcome.result.media.size)
        assertEquals(listOf(image, passThrough), outcome.result.media.map { it.media })

        
        assertTrue("resolver opens were ${harness.opens}", harness.opens.contains(videoSource))
        
        
        
        assertTrue("resolver opens were ${harness.opens}", imageSource !in harness.opens)
        assertTrue(
            "resolver opens were ${harness.opens}",
            harness.opens.none { it.scheme == "file" }
        )

        
        val sandboxSendable = outcome.result.media.last()
        assertSame(passThrough, sandboxSendable.media)
        assertEquals(passThrough.readableUri(), sandboxSendable.sendUri)

        
        
        assertEquals("caption", outcome.result.body)

        
        
        
        assertEquals(1, outcome.failures.size)
        val failure = outcome.failures.single()
        assertEquals(MediaFailureReason.MEDIA_UNSUPPORTED, failure.reason)
        assertEquals(1, failure.position)
        assertTrue(
            causeTypes(failure.cause),
            causeChainOf(failure.cause).any { it is VideoSourceException }
        )
        
        assertTrue(
            causeTypes(failure.cause),
            causeChainOf(failure.cause).any { it === injected }
        )

        
        
        val imageSendable = outcome.result.media.first()
        assertEquals("file", imageSendable.sendUri.scheme)
        assertNotEquals(imageSource, imageSendable.sendUri)
        assertEquals(compressedImage.path, imageSendable.sendUri.path)
        assertEquals("file", compressionInput.captured.scheme)
        assertNotEquals(imageSource, compressionInput.captured)
    }

    



    private fun givenDataSourceCannotBeBound(uri: Uri): RuntimeException =
        RuntimeException("codec unavailable in CI").also {
            ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(context, uri), it)
        }

    



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
