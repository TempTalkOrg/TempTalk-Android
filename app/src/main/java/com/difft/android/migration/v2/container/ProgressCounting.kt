package com.difft.android.migration.v2.container

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Accumulates records and attachment bytes as a stream is produced or consumed and reports
 * [TransferProgress] to [onProgress], throttled to roughly one-percent steps so a hundred-thousand-
 * record history does not flood a StateFlow. The final `1.0` is always delivered.
 *
 * Totals may arrive after counting starts — the receiver only learns them from the manifest, which
 * is the first frame — so they are set through [start]; until then nothing is reported.
 */
class ProgressReporter(private val onProgress: (Float) -> Unit) {

    @Volatile
    private var totals: ContainerTotals? = null
    private val records = AtomicInteger()
    private val attachmentBytes = AtomicLong()
    private var lastReported = -1f

    fun start(totals: ContainerTotals?) {
        this.totals = totals
    }

    fun recordDone() {
        records.incrementAndGet()
        report()
    }

    fun attachmentBytes(count: Long) {
        if (count <= 0L) return
        attachmentBytes.addAndGet(count)
        report()
    }

    private fun report() {
        val ratio = TransferProgress.ratio(records.get(), attachmentBytes.get(), totals) ?: return
        synchronized(this) {
            val crossedStep = ratio - lastReported >= STEP
            val reachedEnd = ratio >= 1f && lastReported < 1f
            if (crossedStep || reachedEnd) {
                lastReported = ratio
                onProgress(ratio)
            }
        }
    }

    private companion object {
        const val STEP = 0.01f
    }
}

/** Wraps a byte source so every attachment payload byte it hands out is counted as it is read. */
fun AttachmentByteSource.counting(onBytes: (Long) -> Unit): AttachmentByteSource =
    AttachmentByteSource { contentRef ->
        open(contentRef)?.let { AttachmentBytes(it.length, CountingInputStream(it.stream, onBytes)) }
    }

/** Counts bytes as they are read through. Reports per read call, not per byte. */
class CountingInputStream(delegate: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(delegate) {
    override fun read(): Int = super.read().also { if (it >= 0) onBytes(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
}

/** Counts bytes as they are written through. Reports per write call, not per byte. */
class CountingOutputStream(delegate: OutputStream, private val onBytes: (Long) -> Unit) : FilterOutputStream(delegate) {
    override fun write(b: Int) {
        out.write(b)
        onBytes(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        // FilterOutputStream's default loops over write(int); go straight to the delegate instead.
        out.write(b, off, len)
        if (len > 0) onBytes(len.toLong())
    }
}
