package com.difft.android.migration.v2.transfer

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.restore.StorageGuard
import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.sink
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Disk spool between the receive socket and the importer. Ciphertext lands in ordered segment files
 * as it arrives; the importer reads them in order and deletes each one it finishes, so the socket is
 * never back-pressured by a staging batch commit or an attachment fsync — which is what let a slow
 * import trip the sender's write-stall watchdog and kill a perfectly good transfer.
 *
 * The writer never waits on the reader. It takes the bookkeeping lock only to publish a sealed
 * segment (microseconds) and never while doing file IO; the reader holds it only to pick the next
 * segment up or to park, never across a read. So the writer's worst case is that critical section.
 *
 * Single-use: one instance carries one body. [openSink] and [openInput] are idempotent accessors —
 * every call returns the same instance, so an owner that no longer holds the reference it handed to
 * the transport can still close it.
 *
 * @param onWriteProgress `(totalWritten, backlogBytes)` after every [WATCH_STEP_BYTES] and at each
 *   segment seal. It runs on the socket thread and may throw to stop the receive: that is the
 *   free-space monitor's hook.
 * @param segmentSink opens the byte destination for one segment file. Production always writes the
 *   file; a test substitutes a destination that fails the way a full disk does, which a healthy temp
 *   directory cannot reproduce.
 */
