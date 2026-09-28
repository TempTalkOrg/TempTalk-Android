package com.difft.android.migration.v2.container

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Transport-agnostic byte sink for the migration container: the writer frames and encrypts the
 * record stream without knowing where the bytes land.
 *
 * [openStream] is called exactly once per write and the caller owns and closes the returned stream.
 * [abort] is called when a write fails mid-stream so the sink can discard the partial artifact; it
 * must be idempotent and must never throw.
 */
interface ArchiveSink {

    /** Open the destination byte stream. Caller owns + closes the returned stream. */
    fun openStream(): OutputStream

    /** Discard any partially-written artifact. Idempotent; must never throw. */
    fun abort()
}

/**
 * [ArchiveSink] that writes to a local [File], truncating any existing content. [abort] deletes the
 * target so a failed write never leaves a partial archive behind. The parent directory is created
 * if missing.
 */
class FileArchiveSink(private val target: File) : ArchiveSink {

    override fun openStream(): OutputStream {
        target.parentFile?.mkdirs()
        return FileOutputStream(target)
    }

    override fun abort() {
        runCatching { if (target.exists()) target.delete() }
    }
}
