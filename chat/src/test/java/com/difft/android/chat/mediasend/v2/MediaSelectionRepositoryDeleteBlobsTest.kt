package com.difft.android.chat.mediasend.v2

import android.content.Context
import android.os.Environment
import com.difft.android.base.utils.AppPrivateStorage
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.test.harness.AppPrivateRoots
import com.difft.android.test.harness.LogCapture
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * T18 / T87 / T88 / T89 — blob cleanup on deselection.
 *
 * T18 is also the automated guard against normalizing `deleteBlobs` through `readableUri()`: a
 * gallery item would then arrive as a content URI, be refused by the scheme gate, and the sandbox
 * item's `deleted=1` would drop to `deleted=0`.
 *
 * Private fixture paths come from [AppPrivateRoots] because production caches the roots for the
 * process lifetime while Robolectric hands every method its own data dir.
 *
 * Run: :chat:testDebugUnitTest --tests "*MediaSelectionRepositoryDeleteBlobsTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSelectionRepositoryDeleteBlobsTest {

    private lateinit var context: Context
    private lateinit var repository: MediaSelectionRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = MediaSelectionRepository(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---------------------------------------------------------------- T18

    /**
     * T18 — deselecting a gallery item never touches the user's own file, while the sandbox blob
     * behind an edited item is cleaned up. The refusal is structural: the shared path is rejected
     * before `File.delete()`, so it is not "a delete that happens to fail".
     */
    @Test
    fun `only sandbox blobs are deleted and the summary counts both`() {
        val gallery = LocalMediaBuilder.gallery(realPath = sharedFile("DCIM/a.jpg").absolutePath)
        val blob = privateFile("draft_blobs/x.jpg")
        val edited = LocalMediaBuilder.sandbox(realPath = blob.absolutePath)

        val capture = LogCapture()
        capture.recording {
            repository.deleteBlobs(listOf(gallery, edited))
            awaitLine("summary") { it.contains("[MediaSend] deleteBlobs total=2 deleted=1") }
            awaitLine("refusal") { it.contains("[Blob] delete refused: path is outside app-private storage") }
        }

        assertTrue("the gallery original was removed", File(gallery.realPath).exists())
        assertFalse("the sandbox blob was left behind", blob.exists())
    }

    // ---------------------------------------------------------------- T87

    /**
     * T87 — a blank `realPath` (the field's default) is skipped rather than turned into a URI over
     * an empty path.
     */
    @Test
    fun `an item without a real path is skipped entirely`() {
        val blank = LocalMediaBuilder.sandbox(realPath = "")

        val capture = LogCapture()
        capture.recording {
            repository.deleteBlobs(listOf(blank))
            awaitLine("summary") { it.contains("[MediaSend] deleteBlobs total=1 deleted=0") }
            // No provider line at all: the gate was never consulted because delete was never called.
            // Safe to assert as an absence — the summary is emitted last, and L delivers in order.
            assertFalse(messages.toString(), messages.any { it.contains("[Blob]") })
        }
    }

    // ---------------------------------------------------------------- T88

    /** T88 — `cleanUp` is the same operation, so it inherits the gate and the summary. */
    @Test
    fun `cleanUp behaves exactly like deleteBlobs`() {
        val gallery = LocalMediaBuilder.gallery(realPath = sharedFile("DCIM/b.jpg").absolutePath)
        val blob = privateFile("draft_blobs/y.jpg")
        val edited = LocalMediaBuilder.sandbox(realPath = blob.absolutePath)

        val capture = LogCapture()
        capture.recording {
            repository.cleanUp(listOf(gallery, edited))
            awaitLine("summary") { it.contains("[MediaSend] deleteBlobs total=2 deleted=1") }
        }

        assertTrue(File(gallery.realPath).exists())
        assertFalse(blob.exists())
    }

    // ---------------------------------------------------------------- T89

    /**
     * T89 — removal hands the deletion to a worker. The gate canonicalizes paths, which is real IO,
     * and `removeMedia` runs on the UI thread of the review screen.
     *
     * The contract is asserted as "not the calling thread" rather than "not yet called after
     * return": the coroutine runs on the real IO dispatcher, so any check for "hasn't started yet"
     * would be a race. If the call were still inline, the recorded thread would be this one.
     */
    @Test
    fun `removeMedia deletes blobs off the calling thread`() {
        val media = LocalMediaBuilder.gallery()
        val ran = CountDownLatch(1)
        val ranOn = AtomicReference<String>()
        val mockRepository = mockk<MediaSelectionRepository>(relaxed = true)
        every { mockRepository.deleteBlobs(any()) } answers {
            ranOn.set(Thread.currentThread().name)
            ran.countDown()
        }
        val callerThread = Thread.currentThread().name
        val viewModel = MediaSelectionViewModel(listOf(media), mockRepository)

        viewModel.removeMedia(media)

        assertTrue("deleteBlobs never ran", ran.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertNotEquals("blob deletion still runs on the caller's thread", callerThread, ranOn.get())
        verify(exactly = 1) { mockRepository.deleteBlobs(listOf(media)) }
    }

    private fun privateFile(relative: String): File =
        File(AppPrivateRoots.first(), relative).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

    /**
     * A path genuinely outside every app-private root. The precondition is asserted rather than
     * assumed so these rows can never pass by pointing back inside the sandbox.
     */
    private fun sharedFile(relative: String): File {
        val file = File(Environment.getExternalStorageDirectory(), relative)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(9))
        assertFalse(
            "fixture path is app-private, so this row would assert nothing: $file",
            AppPrivateStorage.isAppPrivate(file.absolutePath)
        )
        return file
    }

    private companion object {
        const val AWAIT_SECONDS = 10L
    }
}
