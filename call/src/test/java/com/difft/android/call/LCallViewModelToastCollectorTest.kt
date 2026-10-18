package com.difft.android.call

import androidx.lifecycle.ViewModelStore
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.CallSessionUiEvent
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.ui.barrage.BarrageRenderable
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Toast handling of the VM's session-event collector. Robolectric only because `ToastUtil` builds a
 * main-Looper `Handler` when its object initializes; the session and VM are the same JVM fixtures
 * as `LCallViewModelDelegationTest`. Rows: T3-09, T3-24.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class LCallViewModelToastCollectorTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness
    private val collectorScope = CoroutineScope(testDispatcher)

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
        mockkObject(ToastUtil)
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `T3-09 a failing toast does not stop the event collector`() {
        every { ToastUtil.show(any<String>()) } throws IllegalStateException("no window")
        val vm = LCallViewModelFixture.build(h)
        val barrages = mutableListOf<BarrageMessage>()
        collectorScope.launch { vm.callUiController.barrageMessage.collect { barrages += it } }
        testDispatcher.scheduler.runCurrent()
        val ui = h.uiWriter(h.sessions.single())
        val message = BarrageMessage("Bob", "m", 1L)

        ui.emit(CallSessionUiEvent.Toast("x"))
        ui.emit(CallSessionUiEvent.Barrage(BarrageRenderable.Default(message)))
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { ToastUtil.show("x") }
        assertEquals(listOf(message), barrages)
    }

    @Test
    fun `T3-24 an event after the VM is cleared shows nothing and does not throw`() {
        every { ToastUtil.show(any<String>()) } just Runs
        val vm = LCallViewModelFixture.build(h)
        val ui = h.uiWriter(h.sessions.single())

        ViewModelStore().apply {
            put("call-vm", vm)
            clear()
        }
        ui.emit(CallSessionUiEvent.Toast("late"))
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 0) { ToastUtil.show(any<String>()) }
    }
}
