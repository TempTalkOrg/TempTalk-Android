package com.difft.android.call.connect

import com.difft.android.base.utils.ResUtils
import com.difft.android.call.LCallEngine
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.CallSessionUiEvent
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Manual server-switch rejection toast as session UI output: the session graph's own
 * [CallConnectionCoordinator] observed with the graph's `showToast` binding, as Phase B wires it.
 * Row: T3-21.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CallConnectionCoordinatorManualSwitchToastTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness
    private val collector = CoroutineScope(testDispatcher)

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        collector.cancel()
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `T3-21 a node switch with only self in the room emits the forbid-only-self toast`() {
        mockkObject(ResUtils, LCallEngine)
        every { ResUtils.getString(R.string.call_server_node_switch_forbid_only_self) } returns "only self"
        every { LCallEngine.serverNodeSelected } returns MutableStateFlow(mockk(relaxed = true))
        every { LCallEngine.connectionType } returns MutableStateFlow(CONNECTION_TYPE.WEB_SOCKET)
        every { LCallEngine.isUseQuicSignal() } returns false
        every { LCallEngine.resetSelectedServerNode() } just Runs
        h.components.callStatus.value = CallStatus.CONNECTED
        every { h.components.roomCtl.isUseQuicSignal() } returns false
        every { h.components.roomCtl.hasRemoteParticipants() } returns false
        val session = h.start()
        val graph = session.graph
        val events = mutableListOf<CallSessionUiEvent>()
        collector.launch { session.uiState.events.collect { events += it } }

        graph.connectionCoordinator.observeManualSwitchReconnect(
            scope = graph.mainScope,
            callIntent = graph.params.callIntent,
            roomIdGetter = { graph.roomId },
            showToast = graph::showToast,
        )
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf<CallSessionUiEvent>(CallSessionUiEvent.Toast("only self")), events)
    }
}
