package com.difft.android.call.session

import com.difft.android.call.data.CallEndType
import com.difft.android.call.handler.CallExitHandler
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The first-attach watchdog: a session no call page attaches to within 10 s (a blocked launch, a
 * page that finished before its view model existed) leaves the call through the exit decision
 * with LEAVE, and when that signaling never settles the exit fallback ends it locally 10 s later.
 * An attach disarms it; a session that ended or is ending is never left a second time.
 */
class CallSessionWatchdogTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
        // The exit decision itself is CallExitHandlerTest's; here only whether it runs, and how.
        mockkConstructor(CallExitHandler::class)
        every { anyConstructed<CallExitHandler>().handleExit(any(), any()) } just runs
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `no page within 10 s leaves the call once with LEAVE`() {
        h.start()

        advance(WATCHDOG_MS - 1)
        verify(exactly = 0) { anyConstructed<CallExitHandler>().handleExit(any(), any()) }

        advance(1)
        verify(exactly = 1) { anyConstructed<CallExitHandler>().handleExit(any(), CallEndType.LEAVE) }

        advance(WATCHDOG_MS * 3)
        verify(exactly = 1) { anyConstructed<CallExitHandler>().handleExit(any(), any()) }
    }

    @Test
    fun `a page attaching disarms it, also after that page detaches`() {
        val session = h.start()
        advance(WATCHDOG_MS / 2)

        session.attachUi()
        session.detachUi()
        advance(WATCHDOG_MS * 2)

        verify(exactly = 0) { anyConstructed<CallExitHandler>().handleExit(any(), any()) }
        assertSame(session, h.holder.current.value)
    }

    @Test
    fun `a session that ended before the deadline is not left`() {
        val session = h.start()
        advance(WATCHDOG_MS / 2)

        session.end(CallSessionEndReason.EXIT_CLEAR)
        advance(WATCHDOG_MS * 2)

        verify(exactly = 0) { anyConstructed<CallExitHandler>().handleExit(any(), any()) }
    }

    @Test
    fun `a session already ending at the deadline is not left`() {
        val session = h.start()
        session.markEnding()

        advance(WATCHDOG_MS * 2)

        verify(exactly = 0) { anyConstructed<CallExitHandler>().handleExit(any(), any()) }
    }

    @Test
    fun `a leave whose signaling never settles ends the session locally 10 s later`() {
        val session = h.start()
        advance(WATCHDOG_MS)
        verify(exactly = 1) { anyConstructed<CallExitHandler>().handleExit(any(), CallEndType.LEAVE) }
        assertSame(session, h.holder.current.value)

        advance(EXIT_FALLBACK_MS)

        assertEquals(CallFinishRequest.Reason.LOCAL_EXIT, session.finishRequest.value?.reason)
        assertNull(h.holder.current.value)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
    }

    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private companion object {
        const val WATCHDOG_MS = 10_000L
        const val EXIT_FALLBACK_MS = 10_000L
    }
}
