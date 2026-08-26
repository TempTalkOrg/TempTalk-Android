package com.difft.android.call

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.handler.CriticalAlertDispatcher
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.media.CallAudioSetup
import com.difft.android.call.media.CallMediaController
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.CallObservers
import com.difft.android.call.session.CallSessionStarter
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.flow
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.reflect.KProperty0

/**
 * Integration tests for the two-phase `LCallViewModel` initialization that moves WebRTC
 * `LiveKit.create()` room creation off the main thread. Covers the VM/Robolectric rows:
 *
 *  - **T1**   Phase A (construction) performs NO room creation.
 *  - **T2**   Phase B `createRoom()` runs off the main thread.
 *  - **T3**   Phase B wiring order (collection → observers → sessionStarter LAST).
 *  - **T4**   `rtm` is initialized before `roomEventDispatcher` (no lateinit crash).
 *  - **T5**   Phase B is idempotent across sequential calls.
 *  - **T6**   onCreate steps 1–5 surfaces are room-free (see note below).
 *  - **T7**   `initializeView` choreography: Phase B off-main then a post-Phase-B room read
 *             (what `setContent` does) adds no further `LiveKit.create`.
 *  - **T8**   config-change re-entry: invoking the Phase B entry twice creates one room.
 *  - **T9**   `doExitClear()` (user-initiated exit) before Phase B: no create, room-event
 *             dispatcher lazy never forced. Does NOT cancel `viewModelScope`.
 *  - **T9b**  REAL `onCleared()` before Phase B (via `ViewModelStore.clear()`): `super.onCleared()`
 *             cancels `viewModelScope` FIRST, then cleanup runs crash-free on the never-forced
 *             `_roomEventDispatcherLazy` (nullable dispatcher path); room is never created.
 *  - **T11**  `lateinit` (`mediaCtl`/`activeSpeakers`/`rtm`) reachable post-Phase-B.
 *  - **T12**  `withContext` sequencing: the Default (room+wiring) block completes before the
 *             Main (`setContent`) block starts.
 *  - **T14b** Phase B `AtomicBoolean.compareAndSet` idempotency under concurrent invocation.
 *  - **T15**  released-room wiring abort on the `doExitClear` race
 *             (`isReleaseIntended()==true` during create — first guard aborts).
 *  - **T15b** release races in AFTER the first guard but BEFORE `sessionStarter.start()`:
 *             the second (pre-connect) guard aborts so connect() never hits a released room.
 *
 * ## Harness rationale
 *
 * There is no precedent `LCallViewModel`-construction test in `call/src/test`, so the harness
 * is built from scratch:
 *  - **`mockkObject(LiveKit)`** (NOT `mockkStatic`): `io.livekit.android.LiveKit` is a Kotlin
 *    `object` and `create(...)` is a non-static INSTANCE member, so the production call compiles
 *    to `LiveKit.INSTANCE.create(...)`. Creation is observable via `verify { LiveKit.create(...) }`.
 *  - **member-stubbing `mockkConstructor`** for the eager Phase-A collaborators whose ctors do
 *    asset I/O / native init (`AudioPipelineProcessor`, `AudioDeviceManager`, `CallAudioSetup`)
 *    and the Phase-B collaborators we assert ordering on (`CallMediaController`,
 *    `RoomEventDispatcher`, `CallSessionStarter`, `CallConnectionCoordinator`,
 *    `CriticalAlertDispatcher`). `mockkConstructor` runs the real ctor first, then swaps member
 *    dispatch — so it is a member-stubbing tool here, not a ctor bypass.
 *  - **`mockkStatic("io.livekit.android.util.FlowDelegateKt")`** so `r::activeSpeakers.flow`
 *    returns a real `StateFlow` instead of dereferencing LiveKit's `@FlowObservable` delegate
 *    machinery (which is absent on a mock `Room` and would otherwise NPE).
 *  - **`mockkObject(CallObservers)`** to verify `register(...)` ordering.
 *
 * ## T6 note (Activity not launched)
 *
 * Launching the real `LCallActivity` requires the full Hilt graph + Compose, which has no
 * precedent in `call/src/test` and is out of scope for this unit-test tier. The
 * `onCreate`-step ordering contract (only `initializeView` calls `startRoomDependentWiring()`)
 * is enforced by the production structure; T6 verifies the **VM surfaces** that onCreate
 * steps 1–5 actually touch (`callStatus`, `callType`, `getRoomId()`, `addAwaitingJoinInvitees`)
 * never force `LiveKit.create`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class LCallViewModelTwoPhaseInitTest {

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule()

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var mockRoom: Room

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())

        // LiveKit is an `object`; create() is an instance member → mockkObject, not mockkStatic.
        mockkObject(LiveKit)
        mockRoom = mockk(relaxed = true)
        every { LiveKit.create(any(), any(), any()) } returns mockRoom

        // init{} launches camera bring-up on a real IO dispatcher (CameraXHelper.createCameraProvider
        // is an inline fun and cannot be mocked). It touches no test mock (only real CameraXHelper /
        // CameraCapturerUtils / app context), so any background failure is isolated to that coroutine.

        // r::activeSpeakers.flow → real StateFlow (mock Room has no FlowObservable delegate).
        mockkStatic("io.livekit.android.util.FlowDelegateKt")
        every { any<KProperty0<List<Participant>>>().flow } returns MutableStateFlow(emptyList())

        // --- Eager Phase-A collaborator member-stubbing -----------------------------------
        mockkConstructor(AudioPipelineProcessor::class)
        every { anyConstructed<AudioPipelineProcessor>().setModule(any()) } just Runs
        every { anyConstructed<AudioPipelineProcessor>().setSoundTouchPreset(any()) } just Runs
        every { anyConstructed<AudioPipelineProcessor>().setDenoiseEnabled(any()) } just Runs
        every { anyConstructed<AudioPipelineProcessor>().release() } just Runs

        mockkConstructor(AudioDeviceManager::class)
        every { anyConstructed<AudioDeviceManager>().audioHandler } returns mockk(relaxed = true)
        every { anyConstructed<AudioDeviceManager>().switchVoicePreset(any()) } just Runs
        every { anyConstructed<AudioDeviceManager>().initDeNoiseMode(any()) } just Runs

        mockkConstructor(CallAudioSetup::class)
        every { anyConstructed<CallAudioSetup>().start() } just Runs
        every { anyConstructed<CallAudioSetup>().stop() } just Runs
        // Phase B hands the room's state flow to the route lifecycle guard. Stubbed explicitly: the
        // `flow` extension is stubbed by erased type above, so letting the real body run would feed
        // the guard a flow whose element type is not Room.State.
        every { anyConstructed<CallAudioSetup>().bindRoomState(any()) } just Runs

        // --- Phase-B collaborator member-stubbing (ordering / side-effect free) -----------
        mockkConstructor(CallMediaController::class)
        every { anyConstructed<CallMediaController>().setVoicePreset(any()) } just Runs
        every { anyConstructed<CallMediaController>().deNoiseEnable } returns MutableStateFlow(true)
        every { anyConstructed<CallMediaController>().voicePreset } returns MutableStateFlow(VoicePreset.ORIGINAL)

        mockkConstructor(RoomEventDispatcher::class)
        every { anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents() } just Runs
        every { anyConstructed<RoomEventDispatcher>().startCollectingParticipants() } just Runs
        every { anyConstructed<RoomEventDispatcher>().cancelJobs() } just Runs

        mockkConstructor(CallSessionStarter::class)
        every { anyConstructed<CallSessionStarter>().start() } just Runs

        mockkConstructor(CallConnectionCoordinator::class)
        every {
            anyConstructed<CallConnectionCoordinator>().observeManualSwitchReconnect(any(), any(), any(), any())
        } returns mockk(relaxed = true)

        mockkConstructor(CriticalAlertDispatcher::class)
        every { anyConstructed<CriticalAlertDispatcher>().refreshGroupStatus(any()) } just Runs

        mockkObject(CallObservers)
        every {
            CallObservers.register(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildViewModel(callRole: CallRole = CallRole.CALLEE): LCallViewModel = LCallViewModel(
        application = app,
        e2eeEnable = false,
        callIntent = CallIntent(Intent()),
        callConfig = CallConfig(),
        callRole = callRole,
        initialVoicePreset = VoicePreset.ORIGINAL,
        callToChatController = mockk(relaxed = true),
        messageEncryptor = mockk(relaxed = true),
        onGoingCallStateManager = mockk(relaxed = true),
        callDataManager = mockk(relaxed = true),
        callVibrationManager = mockk(relaxed = true),
        callRingtoneManager = mockk(relaxed = true),
        contactorCacheManager = mockk(relaxed = true),
        callFeedbackManager = mockk(relaxed = true),
        callStatisticsLogManager = mockk(relaxed = true),
        callTimeoutManager = mockk(relaxed = true),
        callTlsProvider = mockk(relaxed = true),
        httpClient = dagger.Lazy { mockk(relaxed = true) },
        userManager = mockk(relaxed = true),
        proxyConfigProvider = mockk(relaxed = true),
        urlManager = mockk(relaxed = true),
    )

    // ---------------------------------------------------------------------------------
    // T1 — Phase A (construction) performs NO room creation.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T1 - Phase A construction does not create the room`() {
        buildViewModel()

        verify(exactly = 0) { LiveKit.create(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------
    // T2 — Phase B createRoom() runs OFF the main thread.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T2 - Phase B creates the room off the main thread`() {
        val createThread = AtomicReference<Thread?>(null)
        every { LiveKit.create(any(), any(), any()) } answers {
            createThread.set(Thread.currentThread())
            mockRoom
        }
        val vm = buildViewModel()

        runBlocking {
            withContext(Dispatchers.Default) { vm.startRoomDependentWiring() }
        }

        verify(exactly = 1) { LiveKit.create(any(), any(), any()) }
        val mainThread = Looper.getMainLooper().thread
        assertNotNull("LiveKit.create must have run", createThread.get())
        assertNotEquals(
            "LiveKit.create must NOT run on the main thread",
            mainThread,
            createThread.get(),
        )
    }

    // ---------------------------------------------------------------------------------
    // T3 — Phase B wiring order (rtm/collection before observers before sessionStarter LAST).
    // ---------------------------------------------------------------------------------
    @Test
    fun `T3 - Phase B wires collaborators in the required order`() {
        val vm = buildViewModel()

        vm.startRoomDependentWiring()

        verifyOrder {
            anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents()
            anyConstructed<RoomEventDispatcher>().startCollectingParticipants()
            CallObservers.register(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            // checkCriticalAlertStatusById now sits under the pre-connect guard, still before start().
            anyConstructed<CriticalAlertDispatcher>().refreshGroupStatus(any())
            anyConstructed<CallSessionStarter>().start()
        }
    }

    // ---------------------------------------------------------------------------------
    // T4 — rtm is initialized before roomEventDispatcher is forced (no lateinit crash).
    // ---------------------------------------------------------------------------------
    @Test
    fun `T4 - rtm is initialized before roomEventDispatcher (Phase B returns normally)`() {
        val vm = buildViewModel()

        // RoomEventDispatcher's lazy ctor reads `rtm`; if Phase B forced it before
        // initRtmHandler() this would throw UninitializedPropertyAccessException.
        vm.startRoomDependentWiring()

        // Reaching here without throwing + rtm being readable proves the ordering.
        assertNotNull(vm.rtm)
        verify(exactly = 1) { anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents() }
    }

    // ---------------------------------------------------------------------------------
    // T5 — Phase B idempotency (sequential): two calls create exactly one room.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T5 - sequential Phase B calls create the room and start the session exactly once`() {
        val vm = buildViewModel()

        vm.startRoomDependentWiring()
        vm.startRoomDependentWiring()

        verify(exactly = 1) { LiveKit.create(any(), any(), any()) }
        verify(exactly = 1) { anyConstructed<CallSessionStarter>().start() }
    }

    // ---------------------------------------------------------------------------------
    // T6 — onCreate steps 1–5 VM surfaces are room-free (see class KDoc note).
    // ---------------------------------------------------------------------------------
    @Test
    fun `T6 - pre-composition VM surfaces never force room creation`() {
        val vm = buildViewModel()

        // Surfaces touched by onCreate steps 1–5 (handleIntent / initializeExitHandler /
        // initializeState) — none may force LiveKit.create.
        vm.addAwaitingJoinInvitees(listOf("+10000000000"))
        vm.getRoomId()
        assertNotNull(vm.callStatus)
        assertNotNull(vm.callType)

        verify(exactly = 0) { LiveKit.create(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------
    // T7 — initializeView choreography: Phase B off-main, then a post-Phase-B room read
    //      (what setContent does) adds no further LiveKit.create.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T7 - room read after Phase B adds no further create`() {
        val vm = buildViewModel()

        runBlocking {
            withContext(Dispatchers.Default) { vm.startRoomDependentWiring() }
            // Mirror setContent reading viewModel.room after Phase B completes.
            withContext(Dispatchers.Main) { assertEquals(mockRoom, vm.room) }
        }

        verify(exactly = 1) { LiveKit.create(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------
    // T8 — config-change re-entry: invoking the Phase B entry twice creates one room.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T8 - config-change re-entry creates the room once`() {
        val vm = buildViewModel()

        // Two initializeView invocations on a retained VM both drive the Phase B entry.
        runBlocking { withContext(Dispatchers.Default) { vm.startRoomDependentWiring() } }
        runBlocking { withContext(Dispatchers.Default) { vm.startRoomDependentWiring() } }

        verify(exactly = 1) { LiveKit.create(any(), any(), any()) }
        verify(exactly = 1) { anyConstructed<CallSessionStarter>().start() }
    }

    // ---------------------------------------------------------------------------------
    // T9 — doExitClear() (user-initiated exit) before Phase B: no create, dispatcher lazy
    //      never forced. This path does NOT cancel viewModelScope (distinct from T9b).
    // ---------------------------------------------------------------------------------
    @Test
    fun `T9 - doExitClear before Phase B does not create or force the room-event dispatcher`() {
        val vm = buildViewModel()

        // Trigger the explicit-exit cleanup path without ever running Phase B
        // (call ended pre-composition). doExitClear() runs cleanup but leaves the scope alive.
        vm.doExitClear()

        verify(exactly = 0) { LiveKit.create(any(), any(), any()) }
        // _roomEventDispatcherLazy was never forced → cleanup passes null → cancelJobs never runs.
        verify(exactly = 0) { anyConstructed<RoomEventDispatcher>().cancelJobs() }
    }

    // ---------------------------------------------------------------------------------
    // T9b — REAL onCleared() before Phase B: super.onCleared() cancels viewModelScope FIRST,
    //       then our cleanup runs crash-free on the never-forced (null) dispatcher lazy.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T9b - real onCleared before Phase B cancels scope then cleans up without forcing the room`() {
        val vm = buildViewModel()

        // Drive the REAL lifecycle teardown: ViewModelStore.clear() → ViewModel.clear() →
        // super.onCleared() cancels viewModelScope FIRST, then our onCleared() override runs.
        // This exercises the cancelled-scope cleanup path that doExitClear() (T9) never hits.
        val store = ViewModelStore()
        store.put("call-vm", vm)
        store.clear()

        // Phase B never ran → room never created (roomCtl room stays uninitialized) and the
        // nullable _roomEventDispatcherLazy is never forced → no NPE, cancelJobs never runs.
        verify(exactly = 0) { LiveKit.create(any(), any(), any()) }
        verify(exactly = 0) { anyConstructed<RoomEventDispatcher>().cancelJobs() }
    }

    // ---------------------------------------------------------------------------------
    // T11 — lateinit (mediaCtl / activeSpeakers / rtm) reachable post-Phase-B.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T11 - lateinit media and rtm are reachable after Phase B`() {
        val vm = buildViewModel()

        vm.startRoomDependentWiring()

        // All read through the lateinit fields; none may throw UninitializedPropertyAccessException.
        assertNotNull(vm.rtm)
        assertNotNull(vm.deNoiseEnable)
        assertNotNull(vm.voicePreset)
        assertNotNull(vm.room)
    }

    // ---------------------------------------------------------------------------------
    // T12 — withContext sequencing: Default (room+wiring) completes before Main (setContent).
    // ---------------------------------------------------------------------------------
    @Test
    fun `T12 - Default Phase-B block completes before the Main setContent block`() {
        val vm = buildViewModel()
        val events = Collections.synchronizedList(mutableListOf<String>())

        runBlocking {
            withContext(Dispatchers.Default) {
                vm.startRoomDependentWiring()
                events.add("phaseB-done")
            }
            withContext(Dispatchers.Main) {
                events.add("setContent")
            }
        }

        assertEquals(listOf("phaseB-done", "setContent"), events)
        verify(exactly = 1) { LiveKit.create(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------
    // T14b — Phase B AtomicBoolean.compareAndSet idempotency under concurrent invocation.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T14b - concurrent Phase B invocation creates the room exactly once`() {
        val vm = buildViewModel()
        val start = CountDownLatch(1)

        val t1 = thread { start.await(); vm.startRoomDependentWiring() }
        val t2 = thread { start.await(); vm.startRoomDependentWiring() }
        start.countDown()
        t1.join()
        t2.join()

        verify(exactly = 1) { LiveKit.create(any(), any(), any()) }
        verify(exactly = 1) { anyConstructed<CallSessionStarter>().start() }
    }

    // ---------------------------------------------------------------------------------
    // T15 — released-room wiring abort (doExitClear race): isReleaseIntended()==true during
    //       create → lateinit still assigned but sessionStarter.start() never runs.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T15 - wiring aborts on release-intended without connecting a released room`() {
        // Replace the real roomCtl: createRoom returns a room that is already release-intended.
        mockkConstructor(CallRoomController::class)
        every { anyConstructed<CallRoomController>().createRoom() } returns mockRoom
        every { anyConstructed<CallRoomController>().room } returns mockRoom
        every { anyConstructed<CallRoomController>().isReleaseIntended() } returns true

        val vm = buildViewModel()

        vm.startRoomDependentWiring()

        // lateinit fields were assigned BEFORE the abort guard (so a late setContent is crash-free)…
        assertNotNull(vm.rtm)
        assertNotNull(vm.voicePreset)
        // …but the active wiring aborted: no event/participant collection and no
        // connect-on-released-room (the guard returns before any of these run).
        verify(exactly = 0) { anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents() }
        verify(exactly = 0) { anyConstructed<RoomEventDispatcher>().startCollectingParticipants() }
        verify(exactly = 0) { anyConstructed<CallSessionStarter>().start() }
        verify(exactly = 0) { CallObservers.register(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------
    // T15b — release races in AFTER the first guard but BEFORE sessionStarter.start():
    //        the second (pre-connect) guard aborts so connect() never hits a released room,
    //        even though the earlier collection/observers already ran.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T15b - second guard aborts connect when release races in after first guard`() {
        mockkConstructor(CallRoomController::class)
        every { anyConstructed<CallRoomController>().createRoom() } returns mockRoom
        every { anyConstructed<CallRoomController>().room } returns mockRoom
        // First guard (after initRtmHandler) sees false → wiring proceeds; second guard
        // (before sessionStarter.start) sees true → abort before connect.
        every { anyConstructed<CallRoomController>().isReleaseIntended() } returnsMany listOf(false, true)

        val vm = buildViewModel()

        vm.startRoomDependentWiring()

        // Wiring up to the second guard ran…
        verify(exactly = 1) { anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents() }
        verify(exactly = 1) { anyConstructed<RoomEventDispatcher>().startCollectingParticipants() }
        verify(exactly = 1) { CallObservers.register(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        // …but everything AFTER the pre-connect guard is skipped. checkCriticalAlertStatusById must sit
        // under that guard (it forces criticalAlertDispatcher → fail-loud room getter), so it must NOT run;
        // and sessionStarter.start() (connect-on-released-room) must NOT run either.
        verify(exactly = 0) { anyConstructed<CriticalAlertDispatcher>().refreshGroupStatus(any()) }
        verify(exactly = 0) { anyConstructed<CallSessionStarter>().start() }
    }

    // ---------------------------------------------------------------------------------
    // T17 — isControlButtonClickEnabled (1v1) delegates to the non-throwing roomStateOrNull()
    //        instead of the fail-loud `room` getter. Regression guard for the
    //        "room accessed after release" crash on a control-button tap during teardown:
    //        a released room reads null → button disabled, NOT a crash.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T17 - isControlButtonClickEnabled in 1v1 uses roomStateOrNull and is false after release`() {
        mockkConstructor(CallRoomController::class)
        every { anyConstructed<CallRoomController>().callType } returns MutableStateFlow(CallType.ONE_ON_ONE.type)
        // First tap: room CONNECTED → clickable. Second tap: room released → roomStateOrNull()==null.
        every { anyConstructed<CallRoomController>().roomStateOrNull() } returnsMany
            listOf(Room.State.CONNECTED, null)
        // Proof of delegation: if the method (incorrectly) fell back to the fail-loud getter,
        // this stub would make the test throw instead of returning false.
        every { anyConstructed<CallRoomController>().room } throws
            IllegalStateException("[Call] room accessed after release")

        val vm = buildViewModel()

        assertTrue("connected 1v1 → control button enabled", vm.isControlButtonClickEnabled())
        assertFalse("released 1v1 → control button disabled, not a crash", vm.isControlButtonClickEnabled())
    }
}
