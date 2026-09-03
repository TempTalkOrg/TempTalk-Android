package com.difft.android.call.media

import android.content.Context
import com.difft.android.base.call.CallType
import com.difft.android.base.user.UserManager
import com.difft.android.call.btDevice
import com.difft.android.call.earDevice
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.manager.AudioRouteSnapshot
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.difft.android.call.spkDevice
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wedge self-heal (issue #1155): when the platform's communication-device arbitration stops
 * answering, two consecutive `Failed(TIMEOUT)` on the same kind rebuild the `AudioSwitch` instead of
 * leaving the user with a route their taps can no longer move.
 *
 * Split out of `AudioRouteLifecycleGuardTest` rather than appended to it — that file already covers
 * generation boundaries, intent replay and the starvation watchdog, and this suite's concern (a
 * detector plus a bounded action) is independent, the same way `AudioRouteAbsenceTest` and
 * `AudioRouteRetryBoundednessTest` are split from the applier suite.
 *
 * Most rows drive the snapshot directly through a mocked [AudioDeviceManager]: the assertion is
 * "the guard rebuilt the switch exactly this many times", so counting calls IS the row. The two rows
 * that assert something about the manager's own rules (the `Failed` re-publication that R5 produces,
 * and the post-rescue replay chain) use the real manager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteWedgeRescueTest {

    private val context = mockk<Context>(relaxed = true)
    private val userManager = mockk<UserManager>(relaxed = true)

    private val bt = btDevice()
    private val spk = spkDevice()
    private val ear = earDevice()

    /** The handler a mocked manager hands the guard. The rebuild is verified on this instance. */
    private val handler = mockk<AudioSwitchHandler>(relaxed = true)

    @After
    fun tearDown() {
        unmockkConstructor(AudioSwitchHandler::class)
        clearAllMocks()
    }

    private fun newManager() = AudioDeviceManager(context, CallType.ONE_ON_ONE.type, userManager)

    /**
     * Intercepts the `AudioSwitchHandler` the real manager builds for itself. `start()` / `stop()`
     * are stubbed alongside the construction-time properties: the real `start()` spins up a
     * `HandlerThread` and drives `AudioManager`, which is the platform this suite is standing in for.
     */
    private fun mockLibraryConstruction() {
        mockkConstructor(AudioSwitchHandler::class)
        every { anyConstructed<AudioSwitchHandler>().loggingEnabled = any() } just Runs
        every { anyConstructed<AudioSwitchHandler>().preferredDeviceList = any() } just Runs
        every { anyConstructed<AudioSwitchHandler>().selectDevice(any()) } just Runs
        every { anyConstructed<AudioSwitchHandler>().start() } just Runs
        every { anyConstructed<AudioSwitchHandler>().stop() } just Runs
    }

    /**
     * A manager that is nothing but the snapshot the detector reads, so a row owns the exact
     * emission sequence — including the ones a conflating `StateFlow` would produce under load.
     * `availableDevices` is derived from the same flow, as the real manager derives it.
     */
    private fun mockedManager(snapshots: MutableStateFlow<AudioRouteSnapshot>) =
        mockk<AudioDeviceManager>(relaxed = true) {
            every { routeSnapshot } returns snapshots
            every { availableDevices } returns snapshots.map { it.availableDevices }.distinctUntilChanged()
            every { audioHandler } returns handler
        }

    /** A call already carrying the user's speaker intent, with the wedge target enumerable. */
    private fun snapshotFlow(devices: List<AudioDevice> = listOf(spk, ear)) =
        MutableStateFlow(AudioRouteSnapshot(availableDevices = devices, requested = spk))

    private fun TestScope.startGuard(
        manager: AudioDeviceManager,
        roomState: MutableStateFlow<Room.State>,
    ): AudioRouteLifecycleGuard =
        AudioRouteLifecycleGuard(backgroundScope, manager, roomState).also {
            it.start()
            runCurrent()
        }

    /**
     * One whole applier attempt: the target is driven and then ends without ever being observed.
     *
     * Every call allocates a NEW `Failed`, which is what the detector counts by — and publishes the
     * intervening `Applying` as its own emission, which is the uncongested case. The congested one
     * (`Applying` conflated away) has its own row.
     */
    private fun TestScope.driveAttempt(
        snapshots: MutableStateFlow<AudioRouteSnapshot>,
        target: AudioDevice,
        cause: AudioRouteFailure = AudioRouteFailure.TIMEOUT,
    ) {
        snapshots.value = snapshots.value.copy(state = AudioRouteState.Applying(target))
        runCurrent()
        snapshots.value = snapshots.value.copy(state = AudioRouteState.Failed(target, cause))
        runCurrent()
    }

    /**
     * The rescue suspends between `stop()` and `start()` to keep the dying switch's teardown from
     * landing on top of the new switch's `activate()`, so `start()` has not happened yet when the
     * second failure returns. Comfortably past that barrier and comfortably short of the starvation
     * watchdog, which must stay out of every row here.
     */
    private fun TestScope.settleRebuild() {
        advanceTimeBy(300)
        runCurrent()
    }

    private fun applyingKind(manager: AudioDeviceManager): AudioDeviceKind? =
        (manager.routeState as? AudioRouteState.Applying)?.device?.kind

    // ── detector: streak fires once, is consumed by its rescue ─────────────────
    @Test
    fun `two consecutive timeouts on one kind rebuild the switch exactly once`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        settleRebuild()
        // A single timeout is transient device contention, not a wedge — acting on it would churn
        // the user's audio for a route that was about to succeed.
        verify(exactly = 0) { handler.stop() }

        driveAttempt(snapshots, spk)
        settleRebuild()

        // The invalidate must precede the rebuild, or a belief from the dying generation outlives it.
        verifyOrder {
            manager.onAudioSwitchInvalidated("wedgeRescue", false)
            handler.stop()
            handler.start()
        }
        verify(exactly = 1) { manager.onAudioSwitchInvalidated("wedgeRescue", false) }
        verify(exactly = 1) { handler.stop() }
        verify(exactly = 1) { handler.start() }

        // The streak is consumed by the rescue it fired, so the episode starts over: without the
        // reset every later failure of the same episode would rebuild the switch again.
        driveAttempt(snapshots, spk)
        settleRebuild()
        verify(exactly = 1) { handler.stop() }
    }

    // ── detector: a confirmed route ends the episode ───────────────────────────
    @Test
    fun `a confirmed route between two timeouts ends the episode`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        // Audio was observed on the target in between, so the arbitration is answering: whatever the
        // next failure is, it is not two budgets of ignored requests.
        snapshots.value = snapshots.value.copy(state = AudioRouteState.Confirmed(spk))
        runCurrent()
        driveAttempt(snapshots, spk)
        settleRebuild()

        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    // ── detector: a cross-kind confirm also ends the episode ───────────────────
    /**
     * T4 records a confirm of a different kind on the `confirmed` field WITHOUT emitting a
     * `Confirmed` state (the outstanding attempt is not ended by it). It is still proof the
     * arbitration answered, so the streak must end — the detector watches the monotonic
     * `confirmations` counter, which is the only trace T4 leaves.
     */
    @Test
    fun `a cross-kind confirm between two timeouts ends the episode`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        // T4: an earpiece confirm lands while a speaker attempt is in flight.
        snapshots.value = snapshots.value.copy(
            state = AudioRouteState.Applying(spk),
            confirmed = ear,
            confirmations = snapshots.value.confirmations + 1,
        )
        runCurrent()
        driveAttempt(snapshots, spk)
        settleRebuild()

        // The platform demonstrably moved between the two timeouts, so this is not a wedge.
        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    // ── detector: a conflated confirm still ends the episode ───────────────────
    /**
     * The confirm's own frame can be conflated into the next failure's, and that failure has
     * already cleared `confirmed` (T6) — so the counter is the ONLY evidence that survives the
     * collapse. This row shows the detector honors it: the observed frame simultaneously carries
     * a countable new `Failed` and an advanced counter, and the counter wins.
     */
    @Test
    fun `a confirm conflated into the second failure frame still ends the episode`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        // No runCurrent between these: the confirm and the failure that follows it collapse into
        // one observed frame, exactly what a conflating StateFlow produces under load.
        snapshots.value = snapshots.value.copy(
            state = AudioRouteState.Applying(spk),
            confirmed = ear,
            confirmations = snapshots.value.confirmations + 1,
        )
        snapshots.value = snapshots.value.copy(
            state = AudioRouteState.Failed(spk, AudioRouteFailure.TIMEOUT),
            confirmed = null,
        )
        settleRebuild()

        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    // ── detector: a kind change starts a new episode ───────────────────────────
    @Test
    fun `timeouts on two different kinds are two episodes, not a streak`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        driveAttempt(snapshots, ear)
        settleRebuild()

        // Two different targets failing once each is the ordinary "neither is reachable" case; the
        // wedge fingerprint is one target driven twice and ignored twice.
        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    // ── detector: DEVICE_GONE is structurally excluded ─────────────────────────
    @Test
    fun `a device that keeps going away never rescues`() = runTest {
        val snapshots = snapshotFlow(listOf(spk, ear, bt))
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        repeat(3) { driveAttempt(snapshots, bt, AudioRouteFailure.DEVICE_GONE) }
        settleRebuild()

        // The Bluetooth walk-away path fails as DEVICE_GONE, so the guardrail is structural: a route
        // whose target stopped being enumerable is not a platform that stopped answering, and
        // rebuilding the switch under a headset the user walked away from would only churn audio.
        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    // ── budget: two rebuilds per call, then honest Failed ──────────────────────
    @Test
    fun `the rescue budget is spent after two rebuilds`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        // Three qualifying episodes; the third finds the budget already spent.
        repeat(3) {
            driveAttempt(snapshots, spk)
            driveAttempt(snapshots, spk)
            settleRebuild()
        }

        // A wedge that survived two rebuilds is not client-side recoverable, so past the cap the
        // contract goes back to reporting today's honest Failed. The exhausted path leaves no other
        // trace than this absence plus its own log line.
        verify(exactly = 2) { manager.onAudioSwitchInvalidated("wedgeRescue", false) }
        verify(exactly = 2) { handler.stop() }
        verify(exactly = 2) { handler.start() }
    }

    // ── gating: only a CONNECTED room may be rescued ───────────────────────────
    @Test
    fun `a rescue is refused while the room is not connected`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        driveAttempt(snapshots, spk)
        roomState.value = Room.State.RECONNECTING
        runCurrent()
        driveAttempt(snapshots, spk)
        settleRebuild()

        // The room's own lifecycle already tears the switch down across a reconnect, and the
        // generation collector translates that into a boundary; rebuilding underneath it would fight
        // the library for the same instance.
        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }

        // An evaluated streak is consumed whatever the verdict, so the refused rescue cannot re-fire
        // on the next failure of the same episode — it has to earn two fresh timeouts.
        roomState.value = Room.State.CONNECTED
        runCurrent()
        driveAttempt(snapshots, spk)
        settleRebuild()
        verify(exactly = 0) { handler.stop() }
    }

    // ── settle window: a disconnect aborts ─────────────────────────────────────
    /**
     * The rescue's settle delay is a suspension point, and hang-up cleanup cancels the guard's scope
     * WITHOUT joining it. A `start()` landing after teardown would leave a live `AudioSwitch` — audio
     * focus plus its `HandlerThread` — with nothing left to stop it, for the rest of the process.
     *
     * Cancellation mid-delay already skips `start()` on its own, so the row that actually pins the
     * fix is this one: the room leaves CONNECTED while the rescue is parked, and the resumed
     * continuation must re-read that rather than trusting the check it made before `stop()`.
     */
    @Test
    fun `a disconnect during the settle delay aborts the rebuild`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        driveAttempt(snapshots, spk)
        driveAttempt(snapshots, spk)

        // Parked in the settle delay: the old switch is already down, the new one not yet up.
        verify(exactly = 1) { handler.stop() }
        verify(exactly = 0) { handler.start() }

        advanceTimeBy(100)
        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        settleRebuild()

        // The teardown owns the switch from here: rebuilding one under it is exactly the leak.
        verify(exactly = 0) { handler.start() }
        verify(exactly = 1) { handler.stop() }
    }

    // ── settle window: a reconnect defers, never aborts ────────────────────────
    /**
     * RECONNECTING is the state that must NOT abort. The room calls `audioHandler.start()` only on
     * the transition into CONNECTING, and a successful reconnect goes RECONNECTING -> CONNECTED
     * directly — so a rescue that gave up here would leave the handler stopped with nothing left to
     * restart it, costing the rest of the call its audio route. The rescue completes late instead.
     */
    @Test
    fun `a reconnect during the settle delay defers the rebuild until it resolves`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        driveAttempt(snapshots, spk)
        driveAttempt(snapshots, spk)

        advanceTimeBy(100)
        roomState.value = Room.State.RECONNECTING
        runCurrent()
        settleRebuild()

        // Neither a go nor a permanent stop: the rescue waits the transient state out.
        verify(exactly = 1) { handler.stop() }
        verify(exactly = 0) { handler.start() }

        roomState.value = Room.State.CONNECTED
        runCurrent()

        verify(exactly = 1) { handler.start() }
    }

    // ── settle window: a reconnect that dies still aborts ──────────────────────
    /**
     * The other half of the deferral: a reconnect that never comes back still has to abort, or the
     * rescue would rebuild a switch under a room that has already torn everything down.
     */
    @Test
    fun `a reconnect that ends in a disconnect still aborts the rebuild`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        driveAttempt(snapshots, spk)
        driveAttempt(snapshots, spk)

        advanceTimeBy(100)
        roomState.value = Room.State.RECONNECTING
        runCurrent()
        settleRebuild()
        verify(exactly = 0) { handler.start() }

        roomState.value = Room.State.DISCONNECTED
        runCurrent()

        verify(exactly = 0) { handler.start() }
        verify(exactly = 1) { handler.stop() }
    }

    // ── conflation regression witness ───────────────────────────────────────────
    /**
     * The two failures of one wedge episode are value-identical, and the only thing between them is
     * an `Applying` that a conflating `StateFlow` is free to drop. An equality-based operator in
     * front of the detector would swallow the second failure — precisely under the load that
     * produces the wedge — so the detector counts by the identity of the `Failed` object instead.
     */
    @Test
    fun `a second timeout still counts when the intervening Applying is conflated away`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        // No `runCurrent` between these two: the collector is never shown the Applying frame. Only
        // the device list changing under the failure keeps the snapshot itself distinct — which is
        // exactly the emission `distinctUntilChanged` on the state would have discarded.
        snapshots.value = snapshots.value.copy(state = AudioRouteState.Applying(spk))
        snapshots.value = snapshots.value.copy(
            availableDevices = listOf(spk, ear, bt),
            state = AudioRouteState.Failed(spk, AudioRouteFailure.TIMEOUT),
        )
        settleRebuild()

        verify(exactly = 1) { manager.onAudioSwitchInvalidated("wedgeRescue", false) }
        verify(exactly = 1) { handler.stop() }
        verify(exactly = 1) { handler.start() }
    }

    // ── the other half of the identity discriminator ────────────────────────────
    /**
     * A `Failed` that is merely RE-PUBLISHED is one attempt, not two: rule R5 carries the same
     * instance forward when only the device list changes under it. Counting by value here would
     * rebuild the switch after a SINGLE timeout, triggered by nothing but a headset appearing — so
     * this row and the conflation row above are what pin the comparison to identity from both sides.
     */
    @Test
    fun `a failure republished under a changed device list is not a second timeout`() = runTest {
        mockLibraryConstruction()
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(spk, ear), null)
        manager.select(spk)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        manager.onRouteFailed(spk, AudioRouteFailure.TIMEOUT)
        runCurrent()
        // R5: the failed kind is the library's own pick and the device never went away, so the
        // reducer keeps the attempt's outcome and swaps only the list.
        manager.onLibraryDevicesChanged(listOf(spk, ear, bt), spk)
        settleRebuild()

        assertTrue(manager.routeState is AudioRouteState.Failed)
        assertEquals(3, manager.routeSnapshot.value.availableDevices.size)
        verify(exactly = 0) { anyConstructed<AudioSwitchHandler>().stop() }
    }

    // ── end to end: the rescue feeds the existing replay machinery ─────────────
    /**
     * Everything downstream of the rebuild is existing machinery, and this is the witness that the
     * rescue actually reaches it: the induced boundary wipes the generation, and the rebuilt
     * switch's first device push travels the ordinary ready-edge replay back to the user's intent.
     */
    @Test
    fun `after a rescue the new generation's first device push replays the user's intent`() = runTest {
        mockLibraryConstruction()
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(ear, spk), null)
        manager.select(spk)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        manager.onRouteFailed(spk, AudioRouteFailure.TIMEOUT)
        runCurrent()
        manager.select(spk)
        runCurrent()
        manager.onRouteFailed(spk, AudioRouteFailure.TIMEOUT)
        settleRebuild()

        verify(exactly = 1) { anyConstructed<AudioSwitchHandler>().stop() }
        verify(exactly = 1) { anyConstructed<AudioSwitchHandler>().start() }
        // reseed=false: the switch below is still alive at that moment, so a pull would read the
        // dying generation. The list can only come back from the rebuilt switch's own push.
        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
        assertEquals("intent outlives the rescue", AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)

        // The rebuilt switch reports for the first time and picks the earpiece from its preferred list.
        manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
        runCurrent()

        // No new plumbing was needed: the guard's empty -> non-empty edge replayed `requested` over
        // the library's pick, and the applier gets a normal verified attempt on the user's device.
        assertEquals(AudioDeviceKind.SPEAKERPHONE, applyingKind(manager))
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
    }
}
