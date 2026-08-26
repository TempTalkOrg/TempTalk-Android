package com.difft.android.call.media

import android.content.Context
import android.media.AudioManager
import com.difft.android.call.btDevice
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.spkDevice
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Absence debounce, parking and failure classification (design inventory rows #33, #34 and group F
 * rows #118, #119, #120, #123, #125).
 *
 * These rows exist because the library's scanner mirrors `AudioManager.getDevices()` verbatim: ANY
 * Bluetooth disconnect+reconnect pair — a cross-host handover, a link twitch — produces at least one
 * poll where the target kind is missing. Treating a single poll as removal reports DEVICE_GONE for a
 * device that is coming back; latching "was ever missing" across the attempt does the mirror-image
 * damage, misclassifying a genuine TIMEOUT and disarming the retry loop guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteAbsenceTest {

    private val context = mockk<Context>(relaxed = true)
    private val audioHandler = mockk<AudioSwitchHandler>(relaxed = true)
    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val host = FakeAudioRouteHost()
    private val selectCalls = mutableListOf<AudioDevice?>()

    private var scoOn = false

    private val bt = btDevice()
    private val spk = spkDevice()

    private val present = listOf(bt, spk)
    private val absent = listOf(spk)

    @Before
    @Suppress("DEPRECATION") // the legacy readings are the applier's primary judgement
    fun setUp() {
        every { audioManager.isBluetoothScoOn } answers { scoOn }
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

    private fun TestScope.startApplier(): AudioRouteApplier {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AudioRouteApplier(
            appContext = context,
            host = host,
            audioHandler = audioHandler,
            scope = backgroundScope,
            audioManager = audioManager,
            workDispatcher = dispatcher,
        ).also { it.start() }
    }

    /**
     * Sets the list the *next* poll will read, then lets exactly that poll run.
     *
     * `runCurrent()` is required: `advanceTimeBy` does not run a task scheduled at exactly the
     * moment it lands on, and a poll is scheduled exactly one interval out.
     */
    private fun TestScope.poll(devices: List<AudioDevice>) {
        host.setDevices(devices)
        advanceTimeBy(500)
        runCurrent()
    }

    // ── #34 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `an empty device list parks the attempt without spending the budget`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, devices = emptyList()))
        advanceTimeBy(10_000)

        // An empty list means no live AudioSwitch, so every selectDevice would be dropped silently.
        // Burning the budget here would produce a fake Failed that arms the loop guard against the
        // next generation's own pick.
        assertTrue(selectCalls.isEmpty())
        assertEquals(0, host.terminalCount)

        host.setDevices(present)
        runCurrent()
        assertEquals("the budget starts when devices arrive", listOf<AudioDevice?>(null, bt), selectCalls)

        advanceTimeBy(4_600)
        assertTrue(host.failed.isEmpty())
        advanceTimeBy(1_000)
        assertEquals(listOf(bt to AudioRouteFailure.TIMEOUT), host.failed)
    }

    // ── #33 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a target that stays missing ends the attempt as DEVICE_GONE`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()

        host.setDevices(absent)
        advanceTimeBy(2_000)

        assertEquals(listOf(bt to AudioRouteFailure.DEVICE_GONE), host.failed)
        assertTrue("well inside the 5000ms budget", currentTime < 5_000)
    }

    // ── #119 ────────────────────────────────────────────────────────────────────
    @Test
    fun `DEVICE_GONE is declared on the third consecutive missing poll`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()

        poll(absent) // 1st missing poll, t=500
        assertTrue(host.failed.isEmpty())
        poll(absent) // 2nd, t=1000
        assertTrue(host.failed.isEmpty())
        poll(absent) // 3rd, t=1500 → confirmed removal

        assertEquals(listOf(bt to AudioRouteFailure.DEVICE_GONE), host.failed)
        assertEquals(1_500, currentTime)
    }

    // ── #118 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a single missing poll is a twitch, not a removal`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()
        val callsBeforeGap = selectCalls.size

        poll(absent)
        assertEquals("a missing target is not driven", callsBeforeGap, selectCalls.size)

        poll(present)
        advanceTimeBy(1_500)

        assertTrue("no failure of any kind, least of all DEVICE_GONE", host.failed.isEmpty())
        assertTrue("driving resumed once the target came back", selectCalls.size > callsBeforeGap)
    }

    // ── #120 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a twitch that recovers and then fails to route is a TIMEOUT, not DEVICE_GONE`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()

        poll(absent)  // one transient gap…
        poll(present) // …then present for the rest of the budget
        advanceTimeBy(5_000)

        // Classifying by history instead of by the final reading would say DEVICE_GONE here, which
        // does not arm the loop guard — and the library's next automatic pick would reopen the
        // attempt, forever, with no real device removal involved.
        assertEquals(listOf(bt to AudioRouteFailure.TIMEOUT), host.failed)
    }

    // ── #125 ────────────────────────────────────────────────────────────────────
    @Test
    fun `intermittent absence that is still missing at the deadline is DEVICE_GONE`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()

        // Two-missing / one-present cycles: never three in a row, so the debounce never fires and
        // the classification falls to the final reading.
        repeat(3) {
            poll(absent)
            poll(absent)
            poll(present)
        }
        assertTrue(host.failed.isEmpty())

        host.setDevices(absent)
        advanceTimeBy(600)

        assertEquals(listOf(bt to AudioRouteFailure.DEVICE_GONE), host.failed)
    }

    // ── #123 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a twitch does not interrupt an attempt that later succeeds`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()

        poll(absent)
        poll(present)
        scoOn = true
        poll(present)

        assertEquals(listOf(bt), host.confirmed)
        assertTrue(host.failed.isEmpty())
    }
}
