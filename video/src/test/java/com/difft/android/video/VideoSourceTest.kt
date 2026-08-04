package com.difft.android.video

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T29-T32 — [VideoSource] dispatch.
 *
 * Robolectric because every row touches `Uri` / `ContentResolver`; on the plain JVM `Uri.parse`
 * returns a stub and the scheme assertions would pass vacuously.
 *
 * Run: :video:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
class VideoSourceTest {

    private lateinit var context: Context
    private val temps = mutableListOf<File>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        // Both are static injection tables: openFileCalls accumulates across tests and the
        // registered provider table leaks into the next one.
        FakeFileProvider.reset()
        ShadowContentResolver.reset()
        temps.forEach { it.delete() }
        temps.clear()
    }

    private fun tempVideo(bytes: Int): File =
        File.createTempFile("videosource", ".mp4").also {
            it.writeBytes(ByteArray(bytes))
            temps += it
        }

    /**
     * T29 — a file:// URI keeps the native file path and must not reach ContentResolver at all.
     * The context handed in fails loudly if the resolver is touched, so this is a structural
     * assertion rather than a call count.
     */
    @Test
    fun `file uri resolves to the native file input without touching ContentResolver`() {
        val file = tempVideo(1_024)
        val failingResolverContext = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver =
                throw AssertionError("file:// source must not go through ContentResolver")
        }

        val source = VideoSource.of(failingResolverContext, Uri.fromFile(file))

        assertTrue(source.mediaInput is FileMediaInput)
        assertEquals("file", source.scheme)
        assertEquals(file.length(), source.sizeBytes)
    }

    /**
     * T30 — a content:// URI is sized through the descriptor the provider hands back, opened
     * exactly once. Pins that the size comes from a real `statSize` and not from File.length().
     */
    @Test
    fun `content uri is sized from the provider file descriptor`() {
        val file = tempVideo(1_500_000)
        val uri = Uri.parse("content://${FakeFileProvider.AUTHORITY}/video/1")
        FakeFileProvider.files[uri] = file
        Robolectric.setupContentProvider(FakeFileProvider::class.java, FakeFileProvider.AUTHORITY)

        val source = VideoSource.of(context, uri)

        assertTrue(source.mediaInput is UriMediaInput)
        assertEquals("content", source.scheme)
        assertEquals(1_500_000L, source.sizeBytes)
        assertEquals(1, FakeFileProvider.openFileCalls)
    }

    /**
     * T31 — a failed open reports an unknown size instead of throwing, and never 0. The throw
     * decision belongs to the caller that actually needs the bytes.
     */
    @Test
    fun `failed descriptor open yields unknown size without throwing`() {
        val uri = Uri.parse("content://${FakeFileProvider.AUTHORITY}/video/missing")
        Robolectric.setupContentProvider(FakeFileProvider::class.java, FakeFileProvider.AUTHORITY)
        val captured = CopyOnWriteArrayList<String>()
        val logged = CountDownLatch(1)
        // A planted tree, not a mocked L: L's warn entry point is a @JvmStatic bridge, so call
        // sites are dispatched statically and an object mock never sees them.
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                captured += message
                logged.countDown()
            }
        }
        L.plant(tree)
        try {
            val source = VideoSource.of(context, uri)

            assertEquals(VideoSource.UNKNOWN_SIZE, source.sizeBytes)
            assertTrue(source.mediaInput is UriMediaInput)
            // L dispatches onto its own logging thread; bounded wait, never an unbounded sleep.
            assertTrue("no log line emitted", logged.await(10, TimeUnit.SECONDS))
            val message = captured.single()
            assertTrue(message, message.contains("[MediaAccess]"))
            assertTrue(message, message.contains("scheme=content"))
        } finally {
            L.uproot(tree)
        }
    }

    /**
     * T32 — the File entry point and a file:// URI produce the same source. This is what makes
     * removing the old File constructor safe: the native path did not move, only its name.
     */
    @Test
    fun `File entry point and file uri produce equivalent sources`() {
        val file = tempVideo(2_048)

        val fromFile = VideoSource.of(file)
        val fromUri = VideoSource.of(context, Uri.fromFile(file))

        assertEquals(fromFile.mediaInput.javaClass, fromUri.mediaInput.javaClass)
        assertEquals(fromFile.sizeBytes, fromUri.sizeBytes)
        assertEquals(fromFile.scheme, fromUri.scheme)
        assertTrue(fromFile.mediaInput.hasSameInput(fromUri.mediaInput))
    }
}
