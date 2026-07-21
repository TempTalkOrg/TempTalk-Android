package com.difft.android.base.utils

import com.difft.android.base.log.WCDBKeyUnavailableException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Contract tests for [dbKeyFailSoftExceptionHandler]: a [WCDBKeyUnavailableException] thrown on a
 * scope carrying the handler is swallowed (never reaches the thread's uncaught handler); any other
 * throwable is forwarded so unrelated bugs keep crashing; and a key failure thrown from a nested
 * `launch` is swallowed the same way.
 *
 * `job.join()` resumes only after the coroutine's completion (incl. the CEH), so assertions are
 * deterministic with no sleeps. The forward target is captured via a global default uncaught
 * handler (the CEH runs on a Dispatchers.IO thread with no per-thread handler, so it falls through
 * to the default).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CoroutineFailSoftTest {

    private val forwarded = AtomicReference<Throwable?>(null)
    private var previousDefault: Thread.UncaughtExceptionHandler? = null

    private fun scope() = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("testScope") + dbKeyFailSoftExceptionHandler
    )

    @Before
    fun setUp() {
        previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> forwarded.set(throwable) }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefault)
    }

    @Test
    fun `CEH1 swallows WCDBKeyUnavailableException`() {
        val ex = WCDBKeyUnavailableException("boom", IllegalStateException("keystore down"))

        val job = scope().launch { throw ex }
        runBlocking { job.join() }

        assertNull(forwarded.get(), "our type must NOT reach the thread uncaught handler")
    }

    @Test
    fun `CEH2 forwards any other throwable to the thread uncaught handler`() {
        val ex = IllegalStateException("unrelated bug")

        val job = scope().launch { throw ex }
        runBlocking { job.join() }

        assertSame(ex, forwarded.get(), "non-our-type must be forwarded, not swallowed")
    }

    @Test
    fun `CEH3 swallows WCDBKeyUnavailableException thrown from a nested launch`() {
        val ex = WCDBKeyUnavailableException("nested boom")
        var callerCatchHit = false

        try {
            val job = scope().launch { launch { throw ex } }
            runBlocking { job.join() }
        } catch (e: Throwable) {
            callerCatchHit = true
        }

        assertFalse(callerCatchHit, "nested key failure must not surface to the launching caller")
        assertNull(forwarded.get(), "nested our-type must be swallowed, not forwarded")
    }
}
