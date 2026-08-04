package util

import android.content.Intent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * T5 — [PendingScreenLockDeeplink] single-slot queue (Bug 1 deeplink replay).
 *
 * Pins the invariant the replay correctness depends on: offer stores the intent, poll returns it
 * and clears the slot, and a second poll returns null so the queued deeplink can never replay
 * twice after a single unlock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingScreenLockDeeplinkTest {

    @After
    fun tearDown() {
        // The object is a process-wide singleton; reset between tests.
        PendingScreenLockDeeplink.clear()
    }

    @Test
    fun `poll returns the offered intent then null on second poll`() {
        val intent = Intent("com.difft.test.OPEN_POPUP")

        PendingScreenLockDeeplink.offer(intent)

        assertSame(intent, PendingScreenLockDeeplink.poll(), "first poll returns the offered intent")
        assertNull(PendingScreenLockDeeplink.poll(), "second poll is null — no double replay")
    }

    @Test
    fun `poll on an empty queue returns null`() {
        assertNull(PendingScreenLockDeeplink.poll())
    }

    @Test
    fun `offer overwrites a previously queued intent`() {
        val first = Intent("com.difft.test.FIRST")
        val second = Intent("com.difft.test.SECOND")

        PendingScreenLockDeeplink.offer(first)
        PendingScreenLockDeeplink.offer(second)

        assertSame(second, PendingScreenLockDeeplink.poll(), "latest offer wins the single slot")
        assertNull(PendingScreenLockDeeplink.poll())
    }

    @Test
    fun `clear empties the queue`() {
        PendingScreenLockDeeplink.offer(Intent("com.difft.test.OPEN_POPUP"))

        PendingScreenLockDeeplink.clear()

        assertNull(PendingScreenLockDeeplink.poll())
    }
}
