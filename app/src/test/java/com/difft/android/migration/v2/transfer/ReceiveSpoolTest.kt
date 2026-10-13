package com.difft.android.migration.v2.transfer

import com.difft.android.migration.model.MigrationException
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.sink
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit coverage for [ReceiveSpool] — T23-T27 of the symmetric-transport design.
 *
 * The production segment size is 64 MiB, which would make these tests write hundreds of megabytes
 * for properties that are size-independent (the writer never blocks, segments are consumed in order
 * and deleted, a starved reader gets a partial seal). Every case therefore drives the same code with
 * a small `segmentBytes`, so the ratios the assertions depend on are preserved exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class ReceiveSpoolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val segment = 2L * 1024 * 1024
    private val chunk = 64 * 1024

    /** 3 full segments plus a partial one. */
    private val totalChunks = ((segment * 3 + segment / 4) / chunk).toInt()

    private fun spoolDir() = File(tmp.newFolder("spool-${System.nanoTime()}"), "seg")

    private fun payload(index: Int) = ByteArray(chunk) { (index + it).toByte() }

    // ── T23 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `writer never blocks on a reader that never starts`() {
        val dir = spoolDir()
        val spool = ReceiveSpool(dir, segmentBytes = segment)
        val sink = spool.openSink()

        var slowestWriteMs = 0L
        repeat(totalChunks) { i ->
            val started = System.nanoTime()
            sink.write(Buffer().apply { write(payload(i)) }, chunk.toLong())
            slowestWriteMs = maxOf(slowestWriteMs, (System.nanoTime() - started) / 1_000_000)
        }

        // 3 sealed segments plus the open partial one, all on disk with nobody consuming them.
        assertEquals(4, dir.listFiles()?.size, "every segment is on disk with no reader attached")
        assertTrue(slowestWriteMs < SLOW_WRITE_LIMIT_MS, "no write blocked on the importer (worst=${slowestWriteMs}ms)")
        spool.abort(null)
    }

    // ── T24 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `reader sees every byte in order and deletes each segment it drains`() {
        val dir = spoolDir()
        val spool = ReceiveSpool(dir, segmentBytes = segment)
        val expected = Buffer()

        val sink = spool.openSink()
        repeat(totalChunks) { i ->
            val bytes = payload(i)
            expected.write(bytes)
            sink.write(Buffer().apply { write(bytes) }, chunk.toLong())
        }
        sink.close()

        val read = spool.openInput().use { it.readBytes() }

        assertContentEquals(expected.readByteArray(), read, "the reader reproduces the written byte stream exactly")
        assertEquals(0, dir.listFiles()?.size, "every drained segment is deleted as it is consumed")
    }

    // ── T24b ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the sink and the input are idempotent accessors`() {
        val spool = ReceiveSpool(spoolDir(), segmentBytes = segment)
        assertSame(spool.openSink(), spool.openSink(), "finalizeFile closes the writer through a second openSink()")
        assertSame(spool.openInput(), spool.openInput())
        spool.abort(null)
    }

    // ── T25 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a starved reader gets a partial seal instead of waiting for a full segment`() {
        val dir = spoolDir()
        // No starvation floor here: the case is about the seal request itself, not the batching.
        val spool = ReceiveSpool(dir, segmentBytes = segment, starvedSealMinBytes = 0L)
        val small = ByteArray(1024) { it.toByte() }
        val got = AtomicReference<ByteArray>()
        val parked = CountDownLatch(1)
        val done = CountDownLatch(1)

        val reader = Thread {
            val input = spool.openInput()
            parked.countDown()
            val buffer = ByteArray(small.size)
            var filled = 0
            while (filled < small.size) {
                val n = input.read(buffer, filled, small.size - filled)
                if (n < 0) break
                filled += n
            }
            got.set(buffer.copyOf(filled))
            done.countDown()
        }
        reader.start()
        // The reader parks first, which is what sets the seal request the writer honours.
        assertTrue(parked.await(2, TimeUnit.SECONDS))
        Thread.sleep(PARK_SETTLE_MS)

        spool.openSink().write(Buffer().apply { write(small) }, small.size.toLong())

        assertTrue(done.await(2, TimeUnit.SECONDS), "1 KiB reached the reader without a 2 MiB seal")
        assertContentEquals(small, got.get())
        spool.abort(null)
        reader.join(1_000)
    }

    // ── T26 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `abort unblocks a parked reader with the cause and wipes the directory`() {
        val dir = spoolDir()
        val spool = ReceiveSpool(dir, segmentBytes = segment)
        spool.openSink().write(Buffer().apply { write(payload(0)) }, chunk.toLong())
        val thrown = AtomicReference<Throwable>()
        val parked = CountDownLatch(1)
        val done = CountDownLatch(1)

        val reader = Thread {
            val input = spool.openInput()
            parked.countDown()
            try {
                input.readBytes()
            } catch (e: Throwable) {
                thrown.set(e)
            }
            done.countDown()
        }
        reader.start()
        assertTrue(parked.await(2, TimeUnit.SECONDS))
        Thread.sleep(PARK_SETTLE_MS)

        val cause = IOException("peer went away")
        spool.abort(cause)

        assertTrue(done.await(2, TimeUnit.SECONDS), "the parked reader was released by the abort")
        val failure = assertNotNull(thrown.get())
        assertSame(cause, failure.cause, "the reader's failure carries the abort's cause")
        assertTrue(!dir.exists(), "abort wipes the segment directory")
        reader.join(1_000)
    }

    // ── T26b ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `concurrent aborts are safe and the directory is wiped once`() {
        val dir = spoolDir()
        val spool = ReceiveSpool(dir, segmentBytes = segment)
        spool.openSink().write(Buffer().apply { write(payload(0)) }, chunk.toLong())

        val parked = CountDownLatch(1)
        val readerDone = CountDownLatch(1)
        val wakeups = java.util.concurrent.atomic.AtomicInteger()
        val reader = Thread {
            val input = spool.openInput()
            parked.countDown()
            runCatching { input.readBytes() }
            wakeups.incrementAndGet()
            readerDone.countDown()
        }
        reader.start()
        assertTrue(parked.await(2, TimeUnit.SECONDS))
        Thread.sleep(PARK_SETTLE_MS)

        val barrier = CyclicBarrier(ABORT_THREADS)
        val escaped = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = (0 until ABORT_THREADS).map {
            Thread {
                barrier.await()
                runCatching { spool.abort(null) }.onFailure(escaped::add)
            }.apply { start() }
        }
        threads.forEach { it.join(2_000) }

        assertTrue(escaped.isEmpty(), "no abort caller saw an exception: $escaped")
        assertTrue(readerDone.await(2, TimeUnit.SECONDS), "the parked reader was released")
        assertEquals(1, wakeups.get(), "the reader woke exactly once")
        assertTrue(!dir.exists(), "the directory is gone after the concurrent aborts")
        reader.join(1_000)
    }

    // ── T27 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an out-of-space segment append surfaces as OutOfSpace from the sink`() {
        val dir = spoolDir()
        val spool = ReceiveSpool(
            dir,
            segmentBytes = segment,
            segmentSink = { file -> FullDiskSink(file) },
        )

        val failure = assertFailsWith<MigrationException.OutOfSpace> {
            spool.openSink().write(Buffer().apply { write(payload(0)) }, chunk.toLong())
        }
        assertTrue(failure.cause is IOException, "the ENOSPC IOException is kept as the cause")
    }

    /** Accepts the open, then fails the first real append the way a full filesystem does. */
    private class FullDiskSink(file: File) : Sink {
        private val real = file.sink()
        override fun write(source: Buffer, byteCount: Long): Unit =
            throw IOException("write failed: No space left on device")

        override fun flush() = real.flush()
        override fun timeout(): Timeout = real.timeout()
        override fun close() = real.close()
    }

    private companion object {
        /** A write that waited on the importer would be orders of magnitude slower than this. */
        const val SLOW_WRITE_LIMIT_MS = 50L

        /** Long enough for the reader thread to actually reach its park before the writer moves. */
        const val PARK_SETTLE_MS = 100L

        const val ABORT_THREADS = 3
    }

    @Test
    fun `a reader that outpaces the writer does not get one segment per write`() {
        val dir = spoolDir()
        val spool = ReceiveSpool(
            dir,
            segmentBytes = segment,
            starvedSealMinBytes = 256L * 1024,
            starvedSealMaxWaitMs = 60_000L,
        )
        val chunk = ByteArray(8 * 1024) { it.toByte() }
        val writes = 128 // 1 MiB in total, well under one segment
        val drained = CountDownLatch(1)
        val reader = Thread {
            val input = spool.openInput()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            if (total == chunk.size.toLong() * writes) drained.countDown()
        }
        reader.start()

        val sink = spool.openSink()
        repeat(writes) { sink.write(Buffer().apply { write(chunk) }, chunk.size.toLong()) }
        sink.close()

        assertTrue(drained.await(5, TimeUnit.SECONDS), "reader did not drain every byte")
        // 1 MiB against a 256 KiB starvation floor: at most four starved seals plus the closing one.
        assertTrue(spool.sealedSegments <= 5, "spool fragmented into ${spool.sealedSegments} segments")
        reader.join(1_000)
    }
}
