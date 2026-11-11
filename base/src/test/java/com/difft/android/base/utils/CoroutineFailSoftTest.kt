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
