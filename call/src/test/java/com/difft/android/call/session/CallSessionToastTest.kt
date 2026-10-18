package com.difft.android.call.session

import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.harness.CallSessionTestHarness
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
 * Where a session toast goes: through the call page's event collector when a page collects, else
 * straight to an app toast (before the first page, or with no page left). Robolectric because
 * `ToastUtil` builds a main-Looper `Handler` when its object initializes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallSessionToastTest {

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
        every { ToastUtil.show(any<String>()) } just Runs
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `a toast with no page collecting becomes an app toast and is not kept for a later page`() {
        val session = h.start()

        session.showToast("no page")
        val received = collect(session)

        verify(exactly = 1) { ToastUtil.show("no page") }
        assertEquals(emptyList<CallSessionUiEvent>(), received)
    }

    @Test
    fun `a toast with a page collecting goes to the page only`() {
        val session = h.start()
        val received = collect(session)

        session.showToast("on page")
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(CallSessionUiEvent.Toast("on page")), received)
        verify(exactly = 0) { ToastUtil.show(any<String>()) }
    }

    private fun collect(session: CallSession): List<CallSessionUiEvent> {
        val received = mutableListOf<CallSessionUiEvent>()
        collectorScope.launch { session.uiState.events.collect { received += it } }
        testDispatcher.scheduler.runCurrent()
        return received
    }
}
