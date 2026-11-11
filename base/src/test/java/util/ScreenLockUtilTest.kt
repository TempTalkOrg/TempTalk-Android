package util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue










@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenLockUtilTest {

    @Before
    fun setUp() {
        
        ScreenLockUtil.temporarilyDisabled = false
        
        ScreenLockUtil.recentlyUnlocked
    }

    @After
    fun tearDown() {
        ScreenLockUtil.temporarilyDisabled = false
        ScreenLockUtil.recentlyUnlocked
    }

    
    @Test
    fun `T13 recently unlocked set immediately after unlock`() {
        ScreenLockUtil.markRecentlyUnlocked()
        assertTrue(ScreenLockUtil.recentlyUnlocked)
    }

    
    
    @Test
    fun `T14 recently unlocked is consumed on read`() {
        ScreenLockUtil.markRecentlyUnlocked()
        assertTrue(ScreenLockUtil.recentlyUnlocked)
        assertFalse(ScreenLockUtil.recentlyUnlocked)
    }

    
    
    @Test
    fun `T15 recently-unlocked and temporarilyDisabled are independent`() {
        ScreenLockUtil.markRecentlyUnlocked()
        assertFalse(ScreenLockUtil.temporarilyDisabled)
        assertTrue(ScreenLockUtil.recentlyUnlocked) 

        ScreenLockUtil.temporarilyDisabled = true
        assertFalse(ScreenLockUtil.recentlyUnlocked)
    }

    
    
    @Test
    fun `T17 recently unlocked auto-expires when never consumed`() {
        ScreenLockUtil.markRecentlyUnlocked()
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_000L))
        assertFalse(ScreenLockUtil.recentlyUnlocked)
    }

    
    @Test
    fun `T1 exemption active within time-box`() {
        ScreenLockUtil.temporarilyDisabled = true
        ShadowSystemClock.advanceBy(Duration.ofMillis(59_000L))
        assertTrue(ScreenLockUtil.temporarilyDisabled)
    }

    
    @Test
    fun `T2 exemption auto-expires at time-box boundary`() {
        ScreenLockUtil.temporarilyDisabled = true
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000L))
        assertFalse(ScreenLockUtil.temporarilyDisabled)
    }

    
    @Test
    fun `T3 explicit clear takes effect immediately`() {
        ScreenLockUtil.temporarilyDisabled = true
        assertTrue(ScreenLockUtil.temporarilyDisabled)
        ScreenLockUtil.temporarilyDisabled = false
        assertFalse(ScreenLockUtil.temporarilyDisabled)
    }

    
    
    @Test
    fun `T4 concurrent set and get is race-free`() = runTest {
        coroutineScope {
            (0 until 100).map { i ->
                val dispatcher = if (i % 2 == 0) Dispatchers.Default else Dispatchers.IO
                async(dispatcher) {
                    ScreenLockUtil.temporarilyDisabled = (i % 2 == 0)
                    
                    ScreenLockUtil.temporarilyDisabled
                }
            }.awaitAll()
        }

        
        withContext(Dispatchers.Default) { ScreenLockUtil.temporarilyDisabled = false }
        assertFalse(ScreenLockUtil.temporarilyDisabled)
        withContext(Dispatchers.Default) { ScreenLockUtil.temporarilyDisabled = true }
        assertTrue(ScreenLockUtil.temporarilyDisabled)
    }
}
