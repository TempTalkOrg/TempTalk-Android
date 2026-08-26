package com.difft.android.call.media

import android.content.Context
import android.media.AudioManager
import com.difft.android.base.call.CallType
import com.difft.android.base.user.UserManager
import com.difft.android.call.btDevice
import com.difft.android.call.earDevice
import com.difft.android.call.scoIntent
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.difft.android.call.spkDevice
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Failure-source dispatch of the retry loop guard and the boundedness it buys (design inventory
 * group F rows #121, #122, #124, plus the loop-guard half of #120 and the behavioural half of #51).
 *
 * These rows use the real [AudioDeviceManager] as the applier's host, because the property under test
 * spans both: the applier decides *why* an attempt failed and the manager decides what that failure
 * locks out. Splitting them across mocks would assert each side's belief about the other.
 *
 * The invariant: `DEVICE_GONE` does not arm the guard (a device coming back is new information), and
 * `TIMEOUT` / `ERROR` do (the device was there the whole time and routing still failed). Retries are
 * therefore bounded by real device-arrival events, not by device-list twitches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteRetryBoundednessTest {

    private val context = mockk<Context>(relaxed = true)
    private val userManager = mockk<UserManager>(relaxed = true)
    private val audioHandler = mockk<AudioSwitchHandler>(relaxed = true)
    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val selectCalls = mutableListOf<AudioDevice?>()

    private val bt = btDevice()
    private val spk = spkDevice()
    private val devices = listOf(bt, spk)

    @Before
    @Suppress("DEPRECATION") // the legacy readings are the applier's primary judgement
    fun setUp() {
        every { audioManager.isBluetoothScoOn } returns false
        every { audioManager.isSpeakerphoneOn } returns false
        every { audioManager.mode } returns AudioManager.MODE_IN_COMMUNICATION
        every { audioManager.mode = any() } just Runs
        every { audioManager.communicationDevice } returns null
        every { audioHandler.selectDevice(captureNullable(selectCalls)) } just Runs
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun newManager() = AudioDeviceManager(context, CallType.ONE_ON_ONE.type, userManager)

    private fun TestScope.startApplier(manager: AudioDeviceManager): AudioRouteApplier {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AudioRouteApplier(
            appContext = context,
            host = manager,
            audioHandler = audioHandler,
            scope = backgroundScope,
            audioManager = audioManager,
            workDispatcher = dispatcher,
        ).also { it.start() }
    }

    /** Leaves the manager in `Failed(bt, cause)` through the normal contract entries. */
    private fun failedManager(cause: AudioRouteFailure): AudioDeviceManager = newManager().apply {
        onLibraryDevicesChanged(devices, null)
        select(bt)
        onRouteFailed(bt, cause)
    }

    // ── #121 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a device that comes back after DEVICE_GONE may be retried`() {
        val manager = failedManager(AudioRouteFailure.DEVICE_GONE)

        manager.onLibraryDevicesChanged(devices, bt)

        // Without this, a cross-host handover (headset dropped and re-added by the OS) leaves the
        // state stuck in Failed forever and the only way out is the manual switch this fix removes.
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, (manager.routeState as AudioRouteState.Applying).device.kind)
        assertNull(manager.confirmed)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    // ── #122 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a failure with the device present keeps blocking library-driven retries`() {
        listOf(AudioRouteFailure.TIMEOUT, AudioRouteFailure.ERROR).forEach { cause ->
            val manager = failedManager(cause)
            val before = manager.routeSnapshot.value

            repeat(3) { manager.onLibraryDevicesChanged(devices, bt) }

            assertEquals("cause=$cause must stay latched", before, manager.routeSnapshot.value)
        }
    }

    // ── #124 (with the loop-guard half of #120) ─────────────────────────────────
    @Test
    fun `each DEVICE_GONE buys at most one more attempt`() = runTest {
        val manager = failedManager(AudioRouteFailure.DEVICE_GONE)
        startApplier(manager)
        val pending = recordPendingRoutes(manager)

        // A real arrival event reopens the attempt exactly once…
        manager.onLibraryDevicesChanged(devices, bt)
        advanceTimeBy(5_600)

        // …which lands as TIMEOUT, because the device was present for the whole budget.
        assertEquals(AudioRouteFailure.TIMEOUT, (manager.routeState as AudioRouteState.Failed).cause)
        val callsAtLatch = selectCalls.size

        // From here the guard is armed: further library reports cannot reopen anything.
        repeat(3) { manager.onLibraryDevicesChanged(devices, bt) }
        advanceTimeBy(6_000)

        assertEquals(listOf(AudioDeviceKind.BLUETOOTH_HEADSET), pending)
        assertEquals("no further driving", callsAtLatch, selectCalls.size)
        assertEquals(AudioRouteFailure.TIMEOUT, (manager.routeState as AudioRouteState.Failed).cause)
    }

    // ── #132 (R1a end-to-end) ────────────────────────────────────────────────────
    /**
     * Regression test for the reported fingerprint at the reducer layer
     * ([AudioDeviceManager.onLibraryDevicesChanged]): a progressive enumeration (earpiece first,
     * Bluetooth a poll later on the same generation) must have R1a supersede the earpiece's
     * LIBRARY-origin `Applying` attempt with the updated library pick, and ultimately confirm
     * Bluetooth, never the earpiece. `communicationDevice`/`isBluetoothScoOn` start false so an
     * earpiece-kind reading would spuriously match if the earpiece attempt were ever allowed to
     * run its verification loop — replaying the exact production trap. This does not validate
     * applier-layer scheduling timing — under `StandardTestDispatcher` semantics there is no real
     * timing race for it to reproduce.
     */
    @Test
    @Suppress("DEPRECATION") // matches the applier's own legacy reading, see observeRoute()
    fun `a progressive enumeration settles on the later, different library pick, not the first`() = runTest {
        val ear = earDevice()
        val manager = newManager()
        startApplier(manager)
        val confirmedKinds = mutableListOf<AudioDeviceKind>()
        backgroundScope.launch {
            manager.routeSnapshot.collect { snap ->
                (snap.state as? AudioRouteState.Confirmed)?.let { confirmedKinds += it.device.kind }
            }
        }

        manager.onLibraryDevicesChanged(listOf(ear), ear)
        manager.onLibraryDevicesChanged(listOf(bt, ear, spk), bt)
        every { audioManager.isBluetoothScoOn } returns true
        advanceTimeBy(600)

        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.confirmed?.kind)
        assertTrue(manager.routeState is AudioRouteState.Confirmed)
        assertTrue("earpiece must never be confirmed", AudioDeviceKind.EARPIECE !in confirmedKinds)
    }

    // ── #51, behavioural half ───────────────────────────────────────────────────
    @Test
    fun `the applier never records a route intent of its own`() = runTest {
        val manager = newManager()
        startApplier(manager)

        // Attempt opened purely by the library's own pick; `requested` is only ever written by
        // select(), so it staying null proves the applier never picked a route itself — the applier
        // reports facts, it does not decide policy, which is what keeps the loop guard effective.
        manager.onLibraryDevicesChanged(devices, bt)
        advanceTimeBy(5_600)

        assertTrue("the attempt really ran", selectCalls.isNotEmpty())
        assertEquals(AudioRouteFailure.TIMEOUT, (manager.routeState as AudioRouteState.Failed).cause)
        assertNull(manager.requested)
    }

    // ── AP-3 ────────────────────────────────────────────────────────────────────
    /**
     * The group-call fingerprint: a progressive enumeration whose second report supersedes the first
     * must never drive or confirm the abandoned earpiece target, even though a two-negative reading
     * would match it. Reducer R1a and the applier's ownership checks compose here — splitting them
     * across mocks would assert each side's belief about the other.
     */
    @Test
    @Suppress("DEPRECATION") // matches the applier's own legacy reading, see observeRoute()
    fun `a superseded earpiece attempt is never driven and never confirmed`() = runTest {
        val ear = earDevice()
        val manager = newManager()
        startApplier(manager)
        val confirmedKinds = mutableListOf<AudioDeviceKind>()
        backgroundScope.launch {
            manager.routeSnapshot.collect { snap ->
                (snap.state as? AudioRouteState.Confirmed)?.let { confirmedKinds += it.device.kind }
            }
        }

        manager.onLibraryDevicesChanged(listOf(ear), ear)
        manager.onLibraryDevicesChanged(listOf(ear, spk), spk)
        runCurrent()
        assertTrue("the surviving target drives", selectCalls.any { it === spk })

        every { audioManager.isSpeakerphoneOn } returns true
        advanceTimeBy(600)

        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.confirmed?.kind)
        assertTrue("the abandoned target is never driven", selectCalls.none { it === ear })
        assertTrue("earpiece must never be confirmed", AudioDeviceKind.EARPIECE !in confirmedKinds)
    }

    // ── WK-9 ────────────────────────────────────────────────────────────────────
    /**
     * Wake storms may only make rounds happen earlier, never add them: past `MAX_SIGNAL_WAKES` the
     * loop is back on its 500 ms cadence, so one budget stays bounded at twice the poll-only round
     * count. Driven through the SCO source, so the bound is proven independent of which source fires.
     */
    @Test
    fun `a wake storm cannot make an attempt exceed twice its poll-only rounds`() = runTest {
        val manager = newManager()
        val applier = startApplier(manager)
        manager.onLibraryDevicesChanged(devices, bt)
        runCurrent()

        // Far more wakes than the cap allows; each one that is honoured drives a round immediately.
        repeat(40) {
            applier.wakeSignals.scoReceiver.onReceive(context, scoIntent(AudioManager.SCO_AUDIO_STATE_CONNECTED))
            runCurrent()
        }
        // 11 rounds: the first needs no wake, then exactly MAX_SIGNAL_WAKES=10 are honoured and the
        // rest are ignored — two-sided, so it fails both without a cap and without wake-ups at all.
        assertEquals("early wakes are honoured, then capped", 22, selectCalls.size)

        advanceTimeBy(5_600)

        assertEquals(AudioRouteFailure.TIMEOUT, (manager.routeState as AudioRouteState.Failed).cause)
        assertTrue("at most 20 rounds of two calls each", selectCalls.size <= 40)
    }

    // ── WK-10 ───────────────────────────────────────────────────────────────────
    @Test
    fun `a wake storm cannot add a terminal report`() = runTest {
        val manager = newManager()
        val applier = startApplier(manager)
        val terminals = mutableListOf<AudioRouteState>()
        backgroundScope.launch {
            manager.routeSnapshot.collect { snap ->
                val state = snap.state
                if (state is AudioRouteState.Confirmed || state is AudioRouteState.Failed) {
                    terminals += state
                }
            }
        }
        manager.onLibraryDevicesChanged(devices, bt)
        runCurrent()

        repeat(40) {
            applier.wakeSignals.scoReceiver.onReceive(context, scoIntent(AudioManager.SCO_AUDIO_STATE_CONNECTED))
            runCurrent()
        }
        advanceTimeBy(6_000)

        // The wake source is not a confirmation source: exactly one outcome per attempt survives.
        assertEquals(listOf(AudioRouteState.Failed(bt, AudioRouteFailure.TIMEOUT)), terminals)
    }
}
