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

    

    





    @Test
    fun `ambiguous failure over an unreadable source is a read denial`() {
        val uri = Uri.parse("content://media/external/video/media/10")
        harness.registerUnreadable(uri, "/storage/emulated/0/Movies/denied.mp4")
        val cause = VideoSourceException("Unable to read file", RuntimeException("setDataSource failed"))

        val reason = MediaFailureClassifier.classifyReason(context, uri, cause)

        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, reason)
        assertTrue("probe never ran", harness.opens.contains(uri))
    }

    

    
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

    

    
    @Test
    fun `write errno for a full disk classifies as out of space`() {
        val uri = Uri.parse("content://media/external/images/media/12")
        val cause = IOException(ErrnoException("write", OsConstants.ENOSPC))

        val reason = MediaFailureClassifier.classifyReason(context, uri, cause)

        assertEquals(MediaFailureReason.OUT_OF_SPACE, reason)
        assertFalse(reason.retryable)
    }

    

    
    @Test
    fun `encoding failure over a readable source is a transform failure`() {
        val uri = Uri.parse("content://media/external/video/media/13")
        harness.registerReadable(uri, ByteArray(16))

        val reason = MediaFailureClassifier.classifyReason(context, uri, EncodingException("codec"))

        assertEquals(MediaFailureReason.TRANSFORM_FAILED, reason)
        assertTrue(reason.retryable)
    }

    

    



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

    

    
    @Test
    fun `self referencing cause chain terminates within the depth cap`() {
        val outer = RuntimeException("outer")
        val inner = IllegalStateException("inner")
        outer.initCause(inner)
        inner.initCause(outer)

        val chain = MediaFailureClassifier.causeChain(outer).toList()

        assertTrue("chain length ${chain.size}", chain.size <= MAX_CAUSE_DEPTH)
        
        assertEquals(MediaFailureReason.UNKNOWN, MediaFailureClassifier.classifyThrown(outer).reason)
    }

    

    









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

    

    
    @Test
    fun `file not found over an unreadable source is a read denial`() {
        val uri = Uri.parse("content://media/external/video/media/16")
        harness.registerUnreadable(uri, "/storage/emulated/0/Movies/gone.mp4")
        
        val cause = FileNotFoundException("/storage/emulated/0/Movies/gone.mp4: open failed")

        val reason = MediaFailureClassifier.classifyReason(context, uri, cause)

        assertEquals(MediaFailureReason.SOURCE_UNREADABLE, reason)
        assertFalse(reason.retryable)
        assertTrue("probe never ran", harness.opens.contains(uri))
    }

    

    



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
