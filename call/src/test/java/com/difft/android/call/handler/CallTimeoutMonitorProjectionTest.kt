package com.difft.android.call.handler

import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.appScope
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallTimeoutManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.EndControllerRig
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CallTimeoutMonitor] reading call presence from `OnGoingCallStateManager`: the 1v1 ongoing-call
 * timeout callback fires once for the live room and not once the call is ending, and the remote-left
 * timer is armed only while the call is up. Rows: T5-10, T5-11.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallTimeoutMonitorProjectionTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private val timeouts = mutableListOf<String>()
    private val timers = TestScope(StandardTestDispatcher())
    private val ogsm = OnGoingCallStateManager()
    private val roomCtl = mockk<CallRoomController>(relaxed = true) {
        every { callStatus } returns MutableStateFlow(CallStatus.CALLING)
        every { callType } returns MutableStateFlow(CallType.ONE_ON_ONE.type)
    }

    @Before
    fun setUp() {
        every { appScope } returns timers
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `T5-10 the 1v1 timeout calls back once for the live room after 60 s`() {
        OgsmTestState.enterCall(ogsm, ROOM_ID)
        val monitor = monitor(CallTimeoutManager(mockk(relaxed = true)))

        monitor.start1V1Timeout(ROOM_ID)
        timers.testScheduler.advanceTimeBy(TIMEOUT_MS)
        timers.testScheduler.runCurrent()

        assertEquals(listOf(ROOM_ID), timeouts)
    }

    @Test
    fun `T5-10 once the call is ending the timeout calls back nothing`() {
        OgsmTestState.enterCall(ogsm, ROOM_ID)
        val monitor = monitor(CallTimeoutManager(mockk(relaxed = true)))
        monitor.start1V1Timeout(ROOM_ID)

        EndControllerRig(ogsm = ogsm).controller.markEnding()
        timers.testScheduler.advanceTimeBy(TIMEOUT_MS)
        timers.testScheduler.runCurrent()

        assertTrue(timeouts.isEmpty())
    }

    @Test
    fun `T5-11 a remote leave arms the leave timer only while the call is up`() {
        val manager = mockk<CallTimeoutManager>(relaxed = true)
        val monitor = monitor(manager)

        monitor.onParticipantDisconnected(isOneOnOne = true)
        verify(exactly = 0) { manager.checkCallWithTimeout(any(), any(), any(), any()) }

        OgsmTestState.enterCall(ogsm, ROOM_ID)
        monitor.onParticipantDisconnected(isOneOnOne = true)
        verify(exactly = 1) {
            manager.checkCallWithTimeout(CallTimeoutManager.CallState.LEAVE_CALL, CallTimeoutManager.DEF_LEAVE_CALL_TIMEOUT, ROOM_ID, any())
        }
    }

    private fun monitor(manager: CallTimeoutManager) = CallTimeoutMonitor(
        roomCtl = roomCtl,
        callTimeoutManager = manager,
        onGoingCallStateManager = ogsm,
        callRole = CallRole.CALLER,
        roomIdGetter = { ROOM_ID },
        onTimeout = { timeouts += it },
    )

    private companion object {
        const val ROOM_ID = "r1"
        const val TIMEOUT_MS = CallTimeoutManager.DEF_ONGOING_CALL_TIMEOUT * 1_000
    }
}
