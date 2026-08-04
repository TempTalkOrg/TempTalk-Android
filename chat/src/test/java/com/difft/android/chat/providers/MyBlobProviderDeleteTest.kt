package com.difft.android.chat.providers

import android.net.Uri
import android.os.Environment
import com.difft.android.base.utils.AppPrivateStorage
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.harness.AppPrivateRoots
import com.difft.android.test.harness.LogCapture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * T82–T86 — the ownership gate on [MyBlobProvider.delete].
 *
 * Private fixture paths are derived from [AppPrivateRoots] rather than from `context.filesDir`:
 * production caches the roots for the process lifetime, and Robolectric gives every test method its
 * own data dir, so a path built from `filesDir` would be judged private or not depending on which
 * method happened to initialise the cache first.
 *
 * Run: :chat:testDebugUnitTest --tests "*MyBlobProviderDeleteTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MyBlobProviderDeleteTest {

    private lateinit var provider: MyBlobProvider

    @Before
    fun setUp() {
        provider = MyBlobProvider.getInstance()
    }

    // ---------------------------------------------------------------- T82

    /** T82 — a blob inside app-private storage is deleted and the result says so. */
    @Test
    fun `a blob inside app private storage is deleted`() {
        val blob = privateFile("draft_blobs/x.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(blob.exists())

        val deleted = provider.delete(Uri.fromFile(blob))

        assertTrue("delete reported failure for an owned blob", deleted)
        assertFalse(blob.exists())
    }

    // ---------------------------------------------------------------- T83

    /**
     * T83 — a shared-storage path is refused, not attempted. The file is left in place by
     * construction: nothing on this path reaches `File.delete()`.
     */
    @Test
    fun `a shared storage path is refused and the file survives`() {
        val gallery = sharedFile("DCIM/a.jpg").apply { writeBytes(byteArrayOf(9)) }

        val capture = LogCapture()
        val deleted = capture.recording {
            val result = provider.delete(Uri.fromFile(gallery))
            awaitLine("refusal") { it.contains("[Blob] delete refused: path is outside app-private storage") }
            assertFalse(messages.toString(), messages.any { it.contains(gallery.name) })
            result
        }

        assertFalse("a shared-storage file must never be reported as deleted", deleted)
        assertTrue("the user's own gallery file was removed", gallery.exists())
    }

    // ---------------------------------------------------------------- T84

    /**
     * T84 — a content URI is stopped by the scheme gate. Its path is a pseudo path
     * (`/external/images/media/123`) that must never be handed to `File`, so the refusal has to
     * happen before the ownership check, not inside it.
     */
    @Test
    fun `a content uri is refused by the scheme gate`() {
        val capture = LogCapture()
        val deleted = capture.recording {
            val result = provider.delete(Uri.parse("content://media/external/images/media/123"))
            awaitLine("scheme refusal") { it.contains("[Blob] delete refused: unsupported scheme=content") }
            // Exiting at the scheme gate proves File("/external/images/media/123") was never built:
            // the ownership check, the only other refusal, never got the chance to run.
            assertFalse(messages.toString(), messages.any { it.contains("outside app-private storage") })
            result
        }

        assertFalse(deleted)
    }

    // ---------------------------------------------------------------- T85

    /** T85 — URIs carrying no path are refused instead of dereferencing a null path. */
    @Test
    fun `uris without a path are refused without throwing`() {
        val capture = LogCapture()

        val results = capture.recording {
            val both = listOf(provider.delete(Uri.EMPTY), provider.delete(Uri.parse("file://")))
            awaitLine("missing path") { it.contains("[Blob] delete refused: uri has no path") }
            both
        }

        assertFalse(results[0])
        assertFalse(results[1])
    }

    // ---------------------------------------------------------------- T86

    /**
     * T86 — an owned path that is already gone reports false and says so. The old code dropped this
     * result, which is why a delete that never worked looked exactly like one that did.
     */
    @Test
    fun `a missing blob inside app private storage reports failure`() {
        val absent = File(AppPrivateRoots.first(), "draft_blobs/never_written.jpg")
        assertFalse(absent.exists())

        val capture = LogCapture()
        val deleted = capture.recording {
            val result = provider.delete(Uri.fromFile(absent))
            awaitLine("failed delete") { it.contains("[Blob] delete returned false") }
            result
        }

        assertFalse(deleted)
    }

    private fun privateFile(relative: String): File =
        File(AppPrivateRoots.first(), relative).also { it.parentFile?.mkdirs() }

    /**
     * A path that is genuinely outside every app-private root. The precondition is asserted rather
     * than assumed so this row can never pass by accidentally pointing back inside the sandbox.
     */
    private fun sharedFile(relative: String): File {
        val file = File(Environment.getExternalStorageDirectory(), relative)
        file.parentFile?.mkdirs()
        assertFalse(
            "fixture path is app-private, so this row would assert nothing: $file",
            AppPrivateStorage.isAppPrivate(file.absolutePath)
        )
        return file
    }
}
