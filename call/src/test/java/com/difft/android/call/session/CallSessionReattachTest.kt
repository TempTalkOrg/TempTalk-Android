package com.difft.android.call.session

import androidx.lifecycle.ViewModelStore
import com.difft.android.call.LCallViewModel
import com.difft.android.call.LCallViewModelFixture
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.session.harness.PhaseBCollaboratorStubs
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test






class CallSessionReattachTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher, default = testDispatcher)
        PhaseBCollaboratorStubs.install()
        mockkObject(CallWaitDialogUtil)
        every { CallWaitDialogUtil.dismiss(any<Int>()) } just Runs
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `a new page attaches to the live session without resetting it or building a second room`() {
        val first = LCallViewModelFixture.build(h)
        val session = h.sessions.single()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)
        testDispatcher.scheduler.runCurrent()

        destroy(first)
        val live = checkNotNull(h.holder.current.value) { "the destroyed page ended the call" }
        val second = LCallViewModelFixture.build(h, session = live)

        assertEquals(session.id, second.sessionId)
        assertEquals(CallRoomState.Ready(h.components.room), second.roomState.value)
        assertEquals(1, h.components.createRoomCalls)
        assertTrue(h.ogsm.isInCalling())
        assertEquals(ROOM_ID, h.ogsm.getCurrentRoomId())
        assertEquals(1, session.graph.endController.uiAttachCount)
        assertTrue(h.cleanupStarts.isEmpty())
    }

    @Test
    fun `the last page going away after the room resolved releases a leftover entry claim`() {
        val session = h.start(h.params(entryWaitToken = ENTRY_TOKEN))
        session.attachUi()
        testDispatcher.scheduler.runCurrent()
        verify(exactly = 0) { CallWaitDialogUtil.dismiss(ENTRY_TOKEN) }

        session.detachUi()

        verify(exactly = 1) { CallWaitDialogUtil.dismiss(ENTRY_TOKEN) }
        assertSame(session, h.holder.current.value)
    }

    
    private fun destroy(vm: LCallViewModel) = ViewModelStore().apply {
        put("call-vm", vm)
        clear()
    }

    private companion object {
        const val ROOM_ID = "r1"
        const val ENTRY_TOKEN = 5
    }
}
