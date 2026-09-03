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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generation boundaries, intent replay and the starvation watchdog (design inventory group E —
 * rows #104–#115; the manager-side halves of #101–#103 live in `AudioDeviceManagerRouteTest`).
 *
 * The real [AudioDeviceManager] is used wherever a row depends on its rules (R6 taking a
 * library pick into `Applying`, `select()` rejecting an unreachable target); a mock manager is used
 * for the rows whose whole point is "the guard did nothing", where counting calls is the assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteLifecycleGuardTest {

    private val context = mockk<Context>(relaxed = true)
    private val userManager = mockk<UserManager>(relaxed = true)

    private val bt = btDevice()
    private val spk = spkDevice()
    private val ear = earDevice()

    /** The library view the manager's pull will observe; `var` so a row can heal mid-test. */
    private var pulledDevices: List<AudioDevice> = emptyList()
    private var pulledSelection: AudioDevice? = null

    @After
    fun tearDown() {
        unmockkConstructor(AudioSwitchHandler::class)
        clearAllMocks()
    }

    private fun newManager() = AudioDeviceManager(context, CallType.ONE_ON_ONE.type, userManager)

    /**
     * Intercepts the `AudioSwitchHandler` the manager builds for itself, which is the only way to give
     * it a library view to pull. Rows that omit this see the real, device-less handler — which is what
     * every pre-existing row in this suite relies on, and why the constructor mock is undone in
     * [tearDown].
     */
    private fun mockLibraryConstruction() {
        mockkConstructor(AudioSwitchHandler::class)
        every { anyConstructed<AudioSwitchHandler>().loggingEnabled = any() } just Runs
        every { anyConstructed<AudioSwitchHandler>().preferredDeviceList = any() } just Runs
        every { anyConstructed<AudioSwitchHandler>().selectDevice(any()) } just Runs
    }

    /** Both properties are stubbed explicitly: a relaxed nullable getter would answer a child mock. */
    private fun stubLibrary(devices: List<AudioDevice> = emptyList(), selected: AudioDevice? = null) {
        mockLibraryConstruction()
        pulledDevices = devices
        pulledSelection = selected
        every { anyConstructed<AudioSwitchHandler>().selectedAudioDevice } answers { pulledSelection }
        every { anyConstructed<AudioSwitchHandler>().availableAudioDevices } answers { pulledDevices }
    }

    /** Pulls performed so far. `reseedFromLibrary` reads the list exactly once per attempt. */
    private fun verifyPulls(count: Int) =
        verify(exactly = count) { anyConstructed<AudioSwitchHandler>().availableAudioDevices }

    private fun TestScope.startGuard(
        manager: AudioDeviceManager,
        roomState: MutableStateFlow<Room.State>,
    ): AudioRouteLifecycleGuard =
        AudioRouteLifecycleGuard(backgroundScope, manager, roomState).also {
            it.start()
            runCurrent()
        }

    /** Records every `confirmed` kind the snapshot ever published, for "never showed a check" rows. */
    private fun TestScope.recordConfirmedKinds(manager: AudioDeviceManager): List<AudioDeviceKind?> {
        val seen = mutableListOf<AudioDeviceKind?>()
        backgroundScope.launch { manager.routeSnapshot.collect { seen += it.confirmed?.kind } }
        runCurrent()
        return seen
    }

    private fun applyingKind(manager: AudioDeviceManager): AudioDeviceKind? =
        (manager.routeState as? AudioRouteState.Applying)?.device?.kind

    // ── #104 ────────────────────────────────────────────────────────────────────
    @Test
    fun `an ordinary reconnect touches nothing`() = runTest {
        // The library only creates and destroys its AudioSwitch on DISCONNECTED / CONNECTING; the
        // SDK's own reconnect keeps the instance, so believing otherwise would tear down a working
        // route on every network blip.
        val manager = mockk<AudioDeviceManager>(relaxed = true) {
            every { routeSnapshot } returns MutableStateFlow(
                AudioRouteSnapshot(
                    availableDevices = listOf(bt, spk),
                    requested = bt,
                    confirmed = bt,
                    state = AudioRouteState.Confirmed(bt),
                )
            )
            every { availableDevices } returns MutableStateFlow(listOf(bt, spk))
        }
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        AudioRouteLifecycleGuard(backgroundScope, manager, roomState).start()
        runCurrent()

        roomState.value = Room.State.RECONNECTING
        runCurrent()
        roomState.value = Room.State.CONNECTED
        runCurrent()

        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any()) }
        verify(exactly = 0) { manager.select(any()) }
    }

    // ── #105 ────────────────────────────────────────────────────────────────────
    @Test
    fun `user intent is replayed once the new generation reports devices`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), null)
        manager.select(ear)
        manager.onRouteConfirmed(ear)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
        assertEquals("intent outlives the AudioSwitch", AudioDeviceKind.EARPIECE, manager.requested?.kind)

        roomState.value = Room.State.CONNECTING
        runCurrent()
        // The new generation reports devices and the library picks Bluetooth from its preferred list.
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), bt)
        runCurrent()

        assertEquals(AudioDeviceKind.EARPIECE, applyingKind(manager))
        assertEquals(AudioDeviceKind.EARPIECE, manager.requested?.kind)
    }

    // ── #106 ────────────────────────────────────────────────────────────────────
    @Test
    fun `without an explicit choice the library pick stands`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), null)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        roomState.value = Room.State.CONNECTING
        runCurrent()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        runCurrent()

        // Applying comes from the library-pick rule, not from a replay — replaying a library choice
        // would write it into `requested` and pollute user intent forever.
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
        assertNull(manager.requested)
    }

    // ── #107 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a replay for the kind already being applied does not restart the attempt`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), null)
        manager.select(bt)
        manager.onRouteConfirmed(bt)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)
        val pending = recordPendingRoutes(manager)

        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        roomState.value = Room.State.CONNECTING
        runCurrent()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        runCurrent()

        // One target, one attempt: a second request would reset the applier's retry budget.
        assertEquals(listOf(AudioDeviceKind.BLUETOOTH_HEADSET), pending)
    }

    // ── #108 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a replay target missing from the new list is rejected and the library pick stands`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), null)
        manager.select(bt)
        manager.onRouteConfirmed(bt)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        roomState.value = Room.State.CONNECTING
        runCurrent()
        // The headset was taken away during the rebuild window.
        manager.onLibraryDevicesChanged(listOf(spk, ear), spk)
        runCurrent()

        assertEquals(AudioDeviceKind.SPEAKERPHONE, applyingKind(manager))
        assertEquals("intent is kept for the next generation", AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    // ── #109 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a CONNECTING seen without DISCONNECTED still invalidates`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        manager.onRouteConfirmed(bt)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        // StateFlow conflation can drop DISCONNECTED entirely when the main thread is busy; without
        // this branch the stale list stays visible and the previous generation's Failed keeps
        // blocking the new generation's own pick.
        roomState.value = Room.State.CONNECTING
        runCurrent()

        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
        assertEquals(AudioRouteState.Idle, manager.routeState)
    }

    // ── #110 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a late CONNECTING does not wipe a list the new generation already reported`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        manager.onRouteConfirmed(bt)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        // The rebuilt switch reports before the CONNECTING value is dispatched.
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        runCurrent()
        roomState.value = Room.State.CONNECTING
        runCurrent()

        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
    }

    // ── #111 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a tap in the same frame as the disconnect survives and is replayed`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), null)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)
        val confirmedKinds = recordConfirmedKinds(manager)

        manager.select(bt)
        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        assertEquals(AudioRouteState.Idle, manager.routeState)

        roomState.value = Room.State.CONNECTING
        runCurrent()
        manager.onLibraryDevicesChanged(listOf(bt, spk), spk)
        runCurrent()

        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
        assertFalse(
            "an unverified route must never have shown a check mark",
            confirmedKinds.contains(AudioDeviceKind.BLUETOOTH_HEADSET),
        )
    }

    // ── #116 ────────────────────────────────────────────────────────────────────
    /**
     * The guard's ready-edge replay skips `select()` (`onSwitchReady`'s `alreadyTargeted` path)
     * when the library's own R6 pick already targets `requested`'s kind, to avoid restarting the
     * attempt. That attempt still carries the user's intent and must keep it protected against a
     * later, different library pick — a plain library-to-library handoff must not take over.
     */
    @Test
    fun `a replayed intent that matches the library's own pick keeps its protection`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), null)
        manager.select(bt)
        manager.onRouteConfirmed(bt)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        roomState.value = Room.State.CONNECTING
        runCurrent()
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), bt)
        runCurrent()
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))

        // A further library pick on a different kind must not take over the user's device.
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), ear)
        runCurrent()

        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    // ── #112 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a switch that never reports devices is flagged as starved`() = runTest {
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)

        advanceTimeBy(3_001)

        // The only field-visible trace of a library stop/start race: no devices, no panel, no logs.
        assertTrue(guard.switchStarved.value)
    }

    // ── #113 ────────────────────────────────────────────────────────────────────
    @Test
    fun `the starvation watchdog is cancelled when devices arrive`() = runTest {
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)

        advanceTimeBy(2_000)
        manager.onLibraryDevicesChanged(listOf(bt), bt)
        advanceTimeBy(5_000)

        assertFalse(guard.switchStarved.value)
    }

    // ── #114 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a stopped guard reacts to nothing and resets its warning`() = runTest {
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)
        advanceTimeBy(3_001)
        assertTrue(guard.switchStarved.value)

        guard.stop()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        manager.onRouteConfirmed(bt)
        roomState.value = Room.State.DISCONNECTED
        advanceTimeBy(5_000)

        // No invalidation: the list and the confirmed route survive an event the guard no longer owns.
        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.confirmed?.kind)
        assertFalse("a stale warning would misreport the next call", guard.switchStarved.value)
    }

    // ── #115 ────────────────────────────────────────────────────────────────────
    @Test
    fun `Room State is exhaustively handled`() = runTest {
        // The generation `when` has no `else`, so a library upgrade that adds a state breaks the
        // build there. This pins the current set so the upgrade is noticed here too.
        assertEquals(4, Room.State.entries.size)
        assertEquals(
            setOf(
                Room.State.CONNECTING,
                Room.State.CONNECTED,
                Room.State.DISCONNECTED,
                Room.State.RECONNECTING,
            ),
            Room.State.entries.toSet(),
        )
    }

    // ── RS-12 ───────────────────────────────────────────────────────────────────
    /**
     * THE reported failure, end to end. The library had already reported devices for this generation
     * before the guard was bound, and the guard's first observed room state is CONNECTING — it cannot
     * know the boundary was already handled, so it invalidates. Pre-fix the wipe was final: the library
     * pushes only on a CHANGE, nothing had changed, and the call ran to its end with no devices, no
     * panel and no route. The pull now disproves the wipe inside the same publish.
     */
    @Test
    fun `a spurious generation boundary recovers inline instead of stranding the call`() = runTest {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
        val roomState = MutableStateFlow(Room.State.CONNECTING)
        val guard = startGuard(manager, roomState)

        advanceTimeBy(4_000)

        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioDeviceKind.EARPIECE, applyingKind(manager))
        assertFalse("the list came back, so the watchdog must never fire", guard.switchStarved.value)
    }

    // ── RS-14 ───────────────────────────────────────────────────────────────────
    /** The backstop for the case site A pulled empty and the library then never pushes again. */
    @Test
    fun `the starvation watchdog heals a call the library will never push to`() = runTest {
        stubLibrary(emptyList(), null)
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)

        advanceTimeBy(2_000)
        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
        // The switch came alive without a CHANGE to report, so no callback is coming.
        pulledDevices = listOf(ear, spk)
        pulledSelection = ear
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioDeviceKind.EARPIECE, applyingKind(manager))
        assertFalse(guard.switchStarved.value)
    }

    // ── RS-15 ───────────────────────────────────────────────────────────────────
    /**
     * The failure bound: three pulls one second apart and then a terminal verdict — never a poller. A
     * library that still reports nothing after that is a library-side stop/start race, not a contract
     * bug, and the flag stays raised so it is visible.
     */
    @Test
    fun `the self-heal is bounded at three attempts and then gives up`() = runTest {
        stubLibrary(emptyList(), null)
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)

        advanceTimeBy(3_001 + 3 * 1_000 + 100)

        verifyPulls(3)
        assertTrue(guard.switchStarved.value)

        advanceTimeBy(30_000)

        verifyPulls(3)
    }

    // ── RS-16 ───────────────────────────────────────────────────────────────────
    /**
     * Success terminates the loop structurally rather than by counting: the restored list flips the
     * starved combine to false, and the collector cancels this job at its trailing delay.
     */
    @Test
    fun `a successful pull cancels the remaining attempts structurally`() = runTest {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        advanceTimeBy(3_001)
        verifyPulls(1)

        advanceTimeBy(10_000)

        verifyPulls(1)
    }

    // ── RS-17 ───────────────────────────────────────────────────────────────────
    /**
     * The heal restores the list under an attempt the user already owns (R1), and the ready edge it
     * produces resolves to `alreadyTargeted` — so no second `select()` and no refreshed retry budget.
     */
    @Test
    fun `a heal does not restart the attempt the ready-edge replay already owns`() = runTest {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()
        // T3 accepts a target while the list is still empty ("cannot drive right now" != "absent").
        manager.select(spk)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)
        val pending = recordPendingRoutes(manager)

        advanceTimeBy(3_001)

        assertEquals(listOf(AudioDeviceKind.SPEAKERPHONE), pending)
        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
    }

    // ── RS-18 ───────────────────────────────────────────────────────────────────
    @Test
    fun `stopping the guard mid self-heal stops the pulls`() = runTest {
        stubLibrary(emptyList(), null)
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)

        advanceTimeBy(3_001)
        verifyPulls(1)

        guard.stop()
        advanceTimeBy(10_000)

        verifyPulls(1)
        assertFalse("a stale warning would misreport the next call", guard.switchStarved.value)
    }

    // ── RS-24b ──────────────────────────────────────────────────────────────────
    /**
     * The negative case for the site-A race pin: nothing else in the system would ever repair a
     * stale-but-NON-EMPTY list, so the inline re-read has to stand alone. The watchdog structurally
     * cannot see this defect — its predicate is `devices.isEmpty()` — and no follow-up push is
     * registered here, so the recovery can only have come from the invalidate itself.
     *
     * "The backstop never ran" is asserted through the pull count rather than a spy: `reseedFromLibrary`
     * reads the handler's list on every call, so a total of exactly two reads (the invalidate's two
     * attempts) proves no third pull happened from any source.
     */
    @Test
    fun `a stale but populated list is repaired by the invalidate alone, never by the backstop`() = runTest {
        mockLibraryConstruction()
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
        manager.onRouteConfirmed(ear)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)
        val starvedSeen = mutableListOf<Boolean>()
        backgroundScope.launch { guard.switchStarved.collect { starvedSeen += it } }
        runCurrent()
        var reads = 0
        every { anyConstructed<AudioSwitchHandler>().selectedAudioDevice } answers {
            if (reads == 0) ear else bt
        }
        every { anyConstructed<AudioSwitchHandler>().availableAudioDevices } answers {
            if (reads++ == 0) {
                manager.onLibraryDevicesChanged(listOf(ear, spk, bt), bt)
                listOf(ear, spk)
            } else {
                listOf(ear, spk, bt)
            }
        }

        manager.onAudioSwitchInvalidated("roomState=CONNECTING")
        advanceTimeBy(10_000)

        assertEquals(3, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
        verifyPulls(2)
        assertFalse("the watchdog cannot and must not be what repairs this", starvedSeen.contains(true))
    }

    // ── generation reset outranks the retry loop guard (companion to #103) ──────
    @Test
    fun `a failure from the previous generation cannot block the new one`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), null)
        manager.select(bt)
        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        runCurrent()

        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
    }
}
