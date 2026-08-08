package com.difft.android.chat.mediasend

import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T1-T3, T23 — [readableUri] branch coverage.
 *
 * Robolectric (not plain JUnit) because every row touches `Uri`: on the plain JVM `Uri.parse`
 * returns a stub and the scheme assertions would pass vacuously.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaUriResolverTest {

    /** T1 — gallery item: the MediaStore content URI in `path` wins over the bare `realPath`. */
    @Test
    fun `content path yields the MediaStore content URI`() {
        val media = LocalMediaBuilder.gallery(
            id = 12345L,
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/12345",
            realPath = "/storage/emulated/0/Movies/a.mp4",
        )

        val uri = media.readableUri()

        assertEquals(Uri.parse("content://media/external/video/media/12345"), uri)
        assertEquals("content", uri.scheme)
    }

    /**
     * T2 — SAF / file-attachment item: empty `path` falls back to a file:// URI built from
     * `realPath`. Asserts the full string, not just the scheme, so a `Uri.parse(realPath)`
     * regression (scheme-less relative URI) fails here.
     */
    @Test
    fun `blank content path yields a file URI for the sandbox path`() {
        val media = LocalMediaBuilder.sandbox(realPath = "/data/user/0/pkg/files/attachment/x.jpg")

        val uri = media.readableUri()

        assertEquals(Uri.parse("file:///data/user/0/pkg/files/attachment/x.jpg"), uri)
        assertEquals("file", uri.scheme)
        assertEquals("file:///data/user/0/pkg/files/attachment/x.jpg", uri.toString())
    }

    /** T3 — API 26-28 shape: a bare absolute path in `path` is not a content URI. */
    @Test
    fun `bare absolute path in path field yields a file URI`() {
        val media = LocalMediaBuilder.legacyBarePath(barePath = "/storage/emulated/0/DCIM/a.jpg")

        val uri = media.readableUri()

        assertEquals("file", uri.scheme)
        assertEquals("file:///storage/emulated/0/DCIM/a.jpg", uri.toString())
    }

    /**
     * T23 — both fields blank: unreachable for the three production entry points, but pinned so
     * the diagnostic branch is not "tidied away". Must not throw, and the log line must carry no
     * path plaintext — asserted against the two absolute-path roots media can live under, since
     * a bare '/' check would trip on the mime type itself.
     */
    @Test
    fun `blank path and realPath yield EMPTY and log without path plaintext`() {
        val captured = CopyOnWriteArrayList<String>()
        val logged = CountDownLatch(1)
        // A real planted tree rather than a mocked L: L's warn entry point is a @JvmStatic bridge,
        // so a call site is dispatched statically and an object mock never sees it.
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                captured += message
                logged.countDown()
            }
        }
        L.plant(tree)
        try {
            val media = LocalMedia(path = "", realPath = "", id = 77L, mimeType = "image/png")

            val uri = media.readableUri()

            assertEquals(Uri.EMPTY, uri)
            // L dispatches onto its own logging thread; bounded wait, never an unbounded sleep.
            assertTrue("no log line emitted", logged.await(10, TimeUnit.SECONDS))
            val message = captured.single()
            assertTrue(message, message.contains("[MediaAccess]"))
            assertTrue(message, message.contains("id=77"))
            assertTrue(message, message.contains("mime=image/png"))
            assertFalse(message, message.contains("/data"))
            assertFalse(message, message.contains("/storage"))
        } finally {
            L.uproot(tree)
        }
    }

    /**
     * T28 — the focus check inside `VideoEditorFragment` compares the key derived from its ARG_URI
     * against the key derived from the focused media, so the two derivations must agree.
     *
     * If only the pager had been migrated, the fragment's key (content://) would never equal a key
     * built from the bare `realPath` (file://): `currentlyFocused` would be permanently false, the
     * timeline would never bind and playback would never start — with no error anywhere. The second
     * assertion is that stale shape, kept as the negative control.
     *
     * Written against the pure derivation rather than a launched fragment: the fragment owns a
     * `VideoPlayer` that is not usable under Robolectric.
     */
    @Test
    fun `focused key from the pager argument equals the media key`() {
        val media = LocalMediaBuilder.gallery(
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/9",
            realPath = "/storage/emulated/0/Movies/v.mp4",
        )
        // The pager adapter puts exactly this URI into ARG_URI; the fragment wraps it in a MediaKey.
        val argUri = media.readableUri()

        assertEquals(media.mediaKey(), MediaKey(argUri))
        assertNotEquals(media.mediaKey(), MediaKey(Uri.parse(media.realPath)))
    }
}
