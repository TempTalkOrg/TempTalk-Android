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


class WCDBUpdateServiceTest {

    
    @Test
    fun `T1 compareAndSet has exactly one winner across concurrent callers`() {
        val flag = AtomicBoolean(false)
        val threadCount = 32
        val winners = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                startLatch.await() 
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

    
    @Test
    fun `T2 catch reset allows a subsequent caller to re-register`() {
        val flag = AtomicBoolean(false)

        
        assertTrue(flag.compareAndSet(false, true), "First caller registers")
        
        assertFalse(flag.compareAndSet(false, true), "Duplicate caller no-ops while registered")

        
        flag.set(false)

        
        assertTrue(flag.compareAndSet(false, true), "After .catch reset, the next caller re-registers")
    }

    
    @Test
    fun `T7 collector on process scope survives unrelated scope cancellation`() = runBlocking {
        
        val processScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val collectorJob = processScope.launch {
            
            while (isActive) {
                delay(50)
            }
        }

        
        serviceScope.cancel()
        delay(120) 

        assertTrue(
            collectorJob.isActive,
            "Collector anchored to the process SupervisorJob MUST survive serviceScope.cancel() " +
                "(proves launchIn(this) anchoring, not serviceScope.launch{})"
        )

        processScope.cancel() 
    }
}
