package com.difft.android.p2p.engine

import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.FileSource
import com.difft.android.p2p.engine.io.Sha256
import com.difft.android.p2p.engine.model.FileMetadata
import okio.Buffer
import okio.Sink
import okio.Source

/** In-memory [FileSource] over a byte array — for pure-JVM engine tests. */
class ByteArrayFileSource(
    private val bytes: ByteArray,
    override val fileName: String = "test.bin",
    override val mimeType: String = "application/octet-stream",
) : FileSource {
    override val size: Long = bytes.size.toLong()
    override val sha256: String = Sha256.hex(bytes)
    override fun openRead(): Source = Buffer().apply { write(bytes) }
}

/**
 * In-memory [FileSink] capturing written bytes — for pure-JVM engine tests. Recomputes the
 * SHA-256 on finalize (defense-in-depth, like the real DiskFileSink) and fails on mismatch.
 */
class CapturingFileSink : FileSink {
    val buffers = HashMap<String, Buffer>()
    var finalized: String? = null
        private set

    override fun openWrite(meta: FileMetadata): Sink =
        Buffer().also { buffers[meta.id] = it }

    override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> {
        val buf = buffers[meta.id] ?: return Result.failure(IllegalStateException("no buffer"))
        val actual = Sha256.hex(buf.copy())
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            return Result.failure(IllegalStateException("sha256 mismatch"))
        }
        finalized = "/captured/${meta.fileName}"
        return Result.success(finalized!!)
    }

    fun bytesOf(fileId: String): ByteArray = buffers.getValue(fileId).copy().readByteArray()
}
