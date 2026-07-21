package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.log.WCDBKeyUnavailableException
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract tests for [WCDB.resolveCipherKeyOnce]:
 *
 * - Cache-failure unmask + preserved cause: the first throw is the original (live-stack)
 *   exception; the second is a new instance whose `cause` chains back to it, while
 *   [WCDBKeyManager.getOrCreateKey] is hit exactly once — the cache holds the original failure
 *   Result untouched; only the throw site wraps.
 * - Success cache: repeated calls return the same [ByteArray], hitting the Keystore only once.
 * - `@Synchronized` self-serialization: two threads racing `resolveCipherKeyOnce()` from cold
 *   observe a single `getOrCreateKey` invocation and the same key — this is the shared
 *   check-then-act body the `db` lazy funnels into. The `@Synchronized` is a cheap defensive
 *   guard against any future second entry point bypassing the db-lazy lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WcdbResolveCipherKeyTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        mockkObject(WCDBKeyManager)
    }

    @After
    fun tearDown() {
        unmockkObject(WCDBKeyManager)
    }

    @Test
    fun `T6 cached failure rethrows a new exception chaining the original, keystore hit once`() {
        val keystoreCause = IllegalStateException("keystore down")
        val original = WCDBKeyUnavailableException("original failure", keystoreCause)
        every { WCDBKeyManager.getOrCreateKey(any()) } throws original

        val wcdb = WCDB(ctx, TestScope())

        val first = assertFailsWith<WCDBKeyUnavailableException> { wcdb.resolveCipherKeyOnce() }
        assertSame(original, first, "first throw must be the original live-stack exception")
        assertSame(keystoreCause, first.cause, "original must preserve the keystore cause")

        val second = assertFailsWith<WCDBKeyUnavailableException> { wcdb.resolveCipherKeyOnce() }
        assertNotSame(first, second, "second throw must be a NEW instance (real crash frame)")
        assertSame(first, second.cause, "new exception must chain the cached original as cause")
        assertSame(keystoreCause, second.cause?.cause, "original keystore cause still reachable")

        verify(exactly = 1) { WCDBKeyManager.getOrCreateKey(any()) }
    }

    @Test
    fun `T6-GUARD success is cached, keystore hit once across repeated calls`() {
        val key = ByteArray(48) { it.toByte() }
        every { WCDBKeyManager.getOrCreateKey(any()) } returns key

        val wcdb = WCDB(ctx, TestScope())

        val a = wcdb.resolveCipherKeyOnce()
        val b = wcdb.resolveCipherKeyOnce()
        val c = wcdb.resolveCipherKeyOnce()

        assertSame(key, a)
        assertSame(a, b)
        assertSame(b, c)
        verify(exactly = 1) { WCDBKeyManager.getOrCreateKey(any()) }
    }

    @Test
    fun `T3a key failure sets keyUnavailable but never dbCorrupted`() {
        every { WCDBKeyManager.getOrCreateKey(any()) } throws
            WCDBKeyUnavailableException("boom", IllegalStateException("keystore down"))

        val wcdb = WCDB(ctx, TestScope())
        assertFailsWith<WCDBKeyUnavailableException> { wcdb.resolveCipherKeyOnce() }

        assertTrue(wcdb.keyUnavailable, "key failure must set keyUnavailable")
        assertFalse(wcdb.dbCorrupted, "key failure must NEVER set dbCorrupted (no wipe)")
        assertTrue(wcdb.isDbInaccessible, "fused predicate true when keyUnavailable")
    }

    @Test
    fun `T3b success leaves keyUnavailable and isDbInaccessible false`() {
        every { WCDBKeyManager.getOrCreateKey(any()) } returns ByteArray(48) { it.toByte() }

        val wcdb = WCDB(ctx, TestScope())
        wcdb.resolveCipherKeyOnce()

        assertFalse(wcdb.keyUnavailable, "success must not flag key unavailable")
        assertFalse(wcdb.isDbInaccessible, "success must not flag DB inaccessible")
    }

    @Test
    fun `T3c keyUnavailable is monotonic, set once, stays set across repeated failing calls`() {
        every { WCDBKeyManager.getOrCreateKey(any()) } throws
            WCDBKeyUnavailableException("boom", IllegalStateException("keystore down"))

        val wcdb = WCDB(ctx, TestScope())
        repeat(3) {
            assertFailsWith<WCDBKeyUnavailableException> { wcdb.resolveCipherKeyOnce() }
            assertTrue(wcdb.keyUnavailable, "flag stays set on call #${it + 1}")
        }
        assertFalse(wcdb.dbCorrupted, "still never dbCorrupted")
        verify(exactly = 1) { WCDBKeyManager.getOrCreateKey(any()) }
    }

    @Test
    fun `T-RACE1 concurrent resolveCipherKeyOnce generates the key exactly once`() {
        repeat(200) {
            val callCount = AtomicInteger(0)
            val key = ByteArray(48)
            every { WCDBKeyManager.getOrCreateKey(any()) } answers {
                callCount.incrementAndGet()
                Thread.sleep(1) // widen the check-then-act window
                key
            }
            val wcdb = WCDB(ctx, TestScope())
            val pool = Executors.newFixedThreadPool(2)
            val barrier = CyclicBarrier(2)
            try {
                val f1 = pool.submit<ByteArray> { barrier.await(); wcdb.resolveCipherKeyOnce() }
                val f2 = pool.submit<ByteArray> { barrier.await(); wcdb.resolveCipherKeyOnce() }
                val r1 = f1.get()
                val r2 = f2.get()
                assertEquals(1, callCount.get(), "getOrCreateKey must run exactly once under race")
                assertSame(key, r1)
                assertSame(r1, r2, "both racers must observe the same cached key")
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
