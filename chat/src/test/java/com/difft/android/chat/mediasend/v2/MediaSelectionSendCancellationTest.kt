package com.difft.android.chat.mediasend.v2

import com.difft.android.base.media.ImageCompressor
import com.difft.android.chat.mediasend.ImageTransformTestBase
import com.difft.android.chat.mediasend.MediaKey
import com.difft.android.chat.mediasend.VideoTrimTransform
import com.difft.android.chat.mediasend.mediaKey
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.mediasend.v2.videos.VideoTrimData
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.scribbles.ImageEditorFragment
import com.difft.android.selector.entity.LocalMedia
import io.mockk.every
import io.mockk.mockkConstructor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSelectionSendCancellationTest : ImageTransformTestBase() {

    @Test
    fun `cancellation during image compression preserves an unconsumed draft source`() {
        val (source, sourceFile) = draft("image/png", "pasted.png", png)
        val compressed = givenCompressionSucceeds()
        val gate = TransformGate()
        every { ImageCompressor.compress(any(), any(), any(), any(), any(), any(), any()) } answers {
            gate.block()
            compressed
        }

        assertCancelledSendPreservesSource(
            source, sourceFile, mapOf(source.mediaKey() to ImageEditorFragment.Data()), compressed.file, png, gate
        )
    }

    @Test
    fun `cancellation during video transformation preserves an unconsumed draft source`() {
        val bytes = byteArrayOf(0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70)
        val (source, sourceFile) = draft("video/mp4", "camera.mp4", bytes)
        val output = File(draftDir, "transcoded.mp4").apply { writeBytes(bytes) }
        val gate = TransformGate()
        
        mockkConstructor(VideoTrimTransform::class)
        every { anyConstructed<VideoTrimTransform>().transform(any(), any()) } answers {
            gate.block()
            secondArg<LocalMedia>().apply { realPath = output.path }
        }

        assertCancelledSendPreservesSource(
            source, sourceFile, mapOf(source.mediaKey() to VideoTrimData()), output, bytes, gate
        )
    }

    private fun assertCancelledSendPreservesSource(
        source: LocalMedia,
        sourceFile: File,
        states: Map<MediaKey, Any>,
        output: File,
        bytes: ByteArray,
        gate: TransformGate,
    ) {
        val repository = MediaSelectionRepository(context)
        val sourceKey = source.mediaKey()
        val delivered = AtomicBoolean(false)
        val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val request = senderScope.async {
            repository.send(listOf(source), states, SentMediaQuality.STANDARD, "caption")
                .also { delivered.set(true) }
        }
        try {
            assertTrue("transform never started", gate.entered.await(10, TimeUnit.SECONDS))
            
            request.cancel()
            gate.release.countDown()
            runBlocking { withTimeout(10_000) { request.join() } }

            assertTrue(request.isCancelled)
            assertTrue("blocking transform did not finish", gate.finished.get())
            assertFalse("cancelled result reached its consumer", delivered.get())
            assertArrayEquals(bytes, sourceFile.readBytes())
            assertTrue(output.exists())
            assertEquals(sourceFile.path, source.realPath)
            assertEquals(sourceKey, source.mediaKey())

            
            val retry = runBlocking { repository.send(listOf(source), emptyMap(), SentMediaQuality.HIGH, "caption") }
            assertTrue(retry.failures.isEmpty())
            assertEquals(source.readableUri(), retry.result.media.single().sendUri)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(retry.result.media.single().sendUri)!!.use { it.readBytes() })
        } finally {
            gate.release.countDown()
            try {
                runBlocking { withTimeout(10_000) { request.cancelAndJoin() } }
            } finally {
                senderScope.cancel()
            }
        }
    }

    private class TransformGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)

        fun block() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "transform gate timed out" }
            finished.set(true)
        }
    }
}
