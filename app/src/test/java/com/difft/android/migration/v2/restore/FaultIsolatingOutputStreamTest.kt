package com.difft.android.migration.v2.restore

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaultIsolatingOutputStreamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Fails on the write that carries byte value 9 and rejects everything after. */
    private class FlakyOutput : OutputStream() {
        val written = ArrayList<Int>()
        var closed = false
        override fun write(b: Int) {
            if (b == 9) throw IOException("disk full")
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
