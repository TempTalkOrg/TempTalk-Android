package com.difft.android

import com.difft.android.base.utils.SizeLimitExceededException
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class ShareImportGateTest {

    
    private class StalledStream : InputStream() {
        val reading = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        var closeCount = 0
        override fun read(): Int {
            reading.countDown()
            closed.await()
            throw IOException("closed")
        }
        override fun close() {
            closeCount++
            closed.countDown()
        }
    }

    private fun begun(): Int = ShareImportGate.arrive().also { assertTrue(ShareImportGate.begin(it)) }

    @Test
    fun `a newer share closes the stream a stalled import is reading`() {
        val stalled = StalledStream()
        val ticket = begun()
        var failure: Throwable? = null
        val copier = thread { failure = runCatching { ShareImportGate.copying(ticket, stalled) { it.read() } }.exceptionOrNull() }
        assertTrue(stalled.reading.await(5, TimeUnit.SECONDS))

        val newer = begun()

        copier.join(5_000)
        assertFalse(copier.isAlive, "the stalled copy must end once a newer share starts")
        assertIs<ShareImportSupersededException>(failure)
        assertTrue(ShareImportGate.isCurrent(newer))
    }

    @Test
    fun `an older share that opens late does not replace a newer one`() {
        val older = ShareImportGate.arrive()
        val newer = ShareImportGate.arrive()
        assertTrue(ShareImportGate.begin(newer))
        assertFalse(ShareImportGate.begin(older))
        assertTrue(ShareImportGate.isCurrent(newer))
    }

    @Test
    fun `a stale ticket does not start copying`() {
        val stale = begun()
        begun()
        var copied = false
        try {
            ShareImportGate.copying(stale, "x".byteInputStream()) { copied = true }
            fail("expected the stale import to be superseded")
        } catch (e: ShareImportSupersededException) {
            assertFalse(copied)
        }
    }

    @Test
    fun `failures of the current import keep their own type`() {
        val ticket = begun()
        val error = runCatching {
            ShareImportGate.copying(ticket, "x".byteInputStream()) { throw SizeLimitExceededException(1) }
        }.exceptionOrNull()
        assertIs<SizeLimitExceededException>(error)
    }

    @Test
    fun `a finished copy is not closed by a later share`() {
        val stream = StalledStream()
        val ticket = begun()
        assertEquals("done", ShareImportGate.copying(ticket, stream) { "done" })
        begun()
        assertEquals(0, stream.closeCount)
    }
}
