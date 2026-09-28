package com.difft.android.migration.v2.restore

import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Staging attachment output whose write failure stays with that one attachment: on the first
 * [IOException] the partial [file] is deleted, [onFailure] fires once, and every later write is
 * swallowed so the reader keeps draining the frame and the import goes on.
 */
class FaultIsolatingOutputStream(
    private val file: File,
    private val delegate: OutputStream,
    private val onFailure: () -> Unit,
) : OutputStream() {

    var failed: Boolean = false
        private set

    override fun write(b: Int) = guarded { delegate.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) = guarded { delegate.write(b, off, len) }

    override fun flush() = guarded { delegate.flush() }

    override fun close() {
        if (failed) return
        try {
            delegate.close()
        } catch (e: IOException) {
            fail(e)
        }
    }

    private inline fun guarded(op: () -> Unit) {
        if (failed) return
        try {
            op()
        } catch (e: IOException) {
            fail(e)
        }
    }

    private fun fail(e: IOException) {
        failed = true
        L.w { "[MigrationV2][Restore] attachment write failed, dropping partial file: ${e.javaClass.simpleName}: ${e.message}" }
        runCatching { delegate.close() }
        runCatching { file.delete() }
        onFailure()
    }
}
