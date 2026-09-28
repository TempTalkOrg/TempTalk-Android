package com.difft.android.chat.translate.model

import android.content.Context
import android.net.ConnectivityManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Registration idempotence of the translate model-download network watcher.
 *
 * `ConnectivityManager.unregisterNetworkCallback` throws `IllegalArgumentException` for a callback
 * that is not registered, so a double unregister — which the store can genuinely produce
 * (`onBackground()` + `clearOnLogout()`) — must be a no-op rather than an exception.
 *
 * Robolectric only for `Build.VERSION.SDK_INT` (a plain JVM unit test reads 0 and would take the
 * pre-28 branch); the ConnectivityManager itself is a MockK so the system calls can be counted.
 *
 * What each case asserts:
 *  - T54b register x2 -> unregister x2 calls each system API exactly once and never throws
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateNetworkWatcherTest {

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        connectivityManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- T54b ---------------------------------------------------------------

    @Test
    fun `T54b register and unregister are idempotent`() {
        val watcher = TranslateNetworkWatcher(context) { }

        watcher.register()
        watcher.register()
        watcher.unregister()
        watcher.unregister()

        verify(exactly = 1) { connectivityManager.registerDefaultNetworkCallback(any()) }
        verify(exactly = 1) { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `T54b a register after an unregister registers again`() {
        val watcher = TranslateNetworkWatcher(context) { }

        watcher.register()
        watcher.unregister()
        watcher.register()

        verify(exactly = 2) { connectivityManager.registerDefaultNetworkCallback(any()) }
    }

    @Test
    fun `unregister before any register touches nothing`() {
        val watcher = TranslateNetworkWatcher(context) { }

        watcher.unregister()

        verify(exactly = 0) { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `a device with no ConnectivityManager degrades to a no-op`() {
        val noServiceContext = mockk<Context>(relaxed = true)
        every { noServiceContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null
        var callbacks = 0

        val watcher = TranslateNetworkWatcher(noServiceContext) { callbacks++ }
        watcher.register()
        watcher.unregister()

        assertEquals(0, callbacks)
    }
}
