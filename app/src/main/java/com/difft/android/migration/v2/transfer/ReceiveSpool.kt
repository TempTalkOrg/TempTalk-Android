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

    
    private val sealed = ArrayDeque<File>()

    
    @Volatile private var endOfStream = false
    @Volatile private var aborted = false
    @Volatile private var sealRequested = false
    private var abortCause: Throwable? = null

    @Volatile private var writtenBytes = 0L
    @Volatile private var consumedBytes = 0L
    private var nextOrdinal = 0

    
    @get:VisibleForTesting internal var sealedSegments = 0
        private set

    private val spoolSink = SpoolSink()
    private val spoolInput = SpoolInput()

    
    fun openSink(): Sink = spoolSink

    
    fun openInput(): InputStream = spoolInput

    
    fun backlogBytes(): Long = (writtenBytes - consumedBytes).coerceAtLeast(0L)

    






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
                
                
                val failure = classify(e)
                dropCurrent()
                abort(failure)
                throw failure
            }
        }

        



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

        




        private fun starvedSealDue(): Boolean =
            currentBytes >= starvedSealMinBytes ||
                System.nanoTime() - currentOpenedAtNs >= starvedSealMaxWaitMs * NANOS_PER_MS

        
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

        
        private fun openNext(): InputStream? {
            val file = awaitNextSegment() ?: return null
            
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
        
        const val SEGMENT_BYTES = 64L * 1024 * 1024

        
        const val WATCH_STEP_BYTES = 8L * 1024 * 1024

        
        const val STARVED_SEAL_MIN_BYTES = 4L * 1024 * 1024

        
        const val STARVED_SEAL_MAX_WAIT_MS = 1_000L

        
        private const val SEAL_LOG_EVERY = 16

        private const val NANOS_PER_MS = 1_000_000L

        
        private const val SEGMENT_NAME_FORMAT = "seg-%08d.bin"

        
        private const val PARK_STEP_MS = 200L

        private const val MB = 1024L * 1024L
    }
}
