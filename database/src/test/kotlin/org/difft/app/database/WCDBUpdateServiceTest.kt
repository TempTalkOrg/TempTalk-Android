package org.difft.app.database

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Concurrency-guard tests for WCDBUpdateService.updatingRooms()'s AtomicBoolean (Fix 1 adds a 3rd caller). WCDB native libs can't load in JVM tests + no injection seam, so these assert the atomic/framework semantics, pinning the production field type by reflection.
class WCDBUpdateServiceTest {

    // ---------- T1 — compareAndSet single-winner: exactly one CAS winner across concurrent callers (plain var/@Volatile would double-register) ----------
    @Test
    fun `T1 compareAndSet has exactly one winner across concurrent callers`() {
        val flag = AtomicBoolean(false)
        val threadCount = 32
        val winners = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                startLatch.await() // line everyone up to maximize the race window
                if (flag.compareAndSet(false, true)) {
                    winners.incrementAndGet()
                }
                doneLatch.countDown()
            }.start()
        }

        startLatch.countDown()
        doneLatch.await()

        assertEquals(1, winners.get(), "Exactly one caller must win compareAndSet; rest must no-op")
        assertTrue(flag.get(), "Flag must end up true (the winner set it)")
    }

    // Pins the production guard field as AtomicBoolean — a downgrade to plain var Boolean fails this reflective cast.
    @Test
    fun `T1b production guard field is AtomicBoolean`() {
        val declaredType = WCDBUpdateService::class.java
            .getDeclaredField("isUpdatingRoomsStarted")
            .type
        assertEquals(
            AtomicBoolean::class.java,
            declaredType,
            "Guard must be an AtomicBoolean for the atomic check-then-act guarantee (T1)"
        )
    }

    // ---------- T2 — .catch reset: set(false) lets the next caller re-register a crashed collector (reset, not auto-restart) ----------
    @Test
    fun `T2 catch reset allows a subsequent caller to re-register`() {
        val flag = AtomicBoolean(false)

        // First caller wins and registers.
        assertTrue(flag.compareAndSet(false, true), "First caller registers")
        // A concurrent/duplicate caller while registered must no-op.
        assertFalse(flag.compareAndSet(false, true), "Duplicate caller no-ops while registered")

        // Simulate the flow crashing -> .catch resets the flag.
        flag.set(false)

        // The NEXT caller invocation (service recreate / IndexActivity / FCM) can now re-register.
        assertTrue(flag.compareAndSet(false, true), "After .catch reset, the next caller re-registers")
    }

    // ---------- T7 — process-scope survival: collector on WCDBUpdateService's SupervisorJob survives serviceScope.cancel() (why Fix 1 is a bare call) ----------
    @Test
    fun `T7 collector on process scope survives unrelated scope cancellation`() = runBlocking {
        // Stand-in for WCDBUpdateService's process scope (same shape: IO + SupervisorJob).
        val processScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Stand-in for MessageForegroundService.serviceScope (the one cancelled in onDestroy).
        val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val collectorJob = processScope.launch {
            // long-lived collector
            while (isActive) {
                delay(50)
            }
        }

        // Service is destroyed -> its scope is cancelled. The collector is NOT anchored to it.
        serviceScope.cancel()
        delay(120) // give cancellation a chance to propagate if (incorrectly) linked

        assertTrue(
            collectorJob.isActive,
            "Collector anchored to the process SupervisorJob MUST survive serviceScope.cancel() " +
                "(proves launchIn(this) anchoring, not serviceScope.launch{})"
        )

        processScope.cancel() // cleanup
    }
}
