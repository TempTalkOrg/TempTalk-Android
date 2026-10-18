package com.difft.android.call.session

import com.difft.android.base.call.CallActionType
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.R
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.exception.StartCallException
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.state.OgsmTestState
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room errors are handled by the session from start to end, page or not: a tip as an app toast,
 * then the end of the call. No page lifecycle gates it, a pending delayed end outlives the page,
 * and nothing is done once the call is ending or ended. Robolectric for `ToastUtil` and resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallSessionErrorTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness
    private val toasts = mutableListOf<String>()

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
        mockkObject(ToastUtil)
        every { ToastUtil.show(any<String>()) } answers { toasts += firstArg<String>() }
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `an error with no page shows an app toast, then ends the call`() {
        val session = h.start()

        raise(StartCallException("start refused"))

        assertEquals(listOf("start refused"), toasts)
        assertTrue(session.isEnded)
        assertNull(h.holder.current.value)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `an error with a page attached is handled at once, whatever the page's lifecycle`() {
        val session = h.startWithPage()

        raise(DisconnectException("gone"))

        assertEquals(listOf(ResUtils.getString(R.string.call_room_connect_exception_error)), toasts)
        assertEquals("the page renders the end", CallFinishRequest.LOCAL, session.finishRequest.value)
        assertSame(session, h.holder.current.value)
    }

    @Test
    fun `a delayed error end outlives the page that was attached when it was raised`() {
        val session = h.startWithPage()
        raise(IllegalStateException("late failure"))
        assertTrue(toasts.isEmpty())

        session.detachUi()
        testDispatcher.scheduler.advanceTimeBy(DELAYED_END_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("late failure"), toasts)
        assertNull(h.holder.current.value)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `a delayed error end does nothing once the call is ending`() {
        val session = h.startWithPage()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)
        raise(IllegalStateException("late failure"))

        session.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        testDispatcher.scheduler.advanceTimeBy(DELAYED_END_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertTrue(toasts.isEmpty())
        assertEquals(CallFinishRequest.remote(CallActionType.HANGUP), session.finishRequest.value)
    }

    @Test
    fun `a disconnect error after a remote end shows nothing`() {
        val session = h.startWithPage()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)
        session.onRemoteControl(CallActionType.HANGUP, ROOM_ID)

        raise(DisconnectException("room closed"))

        assertTrue(toasts.isEmpty())
        assertEquals(CallFinishRequest.remote(CallActionType.HANGUP), session.finishRequest.value)
    }

    @Test
    fun `the session's end stops error handling, a pending delayed end included`() {
        val session = h.startWithPage()
        raise(IllegalStateException("late failure"))

        session.end(CallSessionEndReason.EXIT_CLEAR)
        raise(StartCallException("after end"))
        testDispatcher.scheduler.advanceTimeBy(DELAYED_END_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertTrue(toasts.isEmpty())
        assertEquals(listOf("doExitClear"), h.cleanupStarts.map { it.first })
    }

    private fun raise(error: Throwable) {
        h.components.errors.trySend(error)
        testDispatcher.scheduler.runCurrent()
    }

    private companion object {
        const val ROOM_ID = "r1"
        const val DELAYED_END_MS = 2_000L
    }
}
