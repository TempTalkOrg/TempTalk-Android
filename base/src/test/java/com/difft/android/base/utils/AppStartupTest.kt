package com.difft.android.base.utils

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue









@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppStartupTest {

    @After
    fun tearDown() {
        AppStartup.reset()
    }

    @Test
    fun `blocking task throws — subsequent blocking task still runs`() {
        val second = AtomicBoolean(false)

        AppStartup
            .addBlocking("throwing") { throw RuntimeException("boom") }
            .addBlocking("recording") { second.set(true) }
            .execute()

        assertTrue(second.get(), "Subsequent blocking task must run after a prior task throws (#863)")
    }

    @Test
    fun `execute does not propagate blocking task failures`() {
        val ran = AtomicBoolean(false)

        AppStartup
            .addBlocking("throwing") {
                ran.set(true)
                throw RuntimeException("boom")
            }
            .execute()

        assertTrue(ran.get(), "throwing task must have actually been invoked")
        
    }

    @Test
    fun `multiple blocking tasks fail in sequence — all later tasks still run`() {
        val third = AtomicBoolean(false)

        AppStartup
            .addBlocking("throwing-1") { throw RuntimeException("boom-1") }
            .addBlocking("throwing-2") { throw IllegalStateException("boom-2") }
            .addBlocking("recording") { third.set(true) }
            .execute()

        assertTrue(third.get())
    }

    @Test
    fun `non-blocking task throws — other non-blocking still runs`() {
        val latch = CountDownLatch(1)

        AppStartup
            .addNonBlocking("boom") { throw RuntimeException("boom") }
            .addNonBlocking("countDown") { latch.countDown() }
            .execute()

        assertTrue(
            latch.await(2, TimeUnit.SECONDS),
            "Subsequent non-blocking task must run after a prior task throws"
        )
    }

    @Test
    fun `Error subclass — caught and chain continues`() {
        
        
        val second = AtomicBoolean(false)

        AppStartup
            .addBlocking("error-thrower") { throw NoClassDefFoundError("simulated") }
            .addBlocking("recording") { second.set(true) }
            .execute()

        assertTrue(second.get(), "Catch must cover Throwable so that Errors do not cascade-abort the chain")
    }
}
