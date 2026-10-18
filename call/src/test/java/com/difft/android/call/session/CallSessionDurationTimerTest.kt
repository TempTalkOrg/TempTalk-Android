package com.difft.android.call.session

import com.difft.android.call.manager.TimerManager
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.session.harness.PhaseBCollaboratorStubs
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * [CallSessionGraph.startCallDurationTimer] hands the timer the connect instant the session's fenced
 * write left in OnGoingCallStateManager, the value the ongoing-call notification publishes as `when`,
 * so the on-screen duration and the system call chip share one origin.
 */
class CallSessionDurationTimerTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
        PhaseBCollaboratorStubs.install()
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `the timer counts from the connect instant the projection publishes`() = runTest(testDispatcher) {
        val session = h.start()
        session.graph.onRoomIdAssigned("r1")
        // Already stamped 65 s back: markCallConnected keeps it, and the timer must count from it.
        val stamped = System.currentTimeMillis() - 65_000
        setProjectedConnectedAt(stamped)

        session.startCallDurationTimer()

        assertEquals("not re-stamped", stamped, h.ogsm.callConnectedAt())
        assertEquals(stamped, timerConnectedAt(session))
        // Read live between ticks, as the feedback binder's 60 s gate does at teardown.
        val seconds = session.graph.timerManager.getCurrentDuration()
        assertTrue("live duration from the published instant: $seconds", seconds in 65L..75L)
        endSessionBeforeDrain()
    }

    @Test
    fun `a fresh connect stamps the projection and the timer receives that same instant`() = runTest(testDispatcher) {
        val session = h.start()

        session.startCallDurationTimer()

        val connectedAt = h.ogsm.callConnectedAt()
        assertTrue(connectedAt > 0)
        assertEquals(connectedAt, timerConnectedAt(session))
        endSessionBeforeDrain()
    }

    @Test
    fun `a session that no longer owns the projection counts from now`() = runTest(testDispatcher) {
        val session = h.start()
        session.graph.publisher.endPresence()

        val before = System.currentTimeMillis()
        session.startCallDurationTimer()

        assertEquals("the dropped write stamps nothing", 0L, h.ogsm.callConnectedAt())
        assertTrue(timerConnectedAt(session) >= before)
        assertTrue(session.graph.timerManager.callTimerRunning.value)
        endSessionBeforeDrain()
    }

    /** The timer re-arms every virtual second on this test's scheduler; ending the session cancels it before runTest drains. */
    private fun endSessionBeforeDrain() = h.endAll()

    private fun setProjectedConnectedAt(millis: Long) {
        @Suppress("UNCHECKED_CAST")
        val flow = OnGoingCallStateManager::class.java.getDeclaredField("_callConnectedAt")
            .apply { isAccessible = true }.get(h.ogsm) as MutableStateFlow<Long>
        flow.value = millis
    }

    private fun timerConnectedAt(session: CallSession): Long =
        TimerManager::class.java.getDeclaredField("callConnectedAtMillis")
            .apply { isAccessible = true }.getLong(session.graph.timerManager)
}
