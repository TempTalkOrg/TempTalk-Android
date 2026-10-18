package com.difft.android.call.session

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.session.CallEndControllerTest.Companion.ROOM_ID
import com.difft.android.call.session.command.HangupReason
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [CallEndController]'s page-less leave and its exit fallback: an out-of-page hang-up or a leave
 * with no page runs the exit decision with LEAVE; when its signaling never settles the call ends
 * locally 10 s later, and a handler that throws ends it at once. The page's own exit arms the same
 * fallback, so a page that went away while its exit was pending still ends the call. The fallback
 * stays quiet once the call finished and never fires after it was cancelled at session end.
 */
class CallEndControllerExitFallbackTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a hang-up whose signaling never settles finishes locally 10 s later`() {
        val rig = EndControllerRig().inCall()

        rig.controller.hangup(HangupReason.NOTIFICATION)
        rig.scope.testScheduler.advanceTimeBy(EXIT_FALLBACK_MS - 1)
        rig.scope.testScheduler.runCurrent()
        assertNull("still waiting on the signaling", rig.controller.finishRequest.value)

        rig.scope.testScheduler.advanceTimeBy(1)
        rig.scope.testScheduler.runCurrent()

        assertEquals(CallFinishRequest.Reason.LOCAL_EXIT, rig.controller.finishRequest.value?.reason)
        verify(exactly = 1) { rig.endSessionWithoutUi.invoke() }
        verify(exactly = 0) { rig.showToast.invoke(any()) }
    }

    @Test
    fun `the exit fallback stays quiet once the signaling finished the call`() {
        val rig = EndControllerRig().inCall()
        rig.controller.hangup(HangupReason.NOTIFICATION)
        rig.controller.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        val settled = rig.controller.finishRequest.value

        rig.scope.testScheduler.advanceUntilIdle()

        assertSame(settled, rig.controller.finishRequest.value)
        verify(exactly = 1) { rig.endSessionWithoutUi.invoke() }
    }

    @Test
    fun `a cancelled exit fallback never fires`() {
        val rig = EndControllerRig().inCall()
        rig.controller.hangup(HangupReason.NOTIFICATION)

        rig.controller.cancelExitFallback()
        rig.scope.testScheduler.advanceUntilIdle()

        assertNull(rig.controller.finishRequest.value)
        verify(exactly = 0) { rig.endSessionWithoutUi.invoke() }
    }

    @Test
    fun `a throwing exit handler finishes the hang-up locally at once`() {
        val rig = EndControllerRig().inCall()
        every { rig.exitHandler.handleExit(any(), any()) } throws IllegalStateException("no handler")

        rig.controller.hangup(HangupReason.NOTIFICATION)

        assertEquals(CallFinishRequest.Reason.LOCAL_EXIT, rig.controller.finishRequest.value?.reason)
        verify(exactly = 1) { rig.endSessionWithoutUi.invoke() }
    }

    @Test
    fun `leaving without a page runs LEAVE even with no room id, once, and is dropped while ending`() {
        val rig = EndControllerRig().inCall(roomId = null)
        val params = slot<CallExitParams>()
        every { rig.exitHandler.handleExit(capture(params), any()) } returns Unit

        rig.controller.leaveWithoutPage()
        rig.controller.leaveWithoutPage()

        verify(exactly = 1) { rig.exitHandler.handleExit(any(), CallEndType.LEAVE) }
        assertNull(params.captured.roomId)

        val ending = EndControllerRig().inCall()
        ending.controller.markEnding()
        ending.controller.leaveWithoutPage()
        verify(exactly = 0) { ending.exitHandler.handleExit(any(), any()) }
    }

    @Test
    fun `an in-page exit whose signaling never settles ends the call 10 s later once the page is gone`() {
        val rig = EndControllerRig().inCall().pageAttached()

        rig.controller.requestExit(exitParams(), CallEndType.END)
        rig.controller.detachUi()
        rig.scope.testScheduler.advanceTimeBy(EXIT_FALLBACK_MS - 1)
        rig.scope.testScheduler.runCurrent()
        assertNull("still waiting on the signaling", rig.controller.finishRequest.value)

        rig.scope.testScheduler.advanceTimeBy(1)
        rig.scope.testScheduler.runCurrent()

        assertEquals(CallFinishRequest.LOCAL.copy(handledWithoutUi = true), rig.controller.finishRequest.value)
        verify(exactly = 1) { rig.endSessionWithoutUi.invoke() }
    }

    @Test
    fun `an in-page exit that finished at once arms no fallback`() {
        val rig = EndControllerRig().inCall().pageAttached()
        every { rig.exitHandler.handleExit(any(), any()) } answers { rig.controller.finish(CallFinishRequest.LOCAL) }

        rig.controller.requestExit(exitParams(), CallEndType.LEAVE)
        val settled = rig.controller.finishRequest.value
        rig.scope.testScheduler.advanceUntilIdle()

        assertEquals("no 10 s timer was scheduled", 0L, rig.scope.testScheduler.currentTime)
        assertSame(settled, rig.controller.finishRequest.value)
        assertEquals("left to the page", false, settled?.handledWithoutUi)
        verify(exactly = 0) { rig.endSessionWithoutUi.invoke() }
    }

    @Test
    fun `a signaling finish after the in-page exit fallback fired is a no-op`() {
        val rig = EndControllerRig().inCall().pageAttached()

        rig.controller.requestExit(exitParams(), CallEndType.LEAVE)
        rig.scope.testScheduler.advanceTimeBy(EXIT_FALLBACK_MS)
        rig.scope.testScheduler.runCurrent()
        val local = rig.controller.finishRequest.value
        rig.controller.finish(CallFinishRequest.remote(CallActionType.HANGUP))

        assertEquals(CallFinishRequest.LOCAL, local)
        assertSame(local, rig.controller.finishRequest.value)
    }

    @Test
    fun `a throwing exit handler finishes the in-page exit locally at once`() {
        val rig = EndControllerRig().inCall()
        every { rig.exitHandler.handleExit(any(), any()) } throws IllegalStateException("no handler")

        rig.controller.requestExit(exitParams(), CallEndType.END)

        assertEquals(CallFinishRequest.Reason.LOCAL_EXIT, rig.controller.finishRequest.value?.reason)
        verify(exactly = 1) { rig.endSessionWithoutUi.invoke() }
    }

    @Test
    fun `everAttached turns true with the first page and stays true after it detaches`() {
        val rig = EndControllerRig()
        assertFalse(rig.controller.everAttached)

        rig.controller.attachUi()
        rig.controller.detachUi()

        assertTrue(rig.controller.everAttached)
    }

    private fun exitParams() = CallExitParams(
        roomId = ROOM_ID,
        callerId = CallEndControllerTest.CALLER_ID,
        callRole = CallRole.CALLER,
        callType = CallType.ONE_ON_ONE.type,
        conversationId = "conv-1",
    )

    private companion object {
        const val EXIT_FALLBACK_MS = 10_000L
    }
}
