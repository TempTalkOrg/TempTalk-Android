package com.difft.android.network.proxy

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ICallServiceUrlsProvider
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.base.utils.IGlobalConfigsManager
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H-4: save() does not corrupt previously-saved link on validation failure.
 *
 * Prior to the fix, an invalid link with enabled=true would overwrite the
 * previously-working link in storage. The fixed behavior is a pure no-op +
 * log + return-false on the rejection branch.
 *
 * Persistence path: `userManager.update {}` (encrypted secure_user.pb DataStore).
 * We use a real in-memory [UserManager] (the interface's default
 * `update`/`setUserData`/`getUserData` chain operates over a single mutable
 * [UserData] field) so we can assert "the second save did not overwrite the
 * first" purely from the post-state, not from mock interactions.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyConfigProviderSaveBehaviorTest {

    /**
     * Minimal in-memory [UserManager] honoring the interface contract:
     *  - [getUserData] returns the current snapshot.
     *  - [setUserData] replaces it.
     *  - [update] is inherited from the interface (copy → apply config → setUserData
     *    if changed).
     * Tracks how many writes landed so tests can assert the rejection no-op
     * branch never persisted anything.
     */
    private class FakeUserManager : UserManager {
        private var current: UserData? = UserData()
        var writeCount: Int = 0
            private set

        override fun setUserData(userData: UserData, commit: Boolean) {
            current = userData
            writeCount++
        }

        override fun getUserData(): UserData? = current
    }

    /**
     * Counts `reconnectAfterProxyChange()` invocations. Optionally throws to
     * verify that a refresher fault never propagates out of `save()` / `clear()`.
     */
    private class FakeConnectionRefresher(
        private val throwOnInvoke: Boolean = false,
    ) : IConnectionRefresher {
        private val counter = AtomicInteger(0)
        val invocationCount: Int get() = counter.get()
        override fun reconnectAfterProxyChange() {
            counter.incrementAndGet()
            if (throwOnInvoke) throw RuntimeException("simulated refresher failure")
        }
    }

    private fun makeProvider(
        refresher: IConnectionRefresher = FakeConnectionRefresher(),
    ): Triple<ProxyConfigProvider, FakeUserManager, IConnectionRefresher> {
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getNewGlobalConfigs() } returns null
        val csp = mockk<ICallServiceUrlsProvider>(relaxed = true)
        every { csp.getCachedServiceUrlsDomains() } returns emptyList()

        val userManager = FakeUserManager()
        val provider = ProxyConfigProvider(
            userManager = userManager,
            globalConfigsManagerLazy = Lazy { gcm },
            callServiceUrlsProviderLazy = Lazy { csp },
            connectionRefresherLazy = Lazy { refresher },
        )
        return Triple(provider, userManager, refresher)
    }

    @Test
    fun `save(enabled=true, invalid link) does not overwrite previously-saved link`() {
        val (provider, userManager, _) = makeProvider()

        // First save a valid link.
        val validLink = ProxyConfig(
            host = "1.2.3.4",
            port = 443,
            spkiPinBase64 = "pin",
        ).toShareLink()
        assertTrue(provider.save(validLink, enabled = true))
        val savedBefore = provider.savedShareLink
        assertNotNull(savedBefore)
        assertTrue(provider.isEnabled)
        val writesAfterFirst = userManager.writeCount

        // Now attempt to save an invalid link with enabled=true.
        assertFalse(provider.save("ytp://config?d=garbage", enabled = true))

        // The previously-valid link must be preserved and proxy must still be active.
        assertEquals(savedBefore, provider.savedShareLink)
        assertTrue(provider.isEnabled, "proxy must still be active with the old link")
        assertEquals(savedBefore, provider.current?.toShareLink())

        // Persisted state must still carry the valid link, not the garbage one.
        val persisted = userManager.getUserData()
        assertNotNull(persisted)
        assertEquals(savedBefore, persisted.proxyShareLink)
        assertTrue(persisted.proxyEnabled)

        // The rejected save must NOT have produced any write — write count is
        // unchanged from the post-first-save count.
        assertEquals(writesAfterFirst, userManager.writeCount, "rejected save must be a pure no-op")
    }

    /**
     * Regression: concurrent readers + a writer must never observe a torn snapshot
     * (e.g. savedLink updated while cached still has the old value). Two reader
     * threads hammer the public getters while one writer thread alternates between
     * save() and clear(). No exception may escape, and the final post-quiesce state
     * must be internally consistent (cached reflects the last savedLink/enabledFlag).
     *
     * This is the test for the `refreshLock` guarding the multi-volatile write
     * blocks in [ProxyConfigProvider.refreshFromUserDataIfChanged] / [save] / [clear].
     * It's intentionally small — the goal is regression prevention, not exhaustive
     * stress testing.
     */
    @Test
    fun `concurrent readers and writer do not throw and converge to consistent state`() {
        val (provider, _, _) = makeProvider()
        val validLink = ProxyConfig(
            host = "1.2.3.4",
            port = 443,
            spkiPinBase64 = "pin",
        ).toShareLink()

        val iterations = 200
        val readerError = AtomicReference<Throwable?>(null)
        val writerError = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)

        val reader: Runnable = Runnable {
            try {
                start.await()
                repeat(iterations * 5) {
                    // Touch every public accessor that goes through refreshFromUserDataIfChanged().
                    provider.current
                    provider.isEnabled
                    provider.isEnabledByUser
                    provider.savedShareLink
                }
            } catch (t: Throwable) {
                readerError.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }

        val writer = Runnable {
            try {
                start.await()
                repeat(iterations) {
                    provider.save(validLink, enabled = true)
                    provider.clear()
                }
            } catch (t: Throwable) {
                writerError.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }

        Thread(reader, "proxy-reader-1").start()
        Thread(reader, "proxy-reader-2").start()
        Thread(writer, "proxy-writer").start()

        start.countDown()
        assertTrue(
            done.await(20, TimeUnit.SECONDS),
            "concurrent reader/writer threads did not finish within 20s — possible deadlock",
        )

        assertNull(readerError.get(), "reader thread threw: ${readerError.get()}")
        assertNull(writerError.get(), "writer thread threw: ${writerError.get()}")

        // After the writer's final clear(), the provider must be in the cleared
        // state — cached null, savedLink null, isEnabled false. If a torn snapshot
        // ever leaked, the public getters would disagree (e.g. savedLink == validLink
        // but cached == null, or vice-versa) and one of these asserts would fire.
        assertNull(provider.savedShareLink)
        assertNull(provider.current)
        assertFalse(provider.isEnabled)
        assertFalse(provider.isEnabledByUser)
    }

    // -----------------------------------------------------------------------
    // IConnectionRefresher trigger semantics — save() / clear() must drop the
    // IM WebSocket on real state transitions ONLY (not on no-op writes, not on
    // invalid-link rejections), and a refresher fault must NEVER propagate out.
    // -----------------------------------------------------------------------

    /** A valid share-link reused across the refresher tests below. */
    private val validLink: String = ProxyConfig(
        host = "1.2.3.4",
        port = 443,
        spkiPinBase64 = "pin",
    ).toShareLink()

    @Test
    fun `save with new valid link from clean state triggers refresher once`() {
        // Arrange
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)

        // Act
        assertTrue(provider.save(validLink, enabled = true))

        // Assert: exactly one reconnect fired for the off→on transition.
        assertEquals(1, refresher.invocationCount)
    }

    @Test
    fun `save with same link and enabled flag is a no-op for refresher`() {
        // Arrange
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)
        assertTrue(provider.save(validLink, enabled = true))
        assertEquals(1, refresher.invocationCount)

        // Act — Hilt or settings UI may re-emit the same save() (e.g. config
        // resync); steady-state writes that change nothing must not drop the WS.
        assertTrue(provider.save(validLink, enabled = true))

        // Assert: counter unchanged.
        assertEquals(1, refresher.invocationCount)
    }

    @Test
    fun `save rejected for invalid link does not trigger refresher`() {
        // Arrange — clean start, no prior state to trigger from.
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)

        // Act — H-4 rejection path: enabled=true with an unparseable link.
        assertFalse(provider.save("ytp://config?d=garbage", enabled = true))

        // Assert: refresher never invoked — state did not mutate, so neither
        // should the WS be torn down.
        assertEquals(0, refresher.invocationCount)
    }

    @Test
    fun `clear after active state triggers refresher`() {
        // Arrange — establish active state first.
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)
        assertTrue(provider.save(validLink, enabled = true))
        assertEquals(1, refresher.invocationCount)

        // Act — disable proxy via clear().
        provider.clear()

        // Assert: counter advanced for the on→off transition.
        assertEquals(2, refresher.invocationCount)
    }

    @Test
    fun `clear on already-empty state does not trigger refresher`() {
        // Arrange — clean state, never saved anything.
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)

        // Act
        provider.clear()

        // Assert: nothing to clear, no reconnect needed.
        assertEquals(0, refresher.invocationCount)
    }

    @Test
    fun `refresher throwing does not propagate out of save or clear`() {
        // Arrange — refresher that always throws.
        val refresher = FakeConnectionRefresher(throwOnInvoke = true)
        val (provider, userManager, _) = makeProvider(refresher)

        // Act — save() must still report success despite the refresher fault.
        assertTrue(provider.save(validLink, enabled = true))
        assertEquals(1, refresher.invocationCount)

        // State is correctly persisted regardless of the refresher fault.
        assertEquals(validLink, provider.savedShareLink)
        assertTrue(provider.isEnabled)
        assertEquals(validLink, userManager.getUserData()?.proxyShareLink)
        assertTrue(userManager.getUserData()?.proxyEnabled == true)

        // clear() must also swallow the fault and complete the cleared state.
        provider.clear()
        assertEquals(2, refresher.invocationCount)
        assertNull(provider.savedShareLink)
        assertFalse(provider.isEnabled)
        assertNull(userManager.getUserData()?.proxyShareLink)
        assertFalse(userManager.getUserData()?.proxyEnabled == true)
    }
}
