package com.difft.android.chat.mediasend

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.harness.ContentSourceHarness
import com.difft.android.video.exceptions.VideoSourceException
import com.difft.android.video.videoconverter.exceptions.EncodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException

/**
 * T49-T58 — a failure becomes a category without ever guessing.
 *
 * T50 is the load-bearing row: the transcoder reports "cannot read" and "unsupported or corrupt"
 * with the same exception type and no errno, so any classification that reads the type table alone
 * tells a user whose read was denied that their file format is unsupported. The ambiguous branch has
 * to open the item for real, and T50/T51 are the two sides of that open.
 *
 * T56/T57 are the same two sides for FileNotFoundException, which the staging copy can raise for
 * either end of the copy; T58 pins the one type that is still allowed to answer without a probe.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaFailureClassifierTest {

    private lateinit var context: Context
    private lateinit var harness: ContentSourceHarness

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        harness = ContentSourceHarness(context)
    }

    // ---------------------------------------------------------------- T49

    /** T49 — an errno in the cause chain answers the question, so no probe is spent. */
    @Test
    fun `errno in the cause chain classifies without opening anything`() {
        val uri = Uri.parse("content://media/external/video/media/9")
        harness.registerReadable(uri, ByteArray(4))
        val cause = FileNotFoundException("/storage/emulated/0/Movies/a.mp4: open failed: EACCES")
            .apply { initCause(ErrnoException("open", OsConstants.EACCES)) }

        val reason = MediaFailureClassifier.classifyReason(context, uri, cause)

        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, reason)
        assertTrue("probe ran: ${harness.opens}", harness.opens.isEmpty())
    }

    // ---------------------------------------------------------------- T50

    /**
     * T50 — the ambiguous exception over an unreadable source is a read denial.
     *
     * A type table would answer MEDIA_UNSUPPORTED here and the user would be told to pick another
     * file, which cannot fix a denied read.
     */
    @Test
    fun `ambiguous failure over an unreadable source is a read denial`() {
        val uri = Uri.parse("content://media/external/video/media/10")
        harness.registerUnreadable(uri, "/storage/emulated/0/Movies/denied.mp4")
        val cause = VideoSourceException("Unable to read file", RuntimeException("setDataSource failed"))

        val reason = MediaFailureClassifier.classifyReason(context, uri, cause)

        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, reason)
        assertTrue("probe never ran", harness.opens.contains(uri))
    }

    // ---------------------------------------------------------------- T51

    /** T51 — the same exception over a readable source really is an unsupported format. */
    @Test
    fun `ambiguous failure over a readable source is an unsupported format`() {
        val uri = Uri.parse("content://media/external/video/media/11")
        harness.registerReadable(uri, ByteArray(16))
        val cause = VideoSourceException("Unable to read file", RuntimeException("setDataSource failed"))

        val failure = MediaFailureClassifier.classifyAndLog(
            context, uri, "video/mp4", position = 1, displayName = null, cause = cause
        )

        assertEquals(MediaFailureReason.MEDIA_UNSUPPORTED, failure.reason)
        assertNull(failure.denialKind)
    }

    // ---------------------------------------------------------------- T52

    /** T52 — the out-of-space category has a real producer: a write errno. */
    @Test
    fun `write errno for a full disk classifies as out of space`() {
        val uri = Uri.parse("content://media/external/images/media/12")
        val cause = IOException(ErrnoException("write", OsConstants.ENOSPC))

        val reason = MediaFailureClassifier.classifyReason(context, uri, cause)

        assertEquals(MediaFailureReason.OUT_OF_SPACE, reason)
        assertFalse(reason.retryable)
    }

    // ---------------------------------------------------------------- T53

    /** T53 — a readable source that failed to encode is a processing failure, and retryable. */
    @Test
    fun `encoding failure over a readable source is a transform failure`() {
        val uri = Uri.parse("content://media/external/video/media/13")
        harness.registerReadable(uri, ByteArray(16))

        val reason = MediaFailureClassifier.classifyReason(context, uri, EncodingException("codec"))

        assertEquals(MediaFailureReason.TRANSFORM_FAILED, reason)
        assertTrue(reason.retryable)
    }

    // ---------------------------------------------------------------- T54

    /**
     * T54 — the no-item-context path is main-thread safe. It takes no Context at all, so it cannot
     * reach a resolver; the empty [ContentSourceHarness.opens] pins that structurally.
     */
    @Test
    fun `throwable without item context is unknown and touches no resolver`() {
        val uri = Uri.parse("content://media/external/images/media/14")
        harness.registerReadable(uri, ByteArray(4))

        val failure = MediaFailureClassifier.classifyThrown(IllegalStateException("boom"))

        assertEquals(MediaFailureReason.UNKNOWN, failure.reason)
        assertEquals(MediaFailureClassifier.NO_POSITION, failure.position)
        assertNull(failure.denialKind)
        assertTrue("resolver was touched: ${harness.opens}", harness.opens.isEmpty())
    }

    // ---------------------------------------------------------------- T55

    /** T55 — a cause cycle must terminate; the depth cap is what guarantees it. */
    @Test
    fun `self referencing cause chain terminates within the depth cap`() {
        val outer = RuntimeException("outer")
        val inner = IllegalStateException("inner")
        outer.initCause(inner)
        inner.initCause(outer)

        val chain = MediaFailureClassifier.causeChain(outer).toList()

        assertTrue("chain length ${chain.size}", chain.size <= MAX_CAUSE_DEPTH)
        // The classifier walks the same sequence, so a non-terminating chain would hang here too.
        assertEquals(MediaFailureReason.UNKNOWN, MediaFailureClassifier.classifyThrown(outer).reason)
    }

    // ---------------------------------------------------------------- T56

    /**
     * T56 — a FileNotFoundException over a READABLE source is about the write side.
     *
     * The staging copy opens a destination file too, and its failure arrives as the same type as a
     * denied source read. Blaming the source there tells the user to pick another file — and, worse,
     * withholds the retry entry that would actually work once the parent dir exists.
     *
     * EISDIR is deliberate: the errno table only claims the three access errnos, so this row reaches
     * the probe rather than being answered by errno alone.
     */
    @Test
    fun `file not found over a readable source is a transform failure`() {
        val uri = Uri.parse("content://media/external/video/media/15")
        harness.registerReadable(uri, ByteArray(16))
        val cause = FileNotFoundException("/data/user/0/pkg/files/att/dest.mp4: open failed: EISDIR")
            .apply { initCause(ErrnoException("open", OsConstants.EISDIR)) }

        val failure = MediaFailureClassifier.classifyAndLog(
            context, uri, "video/mp4", position = 2, displayName = null, cause = cause
        )

        assertEquals(MediaFailureReason.TRANSFORM_FAILED, failure.reason)
        assertTrue(failure.reason.retryable)
        assertNull(failure.denialKind)
        assertTrue("probe never ran", harness.opens.contains(uri))
    }

    // ---------------------------------------------------------------- T57

    /** T57 — the same type over an UNREADABLE source is still a read denial; the probe decides. */
    @Test
    fun `file not found over an unreadable source is a read denial`() {
        val uri = Uri.parse("content://media/external/video/media/16")
        harness.registerUnreadable(uri, "/storage/emulated/0/Movies/gone.mp4")
        // No errno cause: this row must be answered by the probe, not by the errno table.
        val cause = FileNotFoundException("/storage/emulated/0/Movies/gone.mp4: open failed")

        val reason = MediaFailureClassifier.classifyReason(context, uri, cause)

        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, reason)
        assertFalse(reason.retryable)
        assertTrue("probe never ran", harness.opens.contains(uri))
    }

    // ---------------------------------------------------------------- T58

    /**
     * T58 — SecurityException keeps its no-probe verdict, and the reason is that the probe would
     * open the same content URI through the same resolver, so it cannot disagree.
     */
    @Test
    fun `revoked grant is a read denial without spending a probe`() {
        val uri = Uri.parse("content://com.example.provider/doc/17")
        harness.registerReadable(uri, ByteArray(4))

        val reason = MediaFailureClassifier.classifyReason(context, uri, SecurityException("revoked"))

        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, reason)
        assertTrue("probe ran: ${harness.opens}", harness.opens.isEmpty())
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}
