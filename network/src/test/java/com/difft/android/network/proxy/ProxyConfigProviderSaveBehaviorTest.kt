package com.difft.android.network.proxy

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
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






@RunWith(RobolectricTestRunner::class)
class ProxyConfigProviderSaveBehaviorTest {

    








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

        val userManager = FakeUserManager()
        val provider = ProxyConfigProvider(
            userManager = userManager,
            globalConfigsManagerLazy = Lazy { gcm },
            connectionRefresherLazy = Lazy { refresher },
        )
        return Triple(provider, userManager, refresher)
    }

    @Test
    fun `save(enabled=true, invalid link) does not overwrite previously-saved link`() {
        val (provider, userManager, _) = makeProvider()

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

        assertFalse(provider.save("ytp://config?d=garbage", enabled = true))

        
        assertEquals(savedBefore, provider.savedShareLink)
        assertTrue(provider.isEnabled, "proxy must still be active with the old link")
        assertEquals(savedBefore, provider.current?.toShareLink())

        
        val persisted = userManager.getUserData()
        assertNotNull(persisted)
        assertEquals(savedBefore, persisted.proxyShareLink)
        assertTrue(persisted.proxyEnabled)

        
        assertEquals(writesAfterFirst, userManager.writeCount, "rejected save must be a pure no-op")
    }

    





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

        
        assertNull(provider.savedShareLink)
        assertNull(provider.current)
        assertFalse(provider.isEnabled)
        assertFalse(provider.isEnabledByUser)
    }

    
    
    
    
    

    
    private val validLink: String = ProxyConfig(
        host = "1.2.3.4",
        port = 443,
        spkiPinBase64 = "pin",
    ).toShareLink()

    @Test
    fun `save with new valid link from clean state triggers refresher once`() {
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)

        assertTrue(provider.save(validLink, enabled = true))

        assertEquals(1, refresher.invocationCount)
    }

    @Test
    fun `save with same link and enabled flag is a no-op for refresher`() {
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)
        assertTrue(provider.save(validLink, enabled = true))
        assertEquals(1, refresher.invocationCount)

        
        
        assertTrue(provider.save(validLink, enabled = true))

        assertEquals(1, refresher.invocationCount)
    }

    @Test
    fun `save rejected for invalid link does not trigger refresher`() {
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)

        
        assertFalse(provider.save("ytp://config?d=garbage", enabled = true))

        
        
        assertEquals(0, refresher.invocationCount)
    }

    @Test
    fun `clear after active state triggers refresher`() {
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)
        assertTrue(provider.save(validLink, enabled = true))
        assertEquals(1, refresher.invocationCount)

        provider.clear()

        assertEquals(2, refresher.invocationCount)
    }

    @Test
    fun `clear on already-empty state does not trigger refresher`() {
        val refresher = FakeConnectionRefresher()
        val (provider, _, _) = makeProvider(refresher)

        provider.clear()

        
        assertEquals(0, refresher.invocationCount)
    }

    @Test
    fun `refresher throwing does not propagate out of save or clear`() {
        val refresher = FakeConnectionRefresher(throwOnInvoke = true)
        val (provider, userManager, _) = makeProvider(refresher)

        
        assertTrue(provider.save(validLink, enabled = true))
        assertEquals(1, refresher.invocationCount)

        
        assertEquals(validLink, provider.savedShareLink)
        assertTrue(provider.isEnabled)
        assertEquals(validLink, userManager.getUserData()?.proxyShareLink)
        assertTrue(userManager.getUserData()?.proxyEnabled == true)

        
        provider.clear()
        assertEquals(2, refresher.invocationCount)
        assertNull(provider.savedShareLink)
        assertFalse(provider.isEnabled)
        assertNull(userManager.getUserData()?.proxyShareLink)
        assertFalse(userManager.getUserData()?.proxyEnabled == true)
    }
}
