package com.difft.android.call.session

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.R
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.launchFinishRequestCollector
import com.difft.android.call.manager.CallDataManager
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
import java.util.Collections
import kotlin.concurrent.thread

/**
 * [CallEndController] as the session graph builds it: the no-page toast is posted to the session's
 * main dispatcher even when `finish` runs on another thread (the exit handler finishes from
 * `appScope`), the no-page path ends the session, the page's own exit path finishes through the
 * handler the graph builds, and a page that starts collecting late still finishes exactly once.
 * Robolectric only because `ToastUtil` and the finish collector's `LifecycleRegistry` need a main
 * Looper; the session's main dispatcher is the test dispatcher (run on the main thread).
 * An off-main finish that read an attached page re-checks on main, so a page that detached in
 * between still leaves the session ending. Rows: T4-09 (off-main clause), T4-26, T4-28.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallEndControllerSessionTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness

    /** `(text, ranOnMainLooper)` of every toast shown. */
    private val toasts = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
        mockkObject(ToastUtil)
        every { ToastUtil.show(any<String>()) } answers {
            toasts += firstArg<String>() to (Looper.myLooper() == Looper.getMainLooper())
        }
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `T4-09 a no-page finish from a worker thread toasts on main and then ends the session`() {
        val session = h.start()

        thread(name = "exit-handler-appScope") {
            session.graph.endController.finish(CallFinishRequest.remote(CallActionType.HANGUP))
        }.join()

        assertTrue("nothing runs on the worker thread", toasts.isEmpty())
        assertSame(session, h.holder.current.value)

        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(hangupTip() to true), toasts)
        assertNull(h.holder.current.value)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
        assertEquals(true, session.finishRequest.value?.handledWithoutUi)
    }

    @Test
    fun `an off-main finish that races the last page detach still ends the session without a page`() {
        val session = h.start()
        session.attachUi()

        // The finish reads one attached page; the page detaches before the session's main pass runs.
        thread(name = "exit-handler-appScope") {
            session.graph.endController.finish(CallFinishRequest.remote(CallActionType.HANGUP))
        }.join()
        // Only the count drops: the session's own last-detach check ran before the request was set.
        session.graph.endController.detachUi()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(hangupTip() to true), toasts)
        assertNull(h.holder.current.value)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `an off-main finish followed by the session's last detach tips and ends once`() {
        val session = h.start()
        session.attachUi()

        thread(name = "exit-handler-appScope") {
            session.graph.endController.finish(CallFinishRequest.remote(CallActionType.HANGUP))
        }.join()
        session.detachUi()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(hangupTip() to true), toasts)
        assertNull(h.holder.current.value)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `T4-26 the page's E2 exit finishes LOCAL and marks the call ending synchronously`() {
        h.components.callStatus.value = CallStatus.CONNECTED
        every { h.deps.callDataManager } returns CallDataManager() // no CallData: E2
        val session = h.start()
        session.attachUi()

        session.requestExit(exitParams(), CallEndType.LEAVE)

        assertEquals(CallFinishRequest.LOCAL, session.finishRequest.value)
        assertTrue(h.ogsm.isInCallEnding())
        testDispatcher.scheduler.runCurrent()
        assertTrue("a page is attached: no tip, no self-end", toasts.isEmpty())
        assertSame(session, h.holder.current.value)
    }

    @Test
    fun `T4-28a a page attached before the remote end receives the sticky request when it starts collecting`() {
        val session = h.start()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)
        session.attachUi()
        session.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        testDispatcher.scheduler.runCurrent()
        val tips = mutableListOf<String>()
        var endCalls = 0

        createdOwner().launchFinishRequestCollector(
            finishRequest = session.finishRequest,
            showTip = { message, onDismiss -> tips += message; onDismiss() },
            endCall = { endCalls++ },
        )
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(hangupTip()), tips)
        assertEquals(1, endCalls)
        assertTrue("no no-page toast", toasts.isEmpty())
        assertSame("the page ends the session, not the controller", session, h.holder.current.value)
    }

    @Test
    fun `T4-28b with no page attached the session tips once on main and a later page only finishes`() {
        val session = h.start()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)

        session.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(hangupTip() to true), toasts)
        assertNull(h.holder.current.value)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })

        val tips = mutableListOf<String>()
        var endCalls = 0
        session.attachUi()
        createdOwner().launchFinishRequestCollector(
            finishRequest = session.finishRequest,
            showTip = { message, onDismiss -> tips += message; onDismiss() },
            endCall = { endCalls++ },
        )
        testDispatcher.scheduler.runCurrent()

        assertTrue("no second tip", tips.isEmpty())
        assertEquals(1, endCalls)
        assertEquals(1, toasts.size)
    }

    private fun hangupTip() = ResUtils.getString(R.string.call_callee_action_hangup)

    /** A page that is created but never started, as a backgrounded call page is. */
    private fun createdOwner(): LifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun exitParams() = CallExitParams(
        roomId = ROOM_ID,
        callerId = "caller-1",
        callRole = CallRole.CALLER,
        callType = CallType.ONE_ON_ONE.type,
        conversationId = "conv-1",
    )

    private companion object {
        const val ROOM_ID = "r1"
    }
}
