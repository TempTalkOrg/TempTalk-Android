package com.difft.android.base.utils.weakcontact

import android.os.SystemClock
import com.difft.android.base.utils.time.ServerTimeProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals












@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeakContactCountdownTest {

    private val dayMs = 24L * 60 * 60 * 1000

    @After
    fun tearDown() {
        
        clearClockAnchor()
    }

    

    @Test
    fun `T1 daysLeft 1_5 days remaining rounds up to 2`() {
        
        val serverNow = 1_000_000_000_000L
        val expireAt = serverNow + (dayMs * 3 / 2) 
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 500L,
            nowElapsed = 500L,
            expireAt = expireAt,
        )
        assertEquals(2, result)
    }

    @Test
    fun `T1 daysLeft 12h remaining floors at 1`() {
        
        val serverNow = 1_000_000_000_000L
        val expireAt = serverNow + (dayMs / 2) 
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 0L,
            nowElapsed = 0L,
            expireAt = expireAt,
        )
        assertEquals(1, result)
    }

    @Test
    fun `T1 daysLeft already expired floors at 1`() {
        
        val serverNow = 1_000_000_000_000L
        val expireAt = serverNow - dayMs 
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 0L,
            nowElapsed = 0L,
            expireAt = expireAt,
        )
        assertEquals(1, result)
    }

    @Test
    fun `T1 daysLeft monotonic extrapolation by 1h still rounds up`() {
        
        
        
        val serverNow = 1_000_000_000_000L
        val oneHour = 60L * 60 * 1000
        val expireAt = serverNow + (dayMs * 2) + oneHour
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 10_000L,
            nowElapsed = 10_000L + oneHour, 
            expireAt = expireAt,
        )
        assertEquals(2, result)
    }

    @Test
    fun `T1 daysLeft extrapolation consumes remaining time`() {
        
        val serverNow = 1_000_000_000_000L
        val expireAt = serverNow + (dayMs * 3) 
        
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 0L,
            nowElapsed = dayMs, 
            expireAt = expireAt,
        )
        assertEquals(2, result)
    }

    

    @Test
    fun `T12 daysLeft tracks real SystemClock elapsedRealtime advancing`() {
        
        
        
        val anchorElapsed = SystemClock.elapsedRealtime()
        val serverNow = 2_000_000_000_000L
        val expireAt = serverNow + (dayMs * 5)

        val before = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = anchorElapsed,
            nowElapsed = SystemClock.elapsedRealtime(),
            expireAt = expireAt,
        )

        
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis()) 
        val laterElapsed = anchorElapsed + (dayMs * 2)
        val after = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = anchorElapsed,
            nowElapsed = laterElapsed,
            expireAt = expireAt,
        )

        
        assertEquals(5, before)
        assertEquals(3, after)
    }

    

    @Test
    fun `daysLeftFromClock uses ServerTimeProvider nowMillis when unanchored`() {
        
        
        val now = 1_700_000_000_000L
        ServerTimeProvider.resetForTest(wallClock = { now }, elapsedClock = { 0L })
        val expireAt = now + (dayMs * 3) - 1
        val result = WeakContactCountdown.daysLeftFromClock(expireAt)
        assertEquals(3, result)
    }

    
    private fun clearClockAnchor() {
        ServerTimeProvider.resetForTest(wallClock = { 0L }, elapsedClock = { 0L })
    }
}
