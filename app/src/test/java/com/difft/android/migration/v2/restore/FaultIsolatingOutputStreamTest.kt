package com.difft.android.migration.v2.restore

import com.difft.android.migration.model.MigrationException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T31/T32: every single-attachment IO error stays isolated, except running out of space, which
 * aborts the whole import rather than letting a 200 report a migration the receiver did not get.
 */
class FaultIsolatingOutputStreamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Fails on the write that carries byte value 9 with [failure], and rejects everything after. */
    private class FlakyOutput(private val failure: String = "input/output error") : OutputStream() {
        val written = ArrayList<Int>()
        var closed = false
        override fun write(b: Int) {
            if (b == 9) throw IOException(failure)
            written.add(b)
        }
        override fun close() { closed = true }
    }

    @Test
    fun `first write failure deletes the partial file, reports once, and swallows the rest`() {
        val file = tmp.newFile("partial.bin")
        val delegate = FlakyOutput()
        var failures = 0
        val out = FaultIsolatingOutputStream(file, delegate) { failures++ }

        out.write(1)
        out.write(9)
        out.write(2)
        out.flush()
        out.close()

        assertEquals(listOf(1), delegate.written, "nothing is written after the failure")
        assertTrue(!file.exists(), "the partial file is removed")
        assertEquals(1, failures, "one failure report per attachment")
        assertTrue(delegate.closed)
        assertTrue(out.failed)
    }

    @Test
    fun `an out-of-space write escalates instead of being isolated`() {
        val file = tmp.newFile("enospc.bin")
        val delegate = FlakyOutput("No space left on device")
        var failures = 0
        val out = FaultIsolatingOutputStream(file, delegate) { failures++ }

        out.write(1)
        assertFailsWith<MigrationException.OutOfSpace> { out.write(9) }

        assertTrue(!file.exists(), "the partial file is still removed")
        assertTrue(delegate.closed)
        assertEquals(0, failures, "out of space is not one attachment's problem, so it is not reported as one")
    }

    @Test
    fun `a clean write keeps the file and reports nothing`() {
        val file = tmp.newFile("ok.bin")
        val delegate = FlakyOutput()
        var failures = 0
        FaultIsolatingOutputStream(file, delegate) { failures++ }.use { it.write(byteArrayOf(1, 2, 3), 0, 3) }

        assertEquals(listOf(1, 2, 3), delegate.written)
        assertTrue(file.exists())
        assertEquals(0, failures)
    }
}
