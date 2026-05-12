package com.difft.android.base.concurrent

import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AppExecutorsTest {

    @Test
    fun `Default executor runs submitted tasks`() {
        val ran = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        AppExecutors.Default.execute {
            ran.set(true)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Task did not complete within timeout")
        assertTrue(ran.get())
    }

    @Test
    fun `Default executor runs off the main thread`() {
        val threadIsMain = AtomicBoolean(true)
        val latch = CountDownLatch(1)
        AppExecutors.Default.execute {
            threadIsMain.set(Looper.myLooper() == Looper.getMainLooper())
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(false, threadIsMain.get(), "Default dispatcher must not execute on main thread")
    }

    @Test
    fun `IO executor runs submitted tasks`() {
        val ran = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        AppExecutors.IO.execute {
            ran.set(true)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(ran.get())
    }

    @Test
    fun `mainHandler is bound to the main looper`() {
        val handler = AppExecutors.mainHandler()
        assertNotNull(handler)
        assertSame(Looper.getMainLooper(), handler.looper)
    }

    @Test
    fun `mainHandler returns the same cached instance`() {
        val h1 = AppExecutors.mainHandler()
        val h2 = AppExecutors.mainHandler()
        assertSame(h1, h2, "mainHandler() should return cached singleton")
    }

    @Test
    fun `mainHandler post delivers runnable on main thread`() {
        val result = AtomicReference<Boolean>()
        AppExecutors.mainHandler().post {
            result.set(Looper.myLooper() == Looper.getMainLooper())
        }
        // Drain Robolectric's main looper to execute posted tasks.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, result.get())
    }

    @Test
    fun `Default and IO are distinct executor instances`() {
        // They're both backed by coroutine dispatchers but should be different handles.
        assertNotSame(AppExecutors.Default, AppExecutors.IO)
    }
}
