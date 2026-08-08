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

/**
 * Unit tests for [ScreenLockUtil.temporarilyDisabled] — the monotonic, time-boxed
 * screen-lock exemption.
 *
 * The exemption is backed by a single @Volatile Long deadline read against
 * [android.os.SystemClock.elapsedRealtime]. Robolectric's [ShadowSystemClock] drives the
 * monotonic clock so the auto-expiry (T1/T2) is exercised against the real framework shadow
 * rather than an injected fake — technology matches production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenLockUtilTest {

    @Before
    fun setUp() {
        // Process-singleton — clear any exemption leaked by a sibling test.
        ScreenLockUtil.temporarilyDisabled = false
        // Drain the one-shot recent-unlock flag a sibling test may have left set.
        ScreenLockUtil.recentlyUnlocked
    }

    @After
    fun tearDown() {
        ScreenLockUtil.temporarilyDisabled = false
        ScreenLockUtil.recentlyUnlocked
    }

    // T13: recently-unlocked set right after markRecentlyUnlocked() [Bug1 popup replay bypass].
    @Test
    fun `T13 recently unlocked set immediately after unlock`() {
        ScreenLockUtil.markRecentlyUnlocked()
        assertTrue(ScreenLockUtil.recentlyUnlocked)
    }

    // T14: one-shot — recentlyUnlocked is consumed on read, so only the immediate replay bypasses
    // the gate; a second read is false (no lingering bypass window).
    @Test
    fun `T14 recently unlocked is consumed on read`() {
        ScreenLockUtil.markRecentlyUnlocked()
        assertTrue(ScreenLockUtil.recentlyUnlocked)
        assertFalse(ScreenLockUtil.recentlyUnlocked)
    }

    // T15: the two flags are independent — a recent unlock does NOT grant a temporary exemption
    // (so shouldShowScreenLock / the foreground lock check stays unaffected), and vice versa.
    @Test
    fun `T15 recently-unlocked and temporarilyDisabled are independent`() {
        ScreenLockUtil.markRecentlyUnlocked()
        assertFalse(ScreenLockUtil.temporarilyDisabled)
        assertTrue(ScreenLockUtil.recentlyUnlocked) // consumes the one-shot

        ScreenLockUtil.temporarilyDisabled = true
        assertFalse(ScreenLockUtil.recentlyUnlocked)
    }

    // T17: leak-guard — if the replay never reads the flag, it still auto-expires after the window,
    // so a mark that is never consumed cannot leave the popup gate armed for a later unrelated popup.
    @Test
    fun `T17 recently unlocked auto-expires when never consumed`() {
        ScreenLockUtil.markRecentlyUnlocked()
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_000L))
        assertFalse(ScreenLockUtil.recentlyUnlocked)
    }

    // T1: time-box active — set true, advance < 60s → still exempt.
    @Test
    fun `T1 exemption active within time-box`() {
        ScreenLockUtil.temporarilyDisabled = true
        ShadowSystemClock.advanceBy(Duration.ofMillis(59_000L))
        assertTrue(ScreenLockUtil.temporarilyDisabled)
    }

    // T2: auto-expire — set true, advance >= 60s → exemption lapses without any explicit clear.
    @Test
    fun `T2 exemption auto-expires at time-box boundary`() {
        ScreenLockUtil.temporarilyDisabled = true
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000L))
        assertFalse(ScreenLockUtil.temporarilyDisabled)
    }

    // T3: immediate clear — the callback-clear path re-locks right away, not up to 60s later.
    @Test
    fun `T3 explicit clear takes effect immediately`() {
        ScreenLockUtil.temporarilyDisabled = true
        assertTrue(ScreenLockUtil.temporarilyDisabled)
        ScreenLockUtil.temporarilyDisabled = false
        assertFalse(ScreenLockUtil.temporarilyDisabled)
    }

    // T4: thread-safety — concurrent set/get across two dispatchers throw nothing (single
    // volatile read/write, no compound RMW), and a final deterministic write reads back correctly.
    @Test
    fun `T4 concurrent set and get is race-free`() = runTest {
        coroutineScope {
            (0 until 100).map { i ->
                val dispatcher = if (i % 2 == 0) Dispatchers.Default else Dispatchers.IO
                async(dispatcher) {
                    ScreenLockUtil.temporarilyDisabled = (i % 2 == 0)
                    // Read is one volatile load; must not throw regardless of concurrent writes.
                    ScreenLockUtil.temporarilyDisabled
                }
            }.awaitAll()
        }

        // Deterministic terminal state after the race settles.
        withContext(Dispatchers.Default) { ScreenLockUtil.temporarilyDisabled = false }
        assertFalse(ScreenLockUtil.temporarilyDisabled)
        withContext(Dispatchers.Default) { ScreenLockUtil.temporarilyDisabled = true }
        assertTrue(ScreenLockUtil.temporarilyDisabled)
    }
}
