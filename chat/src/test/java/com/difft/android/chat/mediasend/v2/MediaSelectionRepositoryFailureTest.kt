package com.difft.android.chat.mediasend.v2

import android.content.Context
import android.net.Uri
import com.difft.android.chat.mediasend.MediaFailureReason
import com.difft.android.chat.mediasend.MediaTransform
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.ContentSourceHarness
import com.difft.android.video.exceptions.VideoSourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T6 / T7 / T62 / T63 — one bad item must cost exactly that item.
 *
 * T62 is the row that moves the reported symptom forward in time: the default path (full quality,
 * nothing edited) reads nothing at all before the attachment copy, so without a probe at the
 * transform boundary an unreadable gallery item is only discovered after the review screen — and its
 * caption — is gone. T63 is the other half of that bargain: items whose bytes were just written are
 * never probed, so the success path pays nothing.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSelectionRepositoryFailureTest {

    private lateinit var context: Context
    private lateinit var repository: MediaSelectionRepository
    private lateinit var harness: ContentSourceHarness

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = MediaSelectionRepository(context)
        harness = ContentSourceHarness(context)
    }

    /** Fails the way the transcoder does for an unreadable source: same type, no errno. */
    private class FailingTransform : MediaTransform {
        override fun transform(context: Context, media: LocalMedia): LocalMedia =
            throw VideoSourceException("Unable to read file", RuntimeException("setDataSource failed"))
    }

    private class RewritingTransform(private val outputPath: String) : MediaTransform {
        override fun transform(context: Context, media: LocalMedia): LocalMedia {
            media.realPath = outputPath
            return media
        }
    }

    // ---------------------------------------------------------------- T6

    /** T6 — the surviving items keep their order, and the batch is not discarded. */
    @Test
    fun `a failing item is isolated and the rest are still sendable`() {
        val first = readableImage(id = 1L)
        val video = LocalMediaBuilder.gallery(
            id = 2L,
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/2",
            realPath = "/storage/emulated/0/Movies/b.mp4",
        )
        harness.registerUnreadable(Uri.parse(video.path), "/storage/emulated/0/Movies/b.mp4")
        val third = readableImage(id = 3L)

        val result = repository.transformMediaSync(
            context, listOf(first, video, third), mapOf(video to FailingTransform())
        )

        assertEquals(listOf(first, third), result.updated.keys.toList())
        assertEquals(1, result.failures.size)
        val failure = result.failures.single()
        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, failure.reason)
        assertEquals(2, failure.position)
        assertNotNull(failure.denialKind)
    }

    // ---------------------------------------------------------------- T7

    /** T7 — untouched items are passed through as the very same instances. */
    @Test
    fun `untouched items pass through unchanged`() {
        val items = listOf(readableImage(id = 4L), readableImage(id = 5L), readableImage(id = 6L))

        val result = repository.transformMediaSync(context, items, emptyMap())

        assertTrue(result.failures.isEmpty())
        assertEquals(items, result.updated.keys.toList())
        items.forEach { assertSame(it, result.updated.getValue(it).media) }
    }

    // ---------------------------------------------------------------- T62

    /** T62 — an unreadable pass-through item is caught here, not after the screen is gone. */
    @Test
    fun `unreadable pass through item fails at the transform boundary`() {
        val media = LocalMediaBuilder.gallery(
            id = 7L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/7",
            realPath = "/storage/emulated/0/DCIM/Camera/img_7.jpg",
        )
        harness.registerUnreadable(Uri.parse(media.path), "/storage/emulated/0/DCIM/Camera/img_7.jpg")

        val result = repository.transformMediaSync(context, listOf(media), emptyMap())

        assertTrue(result.updated.isEmpty())
        assertEquals(1, result.failures.size)
        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, result.failures.single().reason)
        assertEquals(1, result.failures.single().position)
    }

    // ---------------------------------------------------------------- T63

    /** T63 — bytes that were just written are not probed, however unreadable the source became. */
    @Test
    fun `an item that produced new bytes is never probed`() {
        val media = LocalMediaBuilder.gallery(
            id = 8L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/8",
            realPath = "/storage/emulated/0/DCIM/Camera/img_8.jpg",
        )
        val source = Uri.parse(media.path)
        harness.registerUnreadable(source, "/storage/emulated/0/DCIM/Camera/img_8.jpg")
        val output = "/data/user/0/com.difft.android/files/draft_blobs/out.jpg"

        val result = repository.transformMediaSync(
            context, listOf(media), mapOf(media to RewritingTransform(output))
        )

        assertTrue(result.failures.isEmpty())
        assertEquals(1, result.updated.size)
        assertFalse("the produced item was probed: ${harness.opens}", harness.opens.contains(source))
    }

    private fun readableImage(id: Long): LocalMedia {
        val media = LocalMediaBuilder.gallery(
            id = id,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/$id",
            realPath = "/storage/emulated/0/DCIM/Camera/img_$id.jpg",
        )
        harness.registerReadable(Uri.parse(media.path), ByteArray(8) { it.toByte() })
        return media
    }
}
