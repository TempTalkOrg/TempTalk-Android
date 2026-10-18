package com.difft.android.call

import androidx.lifecycle.ViewModelStore
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.handler.CallTimeoutMonitor
import com.difft.android.call.session.CallRoomState
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionGraph
import com.difft.android.call.session.CallSessionUiEvent
import com.difft.android.call.session.CallSessionUiState
import com.difft.android.call.session.harness.CallIntentFixture
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.session.harness.PhaseBCollaboratorStubs
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.ui.barrage.BarrageRenderable
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.network.UrlManager
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.difft.app.database.models.GroupModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The thin [LCallViewModel] over its [CallSession], including the VM-side collectors of the
 * session's UI output. Rows: T2-19, T3-01, T3-08, T3-15, T3-17, T3-18, T3-25, T3-27, T5-18, T5-23,
 * T5-29, T5-31.
 * The toast rows (T3-09, T3-24) need a real main Looper for `ToastUtil` and live in
 * `LCallViewModelToastCollectorTest`.
 */
class LCallViewModelDelegationTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness
    private val extraHarnesses = mutableListOf<CallSessionTestHarness>()

    /** Collects UI-controller flows on the test Main dispatcher; cancelled in `@After`. */
    private val collectorScope = CoroutineScope(testDispatcher)

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        (extraHarnesses + h).forEach { it.endAll() }
        unmockkAll()
    }

    @Test
    fun `T2-19 VM attaches the started session, doExitClear ends it once and onCleared detaches the page`() {
        val params = h.params()
        mockkConstructor(ScreenSharePreWarmer::class)
        every { anyConstructed<ScreenSharePreWarmer>().cleanupAll() } returns Unit
        val vm = LCallViewModelFixture.build(h, h.start(params))
        val session = h.sessions.single()

        assertSame(params, session.params)
        assertSame(session, h.holder.current.value)
        // Attached from the VM's init: a remote end is left to the page instead of ending the session.
        OgsmTestState.enterCall(h.ogsm, "r1", session.id)
        session.onRemoteControl(CallActionType.HANGUP, "r1")
        assertSame(session, h.holder.current.value)
        assertTrue(h.cleanupStarts.isEmpty())
        h.ogsm.setIsInPipMode(true)

        vm.doExitClear()
        ViewModelStore().apply {
            put("call-vm", vm)
            clear()
        }

        assertEquals("the second end is a no-op", listOf("doExitClear"), h.cleanupStarts.map { it.first })
        assertEquals("the job lives until the last cleanup step", true, session.mainScope.isActive)
        assertEquals(false, h.ogsm.isInPipMode())
        assertEquals(null, h.holder.current.value)
    }

    @Test
    fun `T5-23 markEnding is true only the first time and publishes the ending flag synchronously`() {
        val vm = LCallViewModelFixture.build(h)

        val first = vm.markEnding()

        assertEquals(true, first)
        assertEquals(true, h.ogsm.isInCallEnding())
        assertEquals(false, vm.markEnding())
        assertEquals(true, h.ogsm.isInCallEnding())
    }

    @Test
    fun `T5-18 the invite-upgrade delegates stop the ringback and the timeout and start the duration timer`() {
        mockkObject(ResUtils)
        every { ResUtils.getString(any()) } returns "Instant"
        val vm = LCallViewModelFixture.build(h)
        val session = h.sessions.single()
        session.graph.onRoomIdAssigned("r1")
        timeoutMonitor(session).start1V1Timeout("r1")
        every { h.components.roomCtl.updateCallType(any()) } answers { h.components.callType.value = firstArg() }

        vm.applyInviteUpgrade()
        vm.stopRingToneAndTimeoutCheck()
        vm.handleConnectedState()

        assertEquals(CallType.INSTANT.type, h.components.callType.value)
        verify(exactly = 1) { h.ringtone.stopRingTone() }
        verify(exactly = 1) { h.deps.callVibrationManager.stopVibration() }
        verify(exactly = 1) { h.callTimeoutManager.cancelCallWithTimeout("r1") }
        assertEquals(true, vm.timerManager.callTimerRunning.value)
    }

    private fun timeoutMonitor(session: CallSession): CallTimeoutMonitor =
        CallSessionGraph::class.java.getDeclaredField("timeoutMonitor").apply { isAccessible = true }
            .get(session.graph) as CallTimeoutMonitor

    @Test
    fun `T5-29 pre-composition VM reads create no room, the session's Phase B creates exactly one`() {
        PhaseBCollaboratorStubs.install()
        val hb = phaseBHarness()
        val vm = LCallViewModelFixture.build(hb)

        assertNotNull(vm.callStatus)
        assertNotNull(vm.callType)
        vm.getRoomId()
        vm.addAwaitingJoinInvitees(listOf("u"))
        assertEquals(0, hb.components.createRoomCalls)
        assertEquals(CallRoomState.Pending, vm.roomState.value)

        testDispatcher.scheduler.runCurrent()

        assertEquals(CallRoomState.Ready(hb.components.room), vm.roomState.value)
        assertSame(hb.components.room, vm.room)
        assertSame(hb.components.room, vm.room)
        assertEquals(1, hb.components.createRoomCalls)
    }

    @Test
    fun `the page's entry-claim release reaches the session's claim`() {
        mockkObject(CallWaitDialogUtil)
        every { CallWaitDialogUtil.dismiss(any<Int>()) } just Runs
        val vm = LCallViewModelFixture.build(h, h.start(h.params(entryWaitToken = 9)))

        vm.releaseEntryWaitDialog()

        verify(exactly = 1) { CallWaitDialogUtil.dismiss(9) }
        assertEquals(false, vm.isSessionEnded)
    }

    @Test
    fun `T5-31 e2eeLearnMoreUrl delegates to UrlManager unchanged`() {
        val urlManager: UrlManager = mockk(relaxed = true)
        every { urlManager.e2eeLearnMoreUrl } returns "https://yelling.pro/security"

        val vm = LCallViewModelFixture.build(h, urlManager = urlManager)

        assertEquals("https://yelling.pro/security", vm.e2eeLearnMoreUrl)
    }

    @Test
    fun `T3-01 the VM subscribes to session events and the critical-alert flag inside its constructor`() {
        LCallViewModelFixture.build(h)
        val ui = h.uiWriter(session())

        // No scheduler advance: UNDISPATCHED subscribes before the constructor returns.
        assertEquals(1, ui.subscriptionCountOf("_events"))
        assertEquals(1, ui.subscriptionCountOf("_groupCriticalAlert"))
    }

    @Test
    fun `T3-08 a RevealCallChrome event shows the top status view and the bottom toolbar`() {
        val vm = LCallViewModelFixture.build(h)
        vm.callUiController.toggleTopBottomBars()
        assertEquals(false, vm.callUiController.showTopStatusViewEnabled.value)

        h.uiWriter(session()).emit(CallSessionUiEvent.RevealCallChrome)
        testDispatcher.scheduler.runCurrent()

        assertEquals(true, vm.callUiController.showTopStatusViewEnabled.value)
        assertEquals(true, vm.callUiController.showBottomToolBarViewEnabled.value)
    }

    @Test
    fun `T3-15 a GROUP critical-alert refresh reaches the session flag and the VM mirror`() {
        val group = mockk<GroupModel>(relaxed = true) { every { criticalAlert } returns true }
        coEvery { h.deps.callToChatController.getSingleGroupInfo("g1") } returns group
        val params = h.params(type = CallType.GROUP.type, callIntent = groupIntent("g1"))
        val vm = LCallViewModelFixture.build(h, h.start(params))

        // The real dispatcher: group read on IO, then the write on Main (the test dispatcher).
        session().graph.criticalAlertDispatcher.refreshGroupStatus(params.callIntent)
        awaitOnMain { vm.callUiController.isCriticalAlertEnable.value }

        assertEquals(true, session().uiState.groupCriticalAlert.value)
        assertEquals(true, vm.callUiController.isCriticalAlertEnable.value)
    }

    @Test
    fun `T3-17 the UI's own critical-alert write is not overwritten by the session mirror`() {
        val vm = LCallViewModelFixture.build(h)
        testDispatcher.scheduler.runCurrent()

        vm.callUiController.setCriticalAlertEnable(true)
        testDispatcher.scheduler.runCurrent()

        assertEquals(true, vm.callUiController.isCriticalAlertEnable.value)
    }

    @Test
    fun `T3-18 a session barrage is formatted with the resolved name and forwarded by the VM`() {
        coEvery { h.contactorCacheManager.getDisplayName("bob.1") } returns "Bob"
        val vm = LCallViewModelFixture.build(h)
        val barrages = collectBarrages(vm)
        val sessionEvents = mutableListOf<CallSessionUiEvent>()
        collectorScope.launch { session().uiState.events.collect { sessionEvents += it } }
        testDispatcher.scheduler.runCurrent()

        vm.showCallBarrageMessage(remote("bob.1"), "hi", RTM_MESSAGE_TYPE_DEFAULT)
        awaitOnMain { barrages.isNotEmpty() }

        val event = sessionEvents.single() as CallSessionUiEvent.Barrage
        val message = (event.renderable as BarrageRenderable.Default).message
        assertEquals("Bob", message.userName)
        assertEquals("hi", message.message)
        assertEquals(listOf(message), barrages)
    }

    @Test
    fun `T3-25 events still reach the UI after doExitClear`() {
        val vm = LCallViewModelFixture.build(h)
        val barrages = collectBarrages(vm)
        val message = BarrageMessage("Bob", "after exit", 1L)

        vm.doExitClear()
        h.uiWriter(session()).emit(CallSessionUiEvent.Barrage(BarrageRenderable.Default(message)))
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(message), barrages)
    }

    @Test
    fun `T3-27 vm rtm throws before Phase B and is the session's instance after it`() {
        PhaseBCollaboratorStubs.install()
        val hb = phaseBHarness()
        val vm = LCallViewModelFixture.build(hb)

        assertThrows(UninitializedPropertyAccessException::class.java) { vm.rtm }
        testDispatcher.scheduler.runCurrent()

        assertSame(hb.sessions.single().graph.rtm, vm.rtm)
    }

    /** The only session in these rows (ended sessions stay in [CallSessionTestHarness.sessions]). */
    private fun session(): CallSession = h.sessions.single()

    /** A harness whose sessions run their room setup on the test scheduler. */
    private fun phaseBHarness() = CallSessionTestHarness(testDispatcher, default = testDispatcher).also { extraHarnesses += it }

    private fun collectBarrages(vm: LCallViewModel): List<BarrageMessage> {
        val received = mutableListOf<BarrageMessage>()
        collectorScope.launch { vm.callUiController.barrageMessage.collect { received += it } }
        testDispatcher.scheduler.runCurrent()
        return received
    }

    /** Runs the Main test dispatcher until [done]; the IO hops in these rows run on real threads. */
    private fun awaitOnMain(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within $AWAIT_TIMEOUT_MS ms" }
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(AWAIT_POLL_MS)
        }
    }

    /** `subscriptionCount` of a private backing flow (the session exposes read-only views). */
    private fun CallSessionUiState.subscriptionCountOf(field: String): Int =
        when (val flow = CallSessionUiState::class.java.getDeclaredField(field).apply { isAccessible = true }.get(this)) {
            is MutableStateFlow<*> -> flow.subscriptionCount.value
            is MutableSharedFlow<*> -> flow.subscriptionCount.value
            else -> error("unexpected backing flow type for $field")
        }

    private fun groupIntent(gid: String) =
        CallIntentFixture.build(CallIntent.Action.JOIN_CALL, CallType.GROUP.type, conversationId = gid)

    private fun remote(identity: String): Participant = mockk<RemoteParticipant>(relaxed = true) {
        every { this@mockk.identity } returns Participant.Identity(identity)
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val AWAIT_POLL_MS = 5L
    }
}
