package com.difft.android.call.session

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallViewModelFixture
import com.difft.android.call.core.CallUiController
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * [CallSessionHolder] ownership (one live session; a start while one is live is refused) and
 * [CallSession.end] ordering, and how the session's OnGoingCallStateManager presence relates to it.
 * Rows: T2-01..T2-07, T2-21, T5-08, T5-15 (holder half; the fence half is in OngoingCallProjectionTest).
 */
class CallSessionHolderTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `T2-01 start publishes the new session as current and keeps its params`() {
        val params = h.params()

        val session = checkNotNull(h.holder.start(params))

        assertSame(session, h.holder.current.value)
        assertSame(params, session.params)
    }

    // One call at a time: a second start creates nothing and leaves the live session as it was.
    @Test
    fun `T2-02 start while a session is live is refused and leaves the live session untouched`() {
        val warnings = captureLogs(warn = true)
        mockkConstructor(ScreenSharePreWarmer::class)
        every { anyConstructed<ScreenSharePreWarmer>().cleanupAll() } returns Unit
        val s1 = h.start()

        val s2 = h.holder.start(h.params())

        assertNull(s2)
        assertEquals("nothing was created for the refused start", listOf(s1), h.sessions)
        assertSame(s1, h.holder.current.value)
        assertTrue("S1 job must stay active", s1.scope.isActive)
        assertTrue("S1 must not start cleanup", h.cleanupStarts.isEmpty())
        verify(exactly = 0) { anyConstructed<ScreenSharePreWarmer>().cleanupAll() }
        verify(exactly = 1) { h.ongoingCallService.start(any()) }
        assertEquals(1, warnings.count { it.contains("start refused") && it.namesId(s1.id) })
        assertTrue("S1 still owns the projection", h.ogsm.publish(s1.id) { setCurrentRoomId("r1") })
    }

    @Test
    fun `T5-08 the call is presented from the session's start until its end`() {
        val session = h.start()
        assertTrue("isInCalling from the start, before any page", h.ogsm.isInCalling())

        session.end(CallSessionEndReason.EXIT_CLEAR)

        assertNull(h.holder.current.value)
        assertFalse("the session's end ends its presence", h.ogsm.isInCalling())
    }

    @Test
    fun `T5-15 a fresh process has no current session and nothing presented`() {
        val holder = CallSessionHolder(h.factory)
        val ogsm = OnGoingCallStateManager()

        assertNull(holder.current.value)
        assertFalse("the value LCallActivity's restore check reads", ogsm.isInCalling())
        assertFalse(ogsm.publish(1L) { setIsInCalling(true) })
        assertFalse(ogsm.isInCalling())
    }

    @Test
    fun `T2-03 end EXIT_CLEAR clears current, keeps the job and starts the doExitClear cleanup once`() {
        val session = h.start()

        session.end(CallSessionEndReason.EXIT_CLEAR)

        assertNull(h.holder.current.value)
        assertTrue(session.scope.isActive)
        assertEquals(listOf("doExitClear"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `T2-04 a second end after EXIT_CLEAR is a no-op that neither cancels the job nor starts cleanup again`() {
        mockkConstructor(ScreenSharePreWarmer::class)
        every { anyConstructed<ScreenSharePreWarmer>().cleanupAll() } returns Unit
        val session = h.start()

        session.end(CallSessionEndReason.EXIT_CLEAR)
        session.end(CallSessionEndReason.NO_UI)

        assertTrue("the job lives until the last cleanup step", session.scope.isActive)
        verify(exactly = 0) { anyConstructed<ScreenSharePreWarmer>().cleanupAll() }
        assertEquals(listOf("doExitClear"), h.cleanupStarts.map { it.first })
        assertNull(h.holder.current.value)
    }

    @Test
    fun `T2-05 a first end NO_UI keeps the job until the last cleanup step`() {
        val session = h.start()

        session.end(CallSessionEndReason.NO_UI)

        assertTrue(session.scope.isActive)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
        assertEquals("cancelSessionScope", h.cleanupStarts.single().second.last().name)
        assertNull(h.holder.current.value)
    }

    @Test
    fun `the session starts the ongoing-call service keyed by its id and its first end stops it before the clear`() {
        val session = h.start()
        verify(exactly = 1) { h.ongoingCallService.start(session.id) }
        var currentAtStop: CallSession? = null
        every { h.ongoingCallService.stop(session.id, any()) } answers { currentAtStop = h.holder.current.value }

        session.end(CallSessionEndReason.EXIT_CLEAR)
        session.end(CallSessionEndReason.NO_UI)

        verify(exactly = 1) { h.ongoingCallService.stop(any(), any()) }
        verify(exactly = 1) { h.ongoingCallService.stop(session.id, any()) }
        assertSame("stopped while still current", session, currentAtStop)
        assertNull(h.holder.current.value)
    }

    @Test
    fun `T2-06 once a session ends the next one starts, and the ended one's repeated end touches nothing`() {
        val s1 = h.start()
        s1.end(CallSessionEndReason.EXIT_CLEAR)

        val s2 = h.start()
        s1.end(CallSessionEndReason.NO_UI)

        assertSame(s2, h.holder.current.value)
        assertTrue(s2.scope.isActive)
        // The only executor start is S1's own; S2's executor was never started.
        assertEquals(listOf("doExitClear"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `T2-07 holder end delegates to the current session and is a logged no-op with none`() {
        val infos = captureLogs(warn = false)
        val session = h.start()

        h.holder.end(CallSessionEndReason.EXIT_CLEAR)

        assertNull(h.holder.current.value)
        assertEquals(listOf("doExitClear"), h.cleanupStarts.map { it.first })

        h.holder.end(CallSessionEndReason.NO_UI)

        assertEquals("no current session -> no further cleanup start", 1, h.cleanupStarts.size)
        assertTrue("the ended session is not reached through the holder", session.scope.isActive)
        assertEquals(1, infos.count { it.contains("end(noUi) with no current session") })
    }

    // A real VM attaches to the session, so a captured VM or its UI controller would show up.
    @Test
    fun `T2-21 an ended session reaches no ViewModel, Activity, non-Application Context or CallUiController`() {
        LCallViewModelFixture.build(h)
        val session = checkNotNull(h.holder.current.value)

        session.end(CallSessionEndReason.NO_UI)

        assertNull(h.holder.current.value)
        val leaks = (leakingValues(session) + leakingValues(session.graph)).distinct()
        assertTrue("leaking references: $leaks", leaks.isEmpty())
    }

    @Test
    fun `T2-21 the end controller reaches no UI once the last page detaches and the session ends`() {
        LCallViewModelFixture.build(h)
        val session = checkNotNull(h.holder.current.value)
        val controller = session.graph.endController
        session.attachUi()

        session.detachUi()

        session.end(CallSessionEndReason.NO_UI)
        val leaks = leakingValues(controller)
        assertTrue("leaking references: $leaks", leaks.isEmpty())
    }

    /** Records the messages of `L.w { }` ([warn]) or `L.i { }` calls; other log calls run unchanged. */
    private fun captureLogs(warn: Boolean): List<String> {
        val messages = Collections.synchronizedList(mutableListOf<String>())
        // mockkStatic: L's entry points are @JvmStatic, so call sites go through the static bridge.
        mockkStatic(L::class)
        if (warn) {
            every { L.w(any<() -> String>()) } answers { messages += firstArg<() -> String>().invoke() }
        } else {
            every { L.i(any<() -> String>()) } answers { messages += firstArg<() -> String>().invoke() }
        }
        return messages
    }

    private fun String.namesId(id: Long) = Regex("\\bid=$id\\b").containsMatchIn(this)

    /** `"field -> class"` for each forbidden value among [root]'s fields and the fields one level below. */
    private fun leakingValues(root: Any): List<String> = fieldValues(root).flatMap { (name, value) ->
        val nested = if (isScannable(value)) fieldValues(value).mapNotNull { (n, v) -> forbidden("$name.$n", v) } else emptyList()
        listOfNotNull(forbidden(name, value)) + nested
    }

    private fun forbidden(name: String, value: Any): String? {
        val leak = value is ViewModel || value is Activity || (value is Context && value !is Application) ||
            value is CallUiController
        return if (leak) "$name -> ${value.javaClass.name}" else null
    }

    /** JDK internals cannot be opened reflectively and never hold app objects. */
    private fun isScannable(value: Any) = !value.javaClass.name.startsWith("java.") && !value.javaClass.isArray

    private fun fieldValues(obj: Any): List<Pair<String, Any>> =
        generateSequence<Class<*>>(obj.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && runCatching { it.isAccessible = true }.isSuccess }
            .mapNotNull { f -> f.get(obj)?.let { f.name to it } }
            .toList()
}
