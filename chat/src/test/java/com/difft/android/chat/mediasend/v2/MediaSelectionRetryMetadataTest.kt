package com.difft.android.chat.mediasend.v2

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import com.difft.android.base.media.ImageCompressor
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.appScope
import com.difft.android.chat.mediasend.ImageTransformTestBase
import com.difft.android.chat.mediasend.MediaFailureReason
import com.difft.android.chat.mediasend.MediaSendActivityResult
import com.difft.android.chat.mediasend.SendableMedia
import com.difft.android.chat.mediasend.mediaKey
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.scribbles.ImageEditorFragment
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.LogCapture
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64


@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class MediaSelectionRetryMetadataTest : ImageTransformTestBase() {
    private lateinit var retirementScope: TestScope

    @Before
    fun setUpRetirement() {
        retirementScope = TestScope()
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns retirementScope
    }

    @After
    fun finishRetirement() {
        try {
            retirementScope.testScheduler.advanceUntilIdle()
        } finally {
            retirementScope.cancel()
        }
    }

    
    private val sourcePng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQMgkDAAD4AJ3MaiF4AAAAAElFTkSuQmCC"
    )

    @Test
    fun `standard partial then back and high sends original PNG bytes with source labels`() {
        assertStandardThenHigh("image/png", "photo.png", sourcePng)
    }

    @Test
    fun `standard partial then back and high sends original HEIC bytes with source labels`() {
        val heic = byteArrayOf(0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63)
        assertStandardThenHigh("image/heic", "photo.heic", heic)
    }

    private fun assertStandardThenHigh(mime: String, name: String, bytes: ByteArray) {
        val (source, sourceFile) = gallery(mime, name, bytes)
        val (unreadable, _) = gallery(name = "unreadable.jpg")
        harness.registerUnreadable(unreadable.readableUri(), unreadable.realPath)
        val selected = listOf(source, unreadable)
        val state = ImageEditorFragment.Data()
        val sourceKey = source.mediaKey()
        val states = mapOf(sourceKey to state, unreadable.mediaKey() to ImageEditorFragment.Data())
        val repository = MediaSelectionRepository(context)
        val compressed = givenCompressionSucceeds()

        val first = runBlocking { repository.send(selected, states, SentMediaQuality.STANDARD, " caption ") }

        assertPartial(first)
        assertSent(roundTrip(first).media.single(), Uri.fromFile(compressed.file), "image/jpeg", "photo.jpg", jpeg)
        assertNotSame(source, first.result.media.single().media)
        assertEquals(1600, first.result.media.single().media.width)
        assertEquals(1200, first.result.media.single().media.height)
        assertEquals(compressed.size, first.result.media.single().media.size)
        assertSource(source, sourceFile, mime, name, bytes.size.toLong())
        assertEquals(sourceKey, source.mediaKey())
        assertSame(state, states[source.mediaKey()])

        
        
        val retry = runBlocking { repository.send(selected, states, SentMediaQuality.HIGH, " caption ") }

        assertPartial(retry)
        assertSent(roundTrip(retry).media.single(), source.readableUri(), mime, name, bytes)
        assertNotSame(source, retry.result.media.single().media)
        assertNotSame(first.result.media.single().media, retry.result.media.single().media)
        assertSource(source, sourceFile, mime, name, bytes.size.toLong())
        assertEquals(sourceKey, source.mediaKey())
        assertSame(state, states[source.mediaKey()])
        assertEquals(2, repository.buildModelsToTransform(selected, states, SentMediaQuality.HIGH).size)
        verify(exactly = 1) { ImageCompressor.compress(any(), source.readableUri(), mime, any(), any(), any(), any()) }
    }

    @Test
    fun `high partial then back and standard compresses the original PNG with matching output labels`() {
        val (source, sourceFile) = gallery("image/png", "photo.png", sourcePng)
        val (unreadable, _) = gallery(name = "unreadable.jpg")
        harness.registerUnreadable(unreadable.readableUri(), unreadable.realPath)
        val selected = listOf(source, unreadable)
        val states = selected.associate { it.mediaKey() to ImageEditorFragment.Data() }
        val repository = MediaSelectionRepository(context)
        val compressed = givenCompressionSucceeds()

        val first = runBlocking { repository.send(selected, states, SentMediaQuality.HIGH, " caption ") }

        assertPartial(first)
        assertSent(roundTrip(first).media.single(), source.readableUri(), "image/png", "photo.png", sourcePng)
        verify(exactly = 0) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }

        val retry = runBlocking { repository.send(selected, states, SentMediaQuality.STANDARD, " caption ") }

        assertPartial(retry)
        assertSent(roundTrip(retry).media.single(), Uri.fromFile(compressed.file), "image/jpeg", "photo.jpg", jpeg)
        assertEquals(source.readableUri(), loaded.captured)
        assertSource(source, sourceFile, "image/png", "photo.png", sourcePng.size.toLong())
        verify(exactly = 1) { ImageCompressor.compress(any(), source.readableUri(), "image/png", any(), any(), any(), any()) }
    }

    @Test
    fun `repeated standard attempts keep source metadata and use fresh outputs`() {
        val (source, sourceFile) = gallery("image/png", "photo.png", sourcePng)
        val selected = listOf(source)
        val states = mapOf(source.mediaKey() to ImageEditorFragment.Data())
        val repository = MediaSelectionRepository(context)
        val inputs = mutableListOf<Pair<Uri, String?>>()
        every { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) } answers {
            inputs += secondArg<Uri>() to thirdArg<String?>()
            val file = File(draftDir, "attempt-${inputs.size}.jpg").apply { writeBytes(jpeg) }
            ImageCompressor.Result.Compressed(file, "image/jpeg", 1600, 1200, file.length(), 0)
        }

        val first = runBlocking { repository.send(selected, states, SentMediaQuality.STANDARD, null) }
        val second = runBlocking { repository.send(selected, states, SentMediaQuality.STANDARD, null) }

        assertEquals(listOf(source.readableUri() to "image/png", source.readableUri() to "image/png"), inputs)
        assertNotSame(first.result.media.single().media, second.result.media.single().media)
        assertEquals("attempt-1.jpg", File(first.result.media.single().sendUri.path!!).name)
        assertEquals("attempt-2.jpg", File(second.result.media.single().sendUri.path!!).name)
        assertSource(source, sourceFile, "image/png", "photo.png", sourcePng.size.toLong())
    }

    @Test
    fun `standard partial retains a selected draft source for a high retry`() {
        val (source, sourceFile) = draft("image/png", "pasted.png", sourcePng)
        val (unreadable, _) = gallery(name = "unreadable.jpg")
        harness.registerUnreadable(unreadable.readableUri(), unreadable.realPath)
        val selected = listOf(source, unreadable)
        val sourceKey = source.mediaKey()
        val states = selected.associate { it.mediaKey() to ImageEditorFragment.Data() }
        val repository = MediaSelectionRepository(context)
        val compressed = givenCompressionSucceeds()

        val first = runBlocking { repository.send(selected, states, SentMediaQuality.STANDARD, "caption") }

        assertPartial(first)
        assertTrue(first.retirableSources.isEmpty())
        assertSent(first.result.media.single(), Uri.fromFile(compressed.file), "image/jpeg", "pasted.jpg", jpeg)
        assertTrue(compressed.file.exists())
        assertTrue(sourceFile.exists())
        assertArrayEquals(sourcePng, sourceFile.readBytes())
        assertEquals(sourceKey, source.mediaKey())

        val retry = runBlocking { repository.send(selected, states, SentMediaQuality.HIGH, "caption") }

        assertPartial(retry)
        assertSent(roundTrip(retry).media.single(), Uri.fromFile(sourceFile), "image/png", "pasted.png", sourcePng)
        assertSource(source, sourceFile, "image/png", "pasted.png", sourcePng.size.toLong())
    }

    @Test
    fun `consuming a complete standard send retires its source after activity handoff`() {
        val (source, sourceFile) = draft("image/png", "pasted.png", sourcePng)
        val sourceUri = source.readableUri()
        val compressed = givenCompressionSucceeds()
        val repository = MediaSelectionRepository(context)

        val outcome = LogCapture().recording {
            val sent = runBlocking {
                repository.send(listOf(source), mapOf(source.mediaKey() to ImageEditorFragment.Data()), SentMediaQuality.STANDARD, "caption")
            }
            retirementScope.testScheduler.advanceUntilIdle()
            assertTrue(sourceFile.exists())
            assertEquals(listOf(File(requireNotNull(sourceUri.path))), sent.retirableSources)
            consume(sent)
            assertFalse(sourceFile.exists())
            assertEquals(
                "[MediaSend] draft sources deleted=1 kept=0",
                awaitLine("complete source cleanup") { it.contains("[MediaSend] draft sources") }
            )
            sent
        }

        assertTrue(outcome.failures.isEmpty())
        assertTrue(compressed.file.exists())
        assertSent(roundTrip(outcome).media.single(), Uri.fromFile(compressed.file), "image/jpeg", "pasted.jpg", jpeg)
        verify { FileUtil.invalidateFileValidity(sourceUri.path!!) }
    }

    @Test
    fun `consuming a partial result keeps the draft source and successful output`() {
        val (source, sourceFile) = draft("image/png", "pasted.png", sourcePng)
        val (unreadable, _) = gallery(name = "unreadable.jpg")
        harness.registerUnreadable(unreadable.readableUri(), unreadable.realPath)
        val selected = listOf(source, unreadable)
        val compressed = givenCompressionSucceeds()
        val outcome = runBlocking {
            MediaSelectionRepository(context).send(
                selected, selected.associate { it.mediaKey() to ImageEditorFragment.Data() }, SentMediaQuality.STANDARD, "caption"
            )
        }

        assertPartial(outcome)
        assertTrue(outcome.retirableSources.isEmpty())
        LogCapture().recording {
            consume(outcome)
            assertEquals(
                "[MediaSend] draft sources deleted=0 kept=2",
                awaitLine("consumed partial source retention") { it.contains("[MediaSend] draft sources") }
            )
        }
        assertArrayEquals(sourcePng, sourceFile.readBytes())
        assertTrue(compressed.file.exists())
    }

    @Test
    fun `complete standard send leaves the gallery source untouched`() {
        val (source, sourceFile) = gallery("image/png", "photo.png", sourcePng)
        val compressed = givenCompressionSucceeds()

        val outcome = runBlocking {
            MediaSelectionRepository(context).send(
                listOf(source), mapOf(source.mediaKey() to ImageEditorFragment.Data()), SentMediaQuality.STANDARD, null
            )
        }

        consume(outcome)
        assertTrue(outcome.failures.isEmpty())
        assertArrayEquals(sourcePng, sourceFile.readBytes())
        assertTrue(compressed.file.exists())
        assertSent(outcome.result.media.single(), Uri.fromFile(compressed.file), "image/jpeg", "photo.jpg", jpeg)
    }

    @Test
    fun `complete high send keeps a pass through draft source for staging`() {
        val (source, sourceFile) = draft("image/png", "pasted.png", sourcePng)

        val outcome = runBlocking {
            MediaSelectionRepository(context).send(
                listOf(source), mapOf(source.mediaKey() to ImageEditorFragment.Data()), SentMediaQuality.HIGH, null
            )
        }

        consume(outcome)
        assertTrue(outcome.failures.isEmpty())
        assertTrue(sourceFile.exists())
        assertSent(roundTrip(outcome).media.single(), Uri.fromFile(sourceFile), "image/png", "pasted.png", sourcePng)
        verify(exactly = 0) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `complete standard fallback keeps a draft source when compression produces no new bytes`() {
        val (source, sourceFile) = draft("image/png", "pasted.png", sourcePng)

        val outcome = runBlocking {
            MediaSelectionRepository(context).send(
                listOf(source), mapOf(source.mediaKey() to ImageEditorFragment.Data()), SentMediaQuality.STANDARD, null
            )
        }

        consume(outcome)
        assertTrue(outcome.failures.isEmpty())
        assertTrue(sourceFile.exists())
        assertSent(outcome.result.media.single(), Uri.fromFile(sourceFile), "image/png", "pasted.png", sourcePng)
        verify(exactly = 1) { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `complete send does not delete a source in a similarly named draft sibling directory`() {
        val sourceFile = File(root, "draft_blobs_other/photo.png").apply {
            parentFile!!.mkdirs()
            writeBytes(sourcePng)
        }
        val source = LocalMediaBuilder.sandbox(sourceFile.path, "image/png")
        val compressed = givenCompressionSucceeds()

        val outcome = runBlocking {
            MediaSelectionRepository(context).send(
                listOf(source), mapOf(source.mediaKey() to ImageEditorFragment.Data()), SentMediaQuality.STANDARD, null
            )
        }

        consume(outcome)
        assertTrue(outcome.failures.isEmpty())
        assertArrayEquals(sourcePng, sourceFile.readBytes())
        assertTrue(compressed.file.exists())
    }

    @Test
    fun `complete send keeps a transformed source also needed by a pass through sibling`() {
        val (source, sourceFile) = draft("image/png", "pasted.png", sourcePng)
        
        val aliasFile = File(draftDir, "./pasted.png")
        val passThrough = LocalMediaBuilder.sandbox(aliasFile.path, "image/png")
        assertNotEquals(source.mediaKey(), passThrough.mediaKey())
        assertEquals(sourceFile.canonicalFile, aliasFile.canonicalFile)
        val compressed = givenCompressionSucceeds()

        val outcome = runBlocking {
            MediaSelectionRepository(context).send(
                listOf(source, passThrough), mapOf(source.mediaKey() to ImageEditorFragment.Data()), SentMediaQuality.STANDARD, null
            )
        }

        consume(outcome)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(2, outcome.result.media.size)
        assertTrue(sourceFile.exists())
        assertSent(outcome.result.media.first(), Uri.fromFile(compressed.file), "image/jpeg", "pasted.jpg", jpeg)
        assertSent(outcome.result.media.last(), Uri.fromFile(aliasFile), "image/png", "pasted.png", sourcePng)
    }

    private fun consume(outcome: MediaSendOutcome) {
        
        val activity = Robolectric.buildActivity(MediaSelectionActivity::class.java).get()
        activity.onSentWithResult(outcome)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val delivered = MediaSendActivityResult.fromData(requireNotNull(shadowOf(activity).resultIntent))
        assertSame(outcome.result, delivered)
        outcome.retirableSources.forEach { assertTrue(it.exists()) }
        
        retirementScope.testScheduler.advanceUntilIdle()
    }

    private fun assertPartial(outcome: MediaSendOutcome) {
        assertEquals(2, outcome.total)
        assertEquals(1, outcome.result.media.size)
        assertEquals(1, outcome.failures.size)
        assertEquals(2, outcome.failures.single().position)
        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, outcome.failures.single().reason)
        assertEquals("caption", outcome.result.body)
    }

    private fun assertSource(
        source: LocalMedia,
        file: File,
        mime: String,
        name: String,
        size: Long,
    ) {
        assertEquals(file.path, source.realPath)
        assertEquals(mime, source.mimeType)
        assertEquals(name, source.fileName)
        assertEquals(4000, source.width)
        assertEquals(3000, source.height)
        assertEquals(size, source.size)
    }

    private fun assertSent(sent: SendableMedia, uri: Uri, mime: String, name: String, bytes: ByteArray) {
        assertEquals(uri, sent.sendUri)
        assertEquals(mime, sent.media.mimeType)
        assertEquals(name, sent.media.fileName)
        val actual = context.contentResolver.openInputStream(sent.sendUri)!!.use { it.readBytes() }
        assertArrayEquals(bytes, actual)
    }

    private fun roundTrip(outcome: MediaSendOutcome): MediaSendActivityResult {
        val intent = Intent().putExtra(MediaSendActivityResult.EXTRA_RESULT, outcome.result)
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            MediaSendActivityResult.fromData(Intent.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
    }
}