class ReceiveSpool(
    private val dir: File,
    private val segmentBytes: Long = SEGMENT_BYTES,
    private val starvedSealMinBytes: Long = STARVED_SEAL_MIN_BYTES,
    private val starvedSealMaxWaitMs: Long = STARVED_SEAL_MAX_WAIT_MS,
    private val onWriteProgress: (Long, Long) -> Unit = { _, _ -> },
    @get:VisibleForTesting internal val segmentSink: (File) -> Sink = { it.sink() },
) : Closeable {

    private val lock = ReentrantLock()
    private val published = lock.newCondition()

    /** Sealed segments waiting for the reader, oldest first. Guarded by [lock]. */
    private val sealed = ArrayDeque<File>()

    /** Guarded by [lock]; volatile so the writer can poll them without taking it. */
    @Volatile private var endOfStream = false
    @Volatile private var aborted = false
    @Volatile private var sealRequested = false
    private var abortCause: Throwable? = null

    @Volatile private var writtenBytes = 0L
    @Volatile private var consumedBytes = 0L
    private var nextOrdinal = 0

    /** Segments published so far; lets a test assert a fast reader did not fragment the spool. */
    @get:VisibleForTesting internal var sealedSegments = 0
        private set

    private val spoolSink = SpoolSink()
    private val spoolInput = SpoolInput()

    /** The one writer sink. Idempotent: every call returns the same instance. */
    fun openSink(): Sink = spoolSink

    /** The one reader stream. Idempotent for the same reason as [openSink]. */
    fun openInput(): InputStream = spoolInput

    /** Bytes written but not yet consumed by the importer. */
    fun backlogBytes(): Long = (writtenBytes - consumedBytes).coerceAtLeast(0L)

    /**
     * Unblock a parked reader with [cause], stop the writer, and delete every segment and the
     * directory. Idempotent under both repeated and concurrent invocation: three teardown paths
     * (the transport's `discard`, the session's `stop`, the consumer's own `finally`) can reach it
     * from three threads at once, so the flag flips exactly once under [lock] and the deletion runs
     * outside it — the "no IO under the lock" invariant holds for teardown too.
     */
    fun abort(cause: Throwable?) {
        lock.withLock {
            if (aborted) return
            aborted = true
            abortCause = cause
            endOfStream = true
            published.signalAll()
        }
        runCatching { dir.deleteRecursively() }
            .onFailure { e -> L.w { "[MigrationV2][P2P][Rx] spool dir wipe failed: ${e.javaClass.simpleName}" } }
    }

    override fun close() = abort(null)

    /**
     * Appends to the current segment, sealing it at [segmentBytes] or as soon as a starved reader
     * asks for it. Not thread-safe with itself: the transport pumps one body on one thread.
     */
    private inner class SpoolSink : Sink {
        private var out: BufferedSink? = null
        private var currentFile: File? = null
        private var currentBytes = 0L
        private var currentOpenedAtNs = 0L
        private var sinceWatch = 0L
        private var closed = false

        override fun write(source: Buffer, byteCount: Long) {
            try {
                if (aborted) throw abortedFailure()
                var remaining = byteCount
                while (remaining > 0L) {
                    val target = openCurrent()
                    val chunk = minOf(remaining, segmentBytes - currentBytes)
                    target.write(source, chunk)
                    currentBytes += chunk
                    remaining -= chunk
                    sinceWatch += chunk
                    writtenBytes += chunk
                    if (currentBytes >= segmentBytes || sealRequested && starvedSealDue()) seal()
                    if (sinceWatch >= WATCH_STEP_BYTES) {
                        sinceWatch = 0L
                        onWriteProgress(writtenBytes, backlogBytes())
                    }
                }
            } catch (e: Throwable) {
                // Classify before tearing down, and hand the cause to the parked reader so the local
                // outcome says "out of space" rather than "the link died".
                val failure = classify(e)
                dropCurrent()
                abort(failure)
                throw failure
            }
        }

        /**
         * Release the open segment's descriptor before the directory is wiped: an unlinked file keeps
         * its blocks until the last descriptor closes, which would defeat an abort meant to free space.
         */
        private fun dropCurrent() {
            runCatching { out?.close() }
            out = null
            currentFile = null
            currentBytes = 0L
        }

        override fun flush() {
            runCatching { out?.flush() }
        }

        override fun timeout(): Timeout = Timeout.NONE

        /** End of body: seal whatever is in the current segment and let the reader drain to EOF. */
        override fun close() {
            if (closed) return
            closed = true
            if (aborted) return
            try {
                if (currentFile != null) seal()
            } catch (e: Throwable) {
                val failure = classify(e)
                dropCurrent()
                abort(failure)
                throw failure
            }
            lock.withLock {
                endOfStream = true
                published.signalAll()
            }
        }

        private fun openCurrent(): BufferedSink {
            out?.let { return it }
            // Under the lock: abort() flips `aborted` inside it before wiping the directory, so a segment
            // is either created before the wipe (and unlinked by it) or refused — never recreated after.
            val (file, sink) = lock.withLock {
                if (aborted) throw abortedFailure()
                dir.mkdirs()
                val file = File(dir, SEGMENT_NAME_FORMAT.format(nextOrdinal++))
                file to segmentSink(file).buffer()
            }
            currentFile = file
            currentBytes = 0L
            currentOpenedAtNs = System.nanoTime()
            out = sink
            return sink
        }

        /**
         * A starved reader is served a partial segment only once it holds a worthwhile amount, or has
         * been open long enough that a slow sender would otherwise starve the importer: honouring the
         * request on every write turns a reader that outpaces the network into one segment per read.
         */
        private fun starvedSealDue(): Boolean =
            currentBytes >= starvedSealMinBytes ||
                System.nanoTime() - currentOpenedAtNs >= starvedSealMaxWaitMs * NANOS_PER_MS

        /** Close the current segment and publish it. The close is file IO, so it runs off the lock. */
        private fun seal() {
            val file = currentFile ?: return
            out?.close()
            out = null
            currentFile = null
            currentBytes = 0L
            val ordinal = nextOrdinal - 1
            lock.withLock {
                sealed.addLast(file)
                sealRequested = false
                published.signalAll()
            }
            sealedSegments++
            if (ordinal % SEAL_LOG_EVERY == 0) {
                L.i { "[MigrationV2][P2P][Rx] spool segment $ordinal sealed backlog=${backlogBytes() / MB}MB" }
            }
            onWriteProgress(writtenBytes, backlogBytes())
        }
    }

    /** Reads sealed segments in order, deleting each one as it is drained. */
    private inner class SpoolInput : InputStream() {
        private var current: InputStream? = null
        private var currentFile: File? = null

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val stream = current ?: openNext() ?: return -1
                val n = stream.read(b, off, len)
                if (n >= 0) {
                    consumedBytes += n
                    return n
                }
                dropCurrent()
            }
        }

        override fun close() {
            dropCurrent()
        }

        /** Null once the body is complete and every segment has been drained. */
        private fun openNext(): InputStream? {
            val file = awaitNextSegment() ?: return null
            // File IO, deliberately outside the lock.
            val stream = FileInputStream(file)
            currentFile = file
            current = stream
            return stream
        }

        private fun dropCurrent() {
            runCatching { current?.close() }
            current = null
            currentFile?.let { file -> runCatching { file.delete() } }
            currentFile = null
        }
    }

    /**
     * The next sealed segment, or null at end of stream. Parking here sets [sealRequested] so the
     * writer publishes a partial segment instead of making a small container wait for 64 MiB.
     */
    private fun awaitNextSegment(): File? {
        lock.withLock {
            while (true) {
                if (aborted) throw abortedFailure()
                sealed.pollFirst()?.let { return it }
                if (endOfStream) return null
                sealRequested = true
                published.await(PARK_STEP_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** The cause a teardown hands the parked reader, keeping a typed storage failure typed. */
    private fun abortedFailure(): Throwable {
        val cause = lock.withLock { abortCause }
        if (cause is MigrationException) return cause
        return IOException("receive spool aborted", cause)
    }

    private fun classify(e: Throwable): Throwable = when {
        e is MigrationException -> e
        StorageGuard.isOutOfSpace(e) -> MigrationException.OutOfSpace("receive spool write ran out of space", e)
        else -> e
    }

    companion object {
        /** Segment size: big enough that sealing is rare, small enough that a stall frees space fast. */
        const val SEGMENT_BYTES = 64L * 1024 * 1024

        /** How often the write side reports progress (and so how often free space is re-checked). */
        const val WATCH_STEP_BYTES = 8L * 1024 * 1024

        /** A starved reader waits for this much before a partial seal, unless the wait below elapses. */
        const val STARVED_SEAL_MIN_BYTES = 4L * 1024 * 1024

        /** Upper bound on how long a slow sender keeps a partial segment from a starved reader. */
        const val STARVED_SEAL_MAX_WAIT_MS = 1_000L

        /** One seal log line per this many segments: enough to follow progress, not one per read. */
        private const val SEAL_LOG_EVERY = 16

        private const val NANOS_PER_MS = 1_000_000L

        /** Zero-padded so the segment order is lexical and an `ls` during triage reads correctly. */
        private const val SEGMENT_NAME_FORMAT = "seg-%08d.bin"

        /** How long a starved reader parks between re-checks; a signal wakes it immediately. */
        private const val PARK_STEP_MS = 200L

        private const val MB = 1024L * 1024L
    }
}
