package com.difft.android.migration.v2.restore

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationException
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Staging attachment output whose write failure stays with that one attachment: on the first
 * [IOException] the partial [file] is deleted, [onFailure] fires once, and every later write is
 * swallowed so the reader keeps draining the frame and the import goes on.
 *
 * Running out of space is the one failure that is *not* isolated — see [fail].
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
        if (StorageGuard.isOutOfSpace(e)) {
            // Out of space is not one attachment's problem: every later write fails too, and isolating
            // it would end in a 200 telling the sender about a complete migration it did not get.
            // Abort the whole import instead — staging is discarded and promote is never reached.
            failed = true
            runCatching { delegate.close() }
            runCatching { file.delete() }
            throw MigrationException.OutOfSpace("attachment write ran out of space", e)
        }
        failed = true
        L.w { "[MigrationV2][Restore] attachment write failed, dropping partial file: ${e.javaClass.simpleName}: ${e.message}" }
        runCatching { delegate.close() }
        runCatching { file.delete() }
        onFailure()
    }
}
