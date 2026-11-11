package com.difft.android.base.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit





class AttachmentProgressMapTest {

    private var now = 1_000L

    @After
    fun tearDown() {
        FileUtil.progressClock = { System.currentTimeMillis() }
    }

    private fun useFakeClock() {
        FileUtil.progressClock = { now }
    }

    @Test
    fun `progress is readable under the key it was emitted with`() {
        FileUtil.emitProgressUpdate("local-read", 42)

        assertEquals(42, FileUtil.getProgress("local-read"))
        assertNull(FileUtil.getProgress("local-other"))
    }

    @Test
    fun `a terminal value stays readable for the retention window`() {
        useFakeClock()
        FileUtil.emitProgressUpdate("local-done", 100)

        
        assertEquals(100, FileUtil.getProgress("local-done"))
        now += 29_000L
        assertEquals(100, FileUtil.getProgress("local-done"))
    }

    @Test
    fun `a success terminal is evicted once the retention window passes`() {
        useFakeClock()
        FileUtil.emitProgressUpdate("local-success", 100)
        assertEquals(100, FileUtil.getProgress("local-success"))

        now += 31_000L

        
        assertNull(FileUtil.getProgress("local-success"))
    }

    @Test
    fun `failure and expired markers stay sticky past the retention window`() {
        useFakeClock()

        listOf(-1, -2).forEachIndexed { index, terminal ->
            val key = "local-sticky-$index"
            FileUtil.emitProgressUpdate(key, terminal)
            assertEquals(terminal, FileUtil.getProgress(key))

            now += 31_000L

            
            
            
            assertEquals(terminal, FileUtil.getProgress(key))
        }
    }

    @Test
    fun `a transfer restarted after a terminal value keeps its live progress`() {
        useFakeClock()
        FileUtil.emitProgressUpdate("local-retry", -1)

        now += 31_000L
        FileUtil.emitProgressUpdate("local-retry", 7)
        now += 31_000L

        assertEquals(7, FileUtil.getProgress("local-retry"))
    }

    @Test
    fun `concurrent emits from many threads all land`() {
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                try {
                    repeat(perThread) { i -> FileUtil.emitProgressUpdate("local-concurrent-$t-$i", i) }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        check(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        repeat(threads) { t ->
            repeat(perThread) { i -> assertEquals(i, FileUtil.getProgress("local-concurrent-$t-$i")) }
        }
    }
}
