package com.difft.android.call.media

import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.difft.android.call.btDevice
import com.difft.android.call.earDevice
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.scoIntent
import com.difft.android.call.manager.AudioRouteHost
import com.difft.android.call.manager.AudioRouteSnapshot
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.spkDevice
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.difft.android.test.TestDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Driving, observation and bounded retry in [AudioRouteApplier] (design inventory group B —
 * rows #26–#32, #35, #36, #39–#46, #51–#55). Absence debounce and failure classification live in
 * [AudioRouteAbsenceTest]; the framework assumptions live in [AudioRouteApplierFrameworkTest].
 *
 * Virtual time throughout: one `StandardTestDispatcher` serves as both the work dispatcher and the
 * "main" dispatcher, so the 500ms / 5000ms parameters are asserted exactly rather than waited for.
 * The interval and budget are hard-coded here on purpose — they are `private` constants in the
 * applier and a test that reads them could not detect them changing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteApplierTest {

    /**
     * The row that constructs the applier with production defaults (#52) needs a Main dispatcher
     * installed; every other row injects the work dispatcher explicitly.
     */
    @get:Rule
    val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())

    private val context = mockk<Context>(relaxed = true)
    private val audioHandler = mockk<AudioSwitchHandler>(relaxed = true)
    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val host = FakeAudioRouteHost()

    /** Every `selectDevice` argument in submission order — round shape and counts are read off it. */
    private val selectCalls = mutableListOf<AudioDevice?>()

    private var scoOn = false
    private var speakerOn = false
    private var mode = AudioManager.MODE_IN_COMMUNICATION

    private val bt = btDevice()
    private val spk = spkDevice()
    private val ear = earDevice()

    @Before
    fun setUp() {
        stubAudioManager()
        every { audioHandler.selectDevice(captureNullable(selectCalls)) } just Runs
    }

    @Suppress("DEPRECATION") // the legacy readings are the applier's primary judgement
    private fun stubAudioManager() {
        every { audioManager.isBluetoothScoOn } answers { scoOn }
        every { audioManager.isSpeakerphoneOn } answers { speakerOn }
        every { audioManager.mode } answers { mode }
        every { audioManager.mode = any() } just Runs
        every { audioManager.communicationDevice } returns null
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun TestScope.startApplier(target: FakeAudioRouteHost = host): AudioRouteApplier {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AudioRouteApplier(
            appContext = context,
            host = target,
            audioHandler = audioHandler,
            scope = backgroundScope,
            audioManager = audioManager,
            workDispatcher = dispatcher,
        ).also { it.start() }
    }

    // ── #26 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a round issues selectDevice null then the target, in that order`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()

        // The null hop is what breaks the library's "already selected, nothing to do" early return.
        assertEquals(listOf<AudioDevice?>(null, bt), selectCalls)
        assertEquals("driving is not confirming", 0, host.terminalCount)
    }

    // ── #27 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a route that is already active is confirmed without any routing action`() = runTest {
        scoOn = true
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()

        assertEquals(listOf(bt), host.confirmed)
        assertTrue("a working route must not be torn down to verify it", selectCalls.isEmpty())
    }

    // ── #28 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `retries repeat every 500ms while the route is not observed`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(2_100)

        // Rounds at 0 / 500 / 1000 / 1500 / 2000, two calls each.
        assertEquals(10, selectCalls.size)
        assertTrue("still inside the budget", host.failed.isEmpty())
    }

    // ── #29 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `the attempt fails with TIMEOUT after 5000ms when the target stayed present`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(5_600)

        assertEquals(listOf(bt to AudioRouteFailure.TIMEOUT), host.failed)
        assertTrue(host.confirmed.isEmpty())

        val callsAtFailure = selectCalls.size
        advanceTimeBy(3_000)
        assertEquals("the attempt is over, not looping", callsAtFailure, selectCalls.size)
        assertEquals(1, host.terminalCount)
    }

    // ── #30 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `retrying stops the moment the route is observed`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(1_100) // rounds at 0 / 500 / 1000
        assertEquals(6, selectCalls.size)

        scoOn = true
        advanceTimeBy(4_500)

        assertEquals(listOf(bt), host.confirmed)
        assertTrue(host.failed.isEmpty())
        assertEquals("no round after the confirming one", 6, selectCalls.size)
    }

    // ── #31 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `an SCO CONNECTED broadcast confirms without waiting out the interval`() = runTest {
        val applier = startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()

        scoOn = true
        applier.wakeSignals.scoReceiver.onReceive(context, scoIntent(AudioManager.SCO_AUDIO_STATE_CONNECTED))
        runCurrent()

        assertEquals(listOf(bt), host.confirmed)
        assertTrue("woke up early instead of polling at 500ms", currentTime < 500)
    }

    // ── #32 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a new target structurally cancels the previous attempt without reporting it`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(600)
        val callsBeforeSwitch = selectCalls.size

        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        advanceTimeBy(3_000)

        val afterSwitch = selectCalls.drop(callsBeforeSwitch)
        assertTrue("the abandoned target is never driven again", afterSwitch.none { it === bt })
        assertTrue("the new target is driven", afterSwitch.any { it === spk })
        assertTrue("a superseded attempt reports nothing", host.failed.isEmpty())
    }

    // ── #35 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a drifted audio mode is corrected before the route is re-driven`() = runTest {
        mode = AudioManager.MODE_NORMAL
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()

        verifyOrder {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioHandler.selectDevice(null)
        }
        verify(exactly = 1) { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
    }

    // ── #36 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `MODE_IN_CALL is tolerated and never overwritten`() = runTest {
        mode = AudioManager.MODE_IN_CALL
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()

        // A real cellular call owns that mode; clobbering it would break the call.
        verify(exactly = 0) { audioManager.mode = any() }
        assertEquals(listOf<AudioDevice?>(null, bt), selectCalls)
    }

    // ── #39 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `an earpiece target already routed to the earpiece is confirmed with zero actions`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(ear, listOf(ear, spk)))
        runCurrent()

        assertEquals(listOf(ear), host.confirmed)
        assertTrue(selectCalls.isEmpty())
    }

    // ── #40 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `an earpiece target is driven and confirmed when coming from the speaker`() = runTest {
        speakerOn = true
        startApplier()
        host.emit(applyingSnapshot(ear, listOf(ear, spk)))
        runCurrent()
        assertEquals(listOf<AudioDevice?>(null, ear), selectCalls)
        assertTrue(host.confirmed.isEmpty())

        speakerOn = false
        advanceTimeBy(600)

        assertEquals(listOf(ear), host.confirmed)
    }

    // ── #41 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a speakerphone target is confirmed from the speakerphone reading`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        runCurrent()
        assertEquals(listOf<AudioDevice?>(null, spk), selectCalls)

        speakerOn = true
        advanceTimeBy(600)

        assertEquals(listOf(spk), host.confirmed)
        assertEquals(
            "speakerphoneOn",
            observedVia(ObservedRoute.SPEAKER, scoOn = false, speakerOn = true, commType = null),
        )
    }

    // ── #42 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a non-bluetooth route is confirmed within one interval`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        runCurrent()
        speakerOn = true
        advanceTimeBy(1_000)

        // The Maestro smoke test asserts the horn icon right after tapping; anything slower than
        // one interval would make that assertion flaky.
        assertEquals(listOf(spk), host.confirmed)
        assertTrue(currentTime <= 1_000)
    }

    // ── #43 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a throwing AudioManager read degrades to a negative observation`() = runTest {
        @Suppress("DEPRECATION")
        every { audioManager.isBluetoothScoOn } throws RuntimeException("binder")
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(5_600)

        assertTrue("driving continued despite the read failure", selectCalls.isNotEmpty())
        assertEquals(listOf(bt to AudioRouteFailure.TIMEOUT), host.failed)
    }

    // ── #44 / AP-5 ──────────────────────────────────────────────────────────────
    @Test
    fun `a throwing selectDevice does not break the retry loop`() = runTest {
        every { audioHandler.selectDevice(any()) } throws RuntimeException("handler gone")
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(5_600)

        // TIMEOUT, not ERROR: the round trip has its own guard, so the loop kept verifying.
        assertEquals(listOf(bt to AudioRouteFailure.TIMEOUT), host.failed)
        // The guard is inside the non-suspending round trip: exactly one outcome, reached at the
        // end of the budget rather than on the first throw.
        assertEquals(1, host.terminalCount)
    }

    // ── AP-1 ────────────────────────────────────────────────────────────────────
    /**
     * A supersession landing between the loop-top ownership check and the round trip must issue no
     * routing call for the superseded target: the observation and mode reads separate the two, and
     * only the re-check inside the round trip covers that window.
     */
    @Test
    fun `a supersession between the ownership check and the round trip drives nothing`() = runTest {
        var flipped = false
        every { audioManager.mode } answers {
            if (!flipped) {
                flipped = true
                host.setState(AudioRouteState.Applying(spk))
            }
            AudioManager.MODE_IN_COMMUNICATION
        }
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()

        assertTrue("the superseded target must never be driven", selectCalls.none { it === bt })
        assertTrue("the superseding target owns the driving", selectCalls.any { it === spk })
        assertEquals("an aborted attempt reports nothing", 0, host.terminalCount)
    }

    // ── AP-2 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a supersession before the next round stops the previous target at the loop top`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()
        val btCallsBefore = selectCalls.count { it === bt }

        host.setState(AudioRouteState.Applying(spk))
        advanceTimeBy(600)

        assertEquals(
            "no round for the superseded target after the supersession",
            btCallsBefore,
            selectCalls.count { it === bt },
        )
        assertEquals(0, host.terminalCount)
    }

    // ── #45 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `start and stop are idempotent`() = runTest {
        val applier = startApplier()
        applier.start()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(600)

        assertEquals("a second start must not create a second collector", 4, selectCalls.size)

        applier.stop()
        applier.stop()
    }

    // ── #46 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `stop cancels the in-flight attempt and reports nothing`() = runTest {
        val applier = startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(600)
        val callsAtStop = selectCalls.size

        applier.stop()
        advanceTimeBy(6_000)

        assertEquals(callsAtStop, selectCalls.size)
        assertEquals(0, host.terminalCount)
    }

    // ── #51 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `the applier never writes the global communication route and cannot pick one`() = runTest {
        // A confirmed attempt…
        scoOn = true
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()
        // …and a timed-out one.
        scoOn = false
        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        advanceTimeBy(5_600)
        assertEquals(1, host.confirmed.size)
        assertEquals(1, host.failed.size)

        @Suppress("DEPRECATION")
        verify(exactly = 0) {
            audioManager.setCommunicationDevice(any())
            audioManager.clearCommunicationDevice()
            audioManager.isSpeakerphoneOn = any()
            audioManager.startBluetoothSco()
            audioManager.stopBluetoothSco()
        }
        // Route *policy* is equally out of reach: the host contract exposes no way to pick a device,
        // so the applier cannot bypass the loop guard with a fallback of its own.
        assertTrue(
            "AudioRouteHost must not expose a route-selection entry",
            AudioRouteHost::class.java.methods.none { it.name.startsWith("select") },
        )
    }

    // ── #52 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `constructing the applier registers nothing and touches no handler`() {
        mockkStatic(ContextCompat::class)
        try {
            every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager

            AudioRouteApplier(
                appContext = context,
                host = host,
                audioHandler = audioHandler,
                scope = CoroutineScope(SupervisorJob()),
            )

            // The ViewModel constructs this eagerly in Phase A; a receiver or coroutine here would
            // fire during every ViewModel test.
            verify(exactly = 0) { ContextCompat.registerReceiver(any(), any(), any(), any()) }
            confirmVerified(audioHandler)
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }

    // ── #53 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `each attempt reports exactly one outcome, or none when ownership is lost`() = runTest {
        // Confirmed.
        val confirmedHost = FakeAudioRouteHost()
        scoOn = true
        startApplier(confirmedHost).let { applier ->
            confirmedHost.emit(applyingSnapshot(bt, listOf(bt, spk)))
            runCurrent()
            assertEquals(1, confirmedHost.terminalCount)
            assertEquals(listOf(bt), confirmedHost.confirmed)
            applier.stop()
        }

        // TIMEOUT.
        scoOn = false
        val timeoutHost = FakeAudioRouteHost()
        startApplier(timeoutHost).let { applier ->
            timeoutHost.emit(applyingSnapshot(bt, listOf(bt, spk)))
            advanceTimeBy(5_600)
            assertEquals(1, timeoutHost.terminalCount)
            assertEquals(listOf(bt to AudioRouteFailure.TIMEOUT), timeoutHost.failed)
            applier.stop()
        }

        // DEVICE_GONE.
        val goneHost = FakeAudioRouteHost()
        startApplier(goneHost).let { applier ->
            goneHost.emit(applyingSnapshot(bt, listOf(bt, spk)))
            runCurrent()
            goneHost.setDevices(listOf(spk))
            advanceTimeBy(2_000)
            assertEquals(1, goneHost.terminalCount)
            assertEquals(listOf(bt to AudioRouteFailure.DEVICE_GONE), goneHost.failed)
            applier.stop()
        }

        // ERROR — an unexpected exception escaping the loop.
        val errorHost = FakeAudioRouteHost()
        startApplier(errorHost).let { applier ->
            errorHost.emit(applyingSnapshot(bt, listOf(bt, spk)))
            runCurrent()
            errorHost.failSnapshotRead = true
            advanceTimeBy(600)
            errorHost.failSnapshotRead = false
            assertEquals(1, errorHost.terminalCount)
            assertEquals(listOf(bt to AudioRouteFailure.ERROR), errorHost.failed)
            applier.stop()
        }

        // Ownership lost — no outcome at all.
        val abortedHost = FakeAudioRouteHost()
        startApplier(abortedHost).let { applier ->
            abortedHost.emit(applyingSnapshot(bt, listOf(bt, spk)))
            runCurrent()
            abortedHost.setState(AudioRouteState.Idle)
            advanceTimeBy(6_000)
            assertEquals(0, abortedHost.terminalCount)
            applier.stop()
        }
    }

    // ── #54 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `losing ownership of the attempt aborts silently`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(600)
        val callsAtInvalidation = selectCalls.size

        // What `onAudioSwitchInvalidated` does: pendingRoute does not emit for it, so only the
        // per-round ownership check can end this attempt.
        host.setState(AudioRouteState.Idle)
        advanceTimeBy(6_000)

        assertEquals(0, host.terminalCount)
        assertEquals(callsAtInvalidation, selectCalls.size)
    }

    // ── #55 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a replayed target after a generation boundary restarts with a fresh budget`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        advanceTimeBy(600)

        // Generation reset: state back to Idle and the device list emptied.
        host.emit(AudioRouteSnapshot(requested = bt))
        advanceTimeBy(600)
        assertEquals("the abandoned attempt reports nothing", 0, host.terminalCount)

        // Ready edge replays the same target: Applying -> Idle -> Applying re-emits.
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()
        val callsAtRestart = selectCalls.size
        assertTrue("the replayed attempt drives again", callsAtRestart > 4)

        advanceTimeBy(4_600)
        assertTrue("the first attempt's elapsed time must not count", host.failed.isEmpty())
        advanceTimeBy(1_000)
        assertEquals(listOf(bt to AudioRouteFailure.TIMEOUT), host.failed)
    }
}
