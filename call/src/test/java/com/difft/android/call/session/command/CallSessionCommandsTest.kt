package com.difft.android.call.session.command

import com.difft.android.base.call.CallActionType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionDispatchers
import com.difft.android.call.session.CallSessionHolder
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

/**
 * [CallSessionCommands], the out-of-page entry point: with no live session a command is dropped
 * with a log, and a command issued off the main thread reaches the session only once the main
 * dispatcher runs, on that dispatcher's thread. Rows: T4-12, T4-13.
 */
class CallSessionCommandsTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private val dispatchers = CallSessionDispatchers(default = testDispatcher, main = testDispatcher)

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `T4-12 with no live session both commands are dropped with a log and reach nothing`() {
        val drops = Collections.synchronizedList(mutableListOf<String>())
        mockkStatic(L::class)
        every { L.i(any<() -> String>()) } answers { drops += firstArg<() -> String>().invoke() }
        val factory = mockk<CallSession.Factory>(relaxed = true)
        val commands = CallSessionCommands(CallSessionHolder(factory), dispatchers)

        commands.hangup(HangupReason.NOTIFICATION)
        commands.onRemoteControl(CallActionType.HANGUP, "r1")
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 0) { factory.create(any(), any()) }
        assertEquals(
            listOf("[Call][Cmd] hangup(notification) dropped: no live session", "[Call][Cmd] remote(hangup) dropped: no live session"),
            drops.filter { it.startsWith("[Call][Cmd]") },
        )
    }

    @Test
    fun `T4-13 an off-main remote end reaches the session only when the main dispatcher runs, on its thread`() {
        val calledOn = Collections.synchronizedList(mutableListOf<Thread>())
        val session = mockk<CallSession>(relaxed = true) {
            every { start() } returns true
            every { onRemoteControl(any(), any()) } answers { calledOn += Thread.currentThread() }
        }
        val holder = CallSessionHolder(mockk { every { create(any(), any()) } returns session })
        holder.start(CallSessionTestHarness(testDispatcher).params())
        val commands = CallSessionCommands(holder, dispatchers)

        thread(name = "call-msg") { commands.onRemoteControl(CallActionType.CALLEND, "r1") }.join()

        assertTrue("not run on the calling thread", calledOn.isEmpty())

        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { session.onRemoteControl(CallActionType.CALLEND, "r1") }
        assertEquals(listOf(Thread.currentThread()), calledOn.toList())
    }
}
