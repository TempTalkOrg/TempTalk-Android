package com.difft.android.chat.mediasend

import android.content.Context
import android.net.Uri
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.harness.ContentSourceHarness
import com.difft.android.test.harness.LogCapture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * T13 / T14 / T47 / T48 — staging an outgoing attachment, and what happens when it cannot be staged.
 *
 * T47 is the row that keeps voice messages working: the voice and file-pre-send call sites build
 * their URI from a bare absolute path, which leaves the scheme null, and
 * `ContentResolver.openInputStream` cannot open such a URI at all. Reaching for the resolver
 * unconditionally here would break those sends outright.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaAttachmentStagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var harness: ContentSourceHarness

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        harness = ContentSourceHarness(context)
    }

    // ---------------------------------------------------------------- T14

    /** T14 — the normal path copies every byte through to the destination. */
    @Test
    fun `readable content source is staged byte for byte`() {
        val source = Uri.parse("content://media/external/images/media/1")
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        harness.registerReadable(source, bytes)
        val dest = File(temp.newFolder("attachments"), "staged.jpg")

        val result = MediaAttachmentStager.stage(context, source, dest.path, "image/jpeg", "a.jpg")

        assertEquals(MediaAttachmentStager.StageResult.Staged, result)
        assertTrue(dest.exists())
        assertEquals(7L, dest.length())
        assertArrayEquals(bytes, dest.readBytes())
    }

    // ---------------------------------------------------------------- T13

    /**
     * T13 — an unreadable source produces no destination file and a classified failure, so the
     * caller can decline to enqueue. The old code dropped the copy helper's boolean, enqueued
     * anyway, and the upload then retried against a file that had never been created.
     */
    @Test
    fun `unreadable content source fails without leaving a destination file`() {
        val source = Uri.parse("content://media/external/images/media/1")
        harness.registerUnreadable(source, "/storage/emulated/0/DCIM/Camera/denied.jpg")
        val dest = File(temp.newFolder("denied"), "staged.jpg")

        val log = LogCapture()
        val result = log.recording {
            val staged = MediaAttachmentStager.stage(context, source, dest.path, "image/jpeg", "denied.jpg")
            awaitLine("the classifier denial line") { it.startsWith("[MediaAccess] read denied") }
            staged
        }

        val failure = (result as MediaAttachmentStager.StageResult.Failed).failure
        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, failure.reason)
        assertFalse("a partial destination file was left behind", dest.exists())
        val line = log.messages.first { it.startsWith("[MediaAccess] read denied") }
        assertEquals(MediaFailureClassifier.denialLogLine(source, "image/jpeg", failure), line)
    }

    // ---------------------------------------------------------------- T47

    /**
     * T47 — a URI with no scheme at all (the voice-message shape) must still stage. This is the one
     * shape the resolver cannot handle, so it is normalized to a direct file read.
     */
    @Test
    fun `scheme less uri from a bare path is staged from the file system`() {
        val sourceFile = temp.newFile("voice.m4a").apply { writeBytes(byteArrayOf(9, 8, 7, 6, 5)) }
        val source = Uri.parse(sourceFile.path)
        assertNull("precondition: the voice path must carry no scheme", source.scheme)
        val dest = File(temp.newFolder("voice-out"), "staged.m4a")

        val result = MediaAttachmentStager.stage(context, source, dest.path, "audio/mp4", "voice.m4a")

        assertEquals(MediaAttachmentStager.StageResult.Staged, result)
        assertEquals(5L, dest.length())
        assertArrayEquals(byteArrayOf(9, 8, 7, 6, 5), dest.readBytes())
    }

    // ---------------------------------------------------------------- T48

    /** T48 — the GIF / sticker / file-pre-send shape (`file://`) goes through the resolver as before. */
    @Test
    fun `file uri source is staged byte for byte`() {
        val sourceFile = temp.newFile("sticker.webp").apply { writeBytes(byteArrayOf(4, 4, 4)) }
        val dest = File(temp.newFolder("sticker-out"), "staged.webp")

        val result = MediaAttachmentStager.stage(
            context, Uri.fromFile(sourceFile), dest.path, "image/webp", "sticker.webp"
        )

        assertEquals(MediaAttachmentStager.StageResult.Staged, result)
        assertArrayEquals(byteArrayOf(4, 4, 4), dest.readBytes())
    }
}
