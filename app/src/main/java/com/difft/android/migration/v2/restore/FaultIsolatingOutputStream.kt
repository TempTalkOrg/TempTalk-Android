package com.difft.android.migration.v2.restore

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationException
import java.io.File
import java.io.IOException
import java.io.OutputStream








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
