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
