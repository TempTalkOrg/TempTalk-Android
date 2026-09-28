package com.difft.android.migration.v2.transfer

import com.difft.android.migration.v2.container.ArchiveSink
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * An [ArchiveSink] that counts the bytes written through it and discards them, so a container's
 * exact length can be pre-counted without buffering it or writing a `.ttmig` file.
 *
 * The upload body needs a known `Content-Length`, which the streaming `ContainerWriter` does not
 * know up front. Callers dry-run the same `ContainerWriter.write(...)` into this sink to learn the
 * length, then do the real streamed write into the [P2pContainerPipe]. The dry run is exact: the
 * encryption and framing overhead is fixed, and the random salt and IV are fixed-size.
 */
class ContainerSizeCounter : ArchiveSink {

    private val counted = AtomicLong(0L)

    /** The number of bytes written through this sink so far (the container's exact length). */
    val byteCount: Long get() = counted.get()

    override fun openStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            counted.incrementAndGet()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            counted.addAndGet(len.toLong())
        }
    }

    override fun abort() {
        // Counting only — nothing to discard.
    }
}
