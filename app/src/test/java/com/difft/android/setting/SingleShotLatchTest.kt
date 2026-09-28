package com.difft.android.setting

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SingleShotLatch] — the re-entrancy gate on the logout sequence (issue #1230).
 * App-scoped kick-out detection means two sources can confirm a kick at once, and the sequence is
 * destructive, so exactly one caller may ever pass.
 */
class SingleShotLatchTest {

    // T15
    @Test
    fun `first acquisition succeeds`() {
        assertTrue(SingleShotLatch().tryAcquire())
    }

    // T16
    @Test
    fun `later acquisitions fail`() {
        val latch = SingleShotLatch()
        assertTrue(latch.tryAcquire())
        assertFalse(latch.tryAcquire())
        assertFalse(latch.tryAcquire())
    }

    // T17
    @Test
    fun `exactly one of a hundred racing threads acquires`() {
        val threads = 100
        val latch = SingleShotLatch()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(16)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (latch.tryAcquire()) winners.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish in time")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, winners.get())
    }
}
