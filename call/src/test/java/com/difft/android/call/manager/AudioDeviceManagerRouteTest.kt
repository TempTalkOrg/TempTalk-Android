package com.difft.android.call.manager

import android.content.Context
import app.cash.turbine.test
import com.difft.android.base.call.CallType
import com.difft.android.base.user.UserManager
import com.difft.android.call.btDevice
import com.difft.android.call.earDevice
import com.difft.android.call.spkDevice
import com.difft.android.call.ui.hornPresentation
import com.difft.android.call.ui.shouldShowAudioDevicePanel
import com.difft.android.call.ui.toDeviceRows
import com.difft.android.call.wiredDevice
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// The manager fixtures below are file-level on purpose: `LargeClass` is measured per class, so this
// file holds one class per concern and every class needs the same three helpers. Neither collaborator
// is ever stubbed or verified — the manager reads them lazily and drives nothing.

private fun newManager(callType: String = CallType.ONE_ON_ONE.type) =
    AudioDeviceManager(mockk<Context>(relaxed = true), callType, mockk<UserManager>(relaxed = true))

/** Seeds the device list without producing any confirmation (R3 keeps the state `Idle`). */
private fun AudioDeviceManager.seedDevices(vararg devices: AudioDevice) =
    onLibraryDevicesChanged(devices.toList(), null)

/** Drives [target] to `Confirmed` through the library-pick path, leaving `requested` null. */
private fun AudioDeviceManager.confirmViaLibrary(devices: List<AudioDevice>, target: AudioDevice) {
    onLibraryDevicesChanged(devices, target)
    onRouteConfirmed(target)
}

/**
 * Route-state contract tests for [AudioDeviceManager] (design inventory group A, rows #1–#23 and
 * #126, plus the manager-side halves of #101–#103).
 *
 * Pure JVM. None of these paths DRIVES the platform — driving belongs to the `pendingRoute`
 * collector — and `select()` (#1) plus construction (#21) are pinned not to touch `audioHandler` at
 * all. The generation-boundary rows do reach it: `onAudioSwitchInvalidated` READS two of its
 * properties to re-check whether the generation really is gone, which
 * [AudioDeviceManagerReseedTest] pins positively (reads, never `selectDevice`). Here the handler is
 * the real, device-less one, so those reads answer empty and the rows below are unaffected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioDeviceManagerRouteTest {

    /**
     * True once the `audioHandler` lazy has been evaluated.
     *
     * Reads the generated delegate field on purpose: it is the only way to assert "the manager did
     * not touch the library at all", which is strictly stronger than counting calls on a handler
     * the manager builds itself. If `audioHandler` ever stops being a `by lazy`, this fails loudly
     * — which is the intent.
     */
    private fun AudioDeviceManager.audioHandlerInitialized(): Boolean {
        val field = AudioDeviceManager::class.java.getDeclaredField("audioHandler\$delegate")
        field.isAccessible = true
        return (field.get(this) as Lazy<*>).isInitialized()
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ── #1 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `select opens an attempt without confirming or driving the library`() {
        val bt = btDevice()
        val manager = newManager()
        manager.seedDevices(bt, spkDevice())

        manager.select(bt)

        assertEquals(AudioRouteState.Applying(bt), manager.routeState)
        assertNull(manager.confirmed)
        assertSame(bt, manager.requested)
        assertFalse("select() must not touch audioHandler", manager.audioHandlerInitialized())
    }

    // ── #2 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `onRouteConfirmed is the only writer of the check mark`() = runTest(UnconfinedTestDispatcher()) {
        val bt = btDevice()
        val manager = newManager()
        manager.seedDevices(bt, spkDevice())
        manager.select(bt)

        manager.routeSnapshot.test {
            awaitItem()
            manager.onRouteConfirmed(bt)
            val after = awaitItem()
            assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, after.confirmed?.kind)
            assertEquals(AudioRouteState.Confirmed(bt), after.state)
            expectNoEvents()
        }
    }

    // ── #3 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `onRouteFailed clears the check mark and records the cause`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), bt)
        manager.select(bt)

        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)

        assertNull(manager.confirmed)
        assertEquals(AudioRouteState.Failed(bt, AudioRouteFailure.TIMEOUT), manager.routeState)
    }

    // ── #4 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `a late onRouteFailed for another target is dropped`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(bt, spk)
        manager.select(spk)
        val before = manager.routeSnapshot.value

        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)

        assertEquals(before, manager.routeSnapshot.value)
    }

    // ── #5 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `R1 suppresses the null selection echo of the round trip`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), bt)
        manager.select(bt)

        manager.onLibraryDevicesChanged(listOf(bt, spk), null)

        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.confirmed?.kind)
        assertEquals(AudioRouteState.Applying(bt), manager.routeState)
        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
    }

    // ── #6 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `R1 covers every callback in the window, not only the null one`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), spk)
        manager.select(bt)

        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)

        // The library reporting `bt` must NOT promote it to confirmed: that is the defect.
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.confirmed?.kind)
        assertEquals(AudioRouteState.Applying(bt), manager.routeState)
    }

    // ── #7 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `R3 clears the check mark when the library routes nothing`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), bt)

        manager.onLibraryDevicesChanged(listOf(spk), null)

        assertNull(manager.confirmed)
        assertEquals(AudioRouteState.Idle, manager.routeState)
    }

    // ── #8 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `R2 clears the check mark when the confirmed device is unplugged`() {
        val bt = btDevice()
        val spk = spkDevice()
        val ear = earDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), bt)

        manager.onLibraryDevicesChanged(listOf(spk, ear), spk)

        assertNull(manager.confirmed)
        assertEquals(AudioRouteState.Idle, manager.routeState)
    }

    // ── #9 ──────────────────────────────────────────────────────────────────────
    @Test
    fun `R4 keeps the check mark when only the bluetooth name changed`() = runTest(UnconfinedTestDispatcher()) {
        val btA = btDevice("Bluetooth")
        val spk = spkDevice()
        val renamed = btDevice("Nate's AirPods")
        val manager = newManager()
        manager.confirmViaLibrary(listOf(btA, spk), btA)

        manager.routeSnapshot.test {
            val before = awaitItem()
            manager.onLibraryDevicesChanged(listOf(btA, spk), renamed)
            expectNoEvents()
            assertSame(btA, before.confirmed)
            assertEquals(AudioRouteState.Confirmed(btA), manager.routeState)
        }
    }

    // ── #10 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `R6 turns the library's own pick into an attempt that must be verified`() {
        val bt = btDevice()
        val manager = newManager()

        manager.onLibraryDevicesChanged(listOf(bt, spkDevice()), bt)

        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
        assertNull(manager.confirmed)
        assertNull("the library's pick is not user intent", manager.requested)
    }

    // ── #127 (R1a) ──────────────────────────────────────────────────────────────
    /**
     * Reproduces the reported fingerprint verbatim: a progressive enumeration where the earpiece
     * enumerates first (arming R6) and Bluetooth lands a poll later on the same generation. Without
     * R1a, R1's blanket suppression would strand the state on the earpiece.
     */
    @Test
    fun `R1a lets a newer library pick supersede an in-flight LIBRARY attempt`() {
        val bt = btDevice()
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()

        manager.onLibraryDevicesChanged(listOf(ear), ear)
        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), manager.routeState)

        manager.onLibraryDevicesChanged(listOf(bt, ear, spk), bt)

        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
        assertNull(manager.confirmed)
    }

    // ── #128 (R1a) ──────────────────────────────────────────────────────────────
    /** Same scenario as #6, re-asserted with the origin field present: a USER attempt is never
     * superseded by R1a, which only ever carves an exception out of R1 for LIBRARY origin. */
    @Test
    fun `R1a never supersedes a USER attempt`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), spk)
        manager.select(bt)

        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)

        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.confirmed?.kind)
        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.USER), manager.routeState)
    }

    // ── #129 (R1a) ──────────────────────────────────────────────────────────────
    @Test
    fun `R1a does not fire when the library re-picks the same kind`() = runTest(UnconfinedTestDispatcher()) {
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()

        manager.pendingRoute.test {
            manager.onLibraryDevicesChanged(listOf(ear), ear)
            assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), awaitItem())

            // The list merely grew; the library re-picked the SAME kind — not new information.
            manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
            expectNoEvents()
        }
        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), manager.routeState)
    }

    // ── #130 (R1a) ──────────────────────────────────────────────────────────────
    @Test
    fun `R1 still suppresses a LIBRARY attempt's own null round-trip echo`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)

        manager.onLibraryDevicesChanged(listOf(bt, spk), null)

        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
    }

    // ── #131 (R1a) ──────────────────────────────────────────────────────────────
    /**
     * A user tapping the device the library already auto-picked upgrades the attempt to
     * USER-protected: intentional, not a regression (a further, different library pick can no
     * longer supersede it once it is user-owned).
     */
    @Test
    fun `a user tap upgrades an in-flight LIBRARY attempt's origin and restarts pendingRoute`() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = btDevice()
            val manager = newManager()

            manager.pendingRoute.test {
                manager.onLibraryDevicesChanged(listOf(bt, spkDevice()), bt)
                assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), awaitItem())

                manager.select(bt)
                assertEquals(AudioRouteState.Applying(bt, RouteOrigin.USER), awaitItem())
            }
            assertEquals(AudioRouteState.Applying(bt, RouteOrigin.USER), manager.routeState)
        }

    // ── #132 (R1a) ──────────────────────────────────────────────────────────────
    /**
     * The lifecycle guard's ready-edge replay (`AudioRouteLifecycleGuard.onSwitchReady`)
     * deliberately skips `select()` when a LIBRARY pick from R6 already targets the kind
     * `requested` holds, to avoid restarting the attempt. That attempt still carries the user's
     * explicit intent and must be as protected as a real USER attempt: R1a must not treat a later,
     * different library pick as an ordinary library-to-library handoff here.
     */
    @Test
    fun `R1a does not supersede a LIBRARY attempt that matches the requested kind`() {
        val bt = btDevice()
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), bt)
        manager.select(bt)
        manager.onAudioSwitchInvalidated("test")
        // New generation: R6 lands on the user's own kind before any replay ever runs.
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), bt)
        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)

        // A further library pick on a DIFFERENT kind must not take over the user's device.
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), ear)

        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    // ── #133 ────────────────────────────────────────────────────────────────────
    /**
     * The applier's per-round `ownsAttempt` check and its terminal report are not atomic with each
     * other, so a confirm for an attempt whose ownership was already lost (no outstanding
     * `Applying` at all) can still reach here. It must be dropped whole — including `confirmed` —
     * never partially honoured, or it would resurrect a check mark nothing has verified for the
     * current attempt or generation.
     */
    @Test
    fun `a confirm with no outstanding attempt is ignored`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), bt)
        manager.select(bt)
        manager.onAudioSwitchInvalidated("test")
        val before = manager.routeSnapshot.value

        manager.onRouteConfirmed(bt)

        assertEquals(before, manager.routeSnapshot.value)
        assertNull(manager.confirmed)
        assertEquals(AudioRouteState.Idle, manager.routeState)
    }

    // ── #11 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `R5 stops device-list twitches from restarting a TIMEOUT failure`() = runTest(UnconfinedTestDispatcher()) {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(bt, spk)
        manager.select(bt)
        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)

        manager.routeSnapshot.test {
            awaitItem()
            repeat(3) { manager.onLibraryDevicesChanged(listOf(bt, spk), bt) }
            expectNoEvents()
        }
        assertEquals(AudioRouteState.Failed(bt, AudioRouteFailure.TIMEOUT), manager.routeState)
    }

    // ── #12 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `R5 never blocks an explicit user retry`() {
        val bt = btDevice()
        val manager = newManager()
        manager.seedDevices(bt, spkDevice())
        manager.select(bt)
        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)

        manager.select(bt)

        assertEquals(AudioRouteState.Applying(bt), manager.routeState)
    }

    // ── #13 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `re-requesting the in-flight target does not restart the attempt`() = runTest(UnconfinedTestDispatcher()) {
        val bt = btDevice()
        val manager = newManager()
        manager.seedDevices(bt, spkDevice())

        manager.pendingRoute.test {
            manager.select(bt)
            assertEquals(AudioRouteState.Applying(bt), awaitItem())
            manager.select(bt)
            expectNoEvents()
        }
    }

    // ── #14 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `pendingRoute emits every distinct target so the driver can cancel the previous one`() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = btDevice()
            val spk = spkDevice()
            val manager = newManager()
            manager.seedDevices(bt, spk)

            manager.pendingRoute.test {
                manager.select(bt)
                assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, awaitItem().device.kind)
                manager.select(spk)
                assertEquals(AudioDeviceKind.SPEAKERPHONE, awaitItem().device.kind)
            }
        }

    // ── #15 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `switchToNext rotates from the confirmed device`() {
        val bt = btDevice()
        val spk = spkDevice()
        val ear = earDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk, ear), bt)

        manager.switchToNext()

        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
    }

    // ── #16 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `switchToNext is a real ring over three devices`() {
        val bt = btDevice()
        val spk = spkDevice()
        val ear = earDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk, ear), bt)

        val visited = mutableListOf<AudioDeviceKind>()
        repeat(3) {
            manager.switchToNext()
            val target = (manager.routeState as AudioRouteState.Applying).device
            visited += target.kind
            manager.onRouteConfirmed(target)
        }

        assertEquals(
            listOf(
                AudioDeviceKind.SPEAKERPHONE,
                AudioDeviceKind.EARPIECE,
                AudioDeviceKind.BLUETOOTH_HEADSET,
            ),
            visited,
        )
    }

    // ── #17 ─────────────────────────────────────────────────────────────────────
    /**
     * With nothing confirmed the anchor is the device the horn already depicts (the library's own
     * pick via `settledRoute`), so a tap must advance PAST it — anchoring on the pick itself would
     * make the first tap a visible no-op. See [AudioDeviceManagerSwitchRingTest].
     */
    @Test
    fun `switchToNext with nothing confirmed advances away from the library's own pick`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(bt, spk)

        manager.switchToNext()

        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
    }

    // ── #18 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `switchToNext with a single device is an observable no-op`() = runTest(UnconfinedTestDispatcher()) {
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(spk)
        val before = manager.routeSnapshot.value

        manager.pendingRoute.test {
            manager.switchToNext()
            expectNoEvents()
        }
        assertEquals(before, manager.routeSnapshot.value)
    }

    // ── #19 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `select refuses a device the library does not enumerate`() {
        val manager = newManager()
        manager.seedDevices(spkDevice())
        val before = manager.routeSnapshot.value

        manager.select(btDevice())

        assertEquals(before, manager.routeSnapshot.value)
    }

    // ── #20 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `select is accepted while the device list is still empty`() {
        val bt = btDevice()
        val manager = newManager()

        manager.select(bt)

        assertEquals(AudioRouteState.Applying(bt), manager.routeState)
        assertSame(bt, manager.requested)
    }

    // ── #21 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `the initial snapshot never comes from the library`() {
        val manager = newManager()

        assertEquals(AudioRouteSnapshot(), manager.routeSnapshot.value)
        assertFalse(
            "construction must not read the library's selectedAudioDevice",
            manager.audioHandlerInitialized(),
        )
    }

    // ── #22 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `every emitted snapshot is internally consistent`() = runTest(UnconfinedTestDispatcher()) {
        val bt = btDevice()
        val manager = newManager()
        manager.seedDevices(bt, spkDevice())

        manager.routeSnapshot.test {
            val seen = mutableListOf(awaitItem())
            manager.select(bt)
            seen += awaitItem()
            manager.onRouteConfirmed(bt)
            seen += awaitItem()

            seen.forEach { snap ->
                val state = snap.state
                if (state is AudioRouteState.Confirmed) {
                    assertEquals(state.device.kind, snap.confirmed?.kind)
                }
            }
        }
    }

    // ── #23 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `kind maps every AudioDevice subclass`() {
        assertEquals(AudioDeviceKind.EARPIECE, earDevice().kind)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, spkDevice().kind)
        assertEquals(AudioDeviceKind.WIRED_HEADSET, wiredDevice().kind)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, btDevice().kind)
    }

    // ── #101 ────────────────────────────────────────────────────────────────────
    @Test
    fun `invalidating the AudioSwitch generation keeps user intent and drops everything else`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(bt, spk)
        manager.select(bt)
        manager.onRouteConfirmed(bt)

        manager.onAudioSwitchInvalidated("roomDisconnected")

        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
        assertNull(manager.confirmed)
        assertEquals(AudioRouteState.Idle, manager.routeState)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    // ── #102 ────────────────────────────────────────────────────────────────────
    @Test
    fun `invalidation outranks R1 so an attempt cannot survive a generation boundary`() {
        val bt = btDevice()
        val manager = newManager()
        manager.seedDevices(bt, spkDevice())
        manager.select(bt)

        manager.onAudioSwitchInvalidated("roomDisconnected")

        assertEquals(AudioRouteState.Idle, manager.routeState)
        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
    }

    // ── #103 ────────────────────────────────────────────────────────────────────
    @Test
    fun `invalidation outranks R5 so the new generation is not locked out`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(bt, spk)
        manager.select(bt)
        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)

        manager.onAudioSwitchInvalidated("roomDisconnected")
        assertEquals(AudioRouteState.Idle, manager.routeState)

        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
    }

    // ── #126 ────────────────────────────────────────────────────────────────────
    /**
     * Pins the T4 rule branch of `onRouteConfirmed`: a confirmation for a different kind than
     * the one applying records the observation without ending the outstanding attempt.
     *
     * A confirmation for a kind other than the one being applied is a real observed fact and is
     * recorded in `confirmed`, but it must NOT collapse the state to `Confirmed`: the attempt on
     * the requested device is still outstanding, and ending it here would strand the route in a
     * `Confirmed` state nobody asked for while the pending attempt is still being driven.
     */
    @Test
    fun `T4 records a foreign confirmation without ending the outstanding attempt`() {
        val ear = earDevice()
        val bt = btDevice()
        val manager = newManager()
        manager.seedDevices(ear, bt)
        manager.select(ear)

        manager.onRouteConfirmed(bt)

        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.confirmed?.kind)
        assertEquals(AudioRouteState.Applying(ear), manager.routeState)
    }
}

/**
 * Resolution-formula contract tests for the [AudioRouteSnapshot] extensions
 * ([settledRoute] / [userPendingRoute] / [targetedRoute]) — pure functions over a hand-built
 * snapshot, no [AudioDeviceManager] involved.
 *
 * Deliberately a second top-level class in this file rather than a new file: the route suites stay
 * co-located, while detekt's `LargeClass` gate (500 lines, matching the project's hard file-size
 * rule) is measured per class, and [AudioDeviceManagerRouteTest] is already close to it.
 */
class AudioRouteSnapshotResolutionTest {

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ── RT-1 ────────────────────────────────────────────────────────────────────
    @Test
    fun `settledRoute prefers the observed fact over an in-flight target`() {
        val spk = spkDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spk),
            confirmed = spk,
            state = AudioRouteState.Applying(earDevice(), RouteOrigin.USER),
        )

        assertSame(spk, snapshot.settledRoute)
    }

    // ── RT-2 ────────────────────────────────────────────────────────────────────
    @Test
    fun `settledRoute falls back to the first enumerated device that is not the failed kind`() {
        val spk = spkDevice()
        val ear = earDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(spk, ear),
            confirmed = null,
            state = AudioRouteState.Failed(spk, AudioRouteFailure.TIMEOUT),
        )

        assertSame(ear, snapshot.settledRoute)
    }

    // ── RT-3 ────────────────────────────────────────────────────────────────────
    @Test
    fun `settledRoute is null when nothing is observed and nothing is enumerated`() {
        assertNull(AudioRouteSnapshot().settledRoute)
    }

    // ── RT-4 ────────────────────────────────────────────────────────────────────
    @Test
    fun `userPendingRoute reports an enumerable non-bluetooth USER attempt`() {
        val spk = spkDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spk),
            state = AudioRouteState.Applying(spk, RouteOrigin.USER),
        )

        assertSame(spk, snapshot.userPendingRoute)
    }

    // ── RT-5 ────────────────────────────────────────────────────────────────────
    @Test
    fun `userPendingRoute ignores a LIBRARY attempt because no gesture is outstanding`() {
        val spk = spkDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spk),
            state = AudioRouteState.Applying(spk, RouteOrigin.LIBRARY),
        )

        assertNull(snapshot.userPendingRoute)
    }

    // ── RT-6 ────────────────────────────────────────────────────────────────────
    @Test
    fun `userPendingRoute is null for every non-Applying state`() {
        val spk = spkDevice()
        val devices = listOf(earDevice(), spk)
        val states = listOf(
            AudioRouteState.Idle,
            AudioRouteState.Confirmed(spk),
            AudioRouteState.Failed(spk, AudioRouteFailure.TIMEOUT),
        )

        states.forEach { state ->
            val snapshot = AudioRouteSnapshot(availableDevices = devices, state = state)
            assertNull("state $state must carry no pending gesture", snapshot.userPendingRoute)
        }
    }

    // ── RT-6a ───────────────────────────────────────────────────────────────────
    /**
     * The enumerable conjunct, isolated: a USER attempt whose kind the library does not enumerate can
     * never be confirmed from this snapshot, so it is neither depictable nor usable as a ring anchor
     * (`indexOfFirst` would land on -1 and restart the ring at `devices[0]`).
     */
    @Test
    fun `userPendingRoute rejects a USER attempt the device list does not contain`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spkDevice()),
            state = AudioRouteState.Applying(btDevice(), RouteOrigin.USER),
        )

        assertNull(snapshot.userPendingRoute)
    }

    // ── RT-6b ───────────────────────────────────────────────────────────────────
    /** The same narrowing without Bluetooth — membership, not a Bluetooth special case. */
    @Test
    fun `userPendingRoute rejects a non-enumerable wired attempt too`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spkDevice()),
            state = AudioRouteState.Applying(wiredDevice(), RouteOrigin.USER),
        )

        assertNull(snapshot.userPendingRoute)
    }

    // ── RT-6c ───────────────────────────────────────────────────────────────────
    /**
     * The Bluetooth conjunct, isolated: eligibility fails on kind even when membership holds, so the
     * ruling "a Bluetooth activation never earns a horn-level optimistic visual" is a property of this
     * formula rather than a consequence of the panel formula.
     */
    @Test
    fun `userPendingRoute rejects an enumerable bluetooth attempt`() {
        val bt = btDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(bt, spkDevice()),
            state = AudioRouteState.Applying(bt, RouteOrigin.USER),
        )

        assertNull(snapshot.userPendingRoute)
    }

    // ── RT-6d ───────────────────────────────────────────────────────────────────
    /** The `select()`-on-empty-list path (T3) that makes RT-6a reachable with no race at all. */
    @Test
    fun `userPendingRoute is null while the device list is still empty`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = emptyList(),
            state = AudioRouteState.Applying(spkDevice(), RouteOrigin.USER),
        )

        assertNull(snapshot.userPendingRoute)
    }

    // ── RT-15 ───────────────────────────────────────────────────────────────────
    @Test
    fun `targetedRoute prefers the in-flight target over the observed fact`() {
        val spk = spkDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spk),
            confirmed = earDevice(),
            state = AudioRouteState.Applying(spk, RouteOrigin.LIBRARY),
        )

        assertSame(spk, snapshot.targetedRoute)
    }

    // ── RT-16 ───────────────────────────────────────────────────────────────────
    @Test
    fun `targetedRoute falls back to the observed fact with no attempt in flight`() {
        val ear = earDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spkDevice()),
            confirmed = ear,
            state = AudioRouteState.Idle,
        )

        assertSame(ear, snapshot.targetedRoute)
    }
}

/**
 * Ring-anchor tests for [AudioDeviceManager.switchToNext] — the toggle surface's "one tap always
 * moves audio somewhere else" contract, driven through a real manager.
 *
 * The anchor is `userPendingRoute ?: settledRoute`, i.e. the device the horn is depicting. Every row
 * below fails against a `confirmed`-only anchor, which returned -1 from `indexOfFirst` and restarted
 * the ring at `devices[0]` — in a 1v1 the earpiece the call is already using.
 *
 * A third top-level class in this file for the same reason as [AudioRouteSnapshotResolutionTest]:
 * detekt's `LargeClass` gate is measured per class, and the suites stay co-located.
 */
class AudioDeviceManagerSwitchRingTest {

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ── RT-17 ───────────────────────────────────────────────────────────────────
    @Test
    fun `the first tap of a 1v1 call advances instead of re-selecting the earpiece`() {
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(ear, spk)

        manager.switchToNext()

        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
    }

    // ── RT-19 ───────────────────────────────────────────────────────────────────
    @Test
    fun `a tap during an unconfirmed user attempt advances from the pending target`() {
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(ear, spk)
        manager.select(spk)

        manager.switchToNext()

        assertEquals(AudioRouteState.Applying(ear), manager.routeState)
    }

    // ── RT-20 ───────────────────────────────────────────────────────────────────
    @Test
    fun `a tap during the library's own attempt advances from the library pick`() {
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        // R6 at connect: the library picked the earpiece itself, so the horn shows it solid.
        manager.onLibraryDevicesChanged(listOf(ear, spk), ear)

        manager.switchToNext()

        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
    }

    // ── RT-21 ───────────────────────────────────────────────────────────────────
    @Test
    fun `a tap after a failed attempt retries it instead of alternating back`() {
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(ear, spk)
        manager.select(spk)
        manager.onRouteFailed(spk, AudioRouteFailure.TIMEOUT)

        manager.switchToNext()

        // The failed kind is skipped by settledRoute, so the anchor is the earpiece the call is
        // actually on and the tap retries speaker — an anchor carrying `requested` would have
        // anchored on the failed speaker and re-selected the earpiece.
        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
    }

    // ── RT-23 ───────────────────────────────────────────────────────────────────
    @Test
    fun `a wired-and-speaker ring alternates in both directions`() {
        val wired = wiredDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(wired, spk), wired)

        manager.switchToNext()
        assertEquals(AudioRouteState.Applying(spk), manager.routeState)

        manager.onRouteConfirmed(spk)
        manager.switchToNext()
        assertEquals(AudioRouteState.Applying(wired), manager.routeState)
    }

    // ── RT-25c ──────────────────────────────────────────────────────────────────
    @Test
    fun `a tap anchored on a pending target the list no longer holds still advances`() {
        val bt = btDevice()
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        // T3 accepts a target while the list is empty ("cannot drive right now" != "absent"), and R1
        // then preserves that USER attempt verbatim across the push that restores a list without it.
        manager.select(bt)
        manager.onLibraryDevicesChanged(listOf(ear, spk), null)
        assertEquals(AudioRouteState.Applying(bt), manager.routeState)

        manager.switchToNext()

        // The pending target is not enumerable, so it is not toggle-eligible and the anchor falls
        // back to the earpiece the horn depicts. A raw `Applying.device` anchor would have landed
        // `indexOfFirst` on -1 and re-selected that earpiece.
        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
    }
}

/**
 * Pull-reseed contract: the manager's two library-pull entry points
 * ([AudioDeviceManager.onAudioSwitchInvalidated] and [AudioDeviceManager.reseedFromLibrary]).
 *
 * The defect these rows exist for: a generation boundary wipes the device list on the assumption that
 * the `AudioSwitch` is gone, and the library pushes a list only when it CHANGES — so a spurious
 * boundary left a live call with no devices, no panel and no route for its whole duration. The pull is
 * a truth check on that assumption.
 *
 * The manager builds its own `AudioSwitchHandler` from a `Context`, so intercepting the construction is
 * the only way to give it a library view — hence `mockkConstructor`, undone in [tearDown] so both the
 * rows that need the REAL device-less handler and every other suite sharing this JVM see the untouched
 * class. Both library properties are always stubbed explicitly: a relaxed answer for the nullable
 * `selectedAudioDevice` returns a child mock, and `kind`'s exhaustive `when` would then throw instead
 * of failing an assertion.
 *
 * A fourth top-level class in this file for the same reason as [AudioRouteSnapshotResolutionTest]:
 * detekt's `LargeClass` gate is measured per class, and the route suites stay co-located.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioDeviceManagerReseedTest {

    private val ear = earDevice()
    private val spk = spkDevice()
    private val bt = btDevice()

    /** The view `readLibraryView()` will observe. `var` so a row can heal or race mid-test. */
    private var pulledDevices: List<AudioDevice> = emptyList()
    private var pulledSelection: AudioDevice? = null

    @After
    fun tearDown() {
        unmockkConstructor(AudioSwitchHandler::class)
        clearAllMocks()
    }

    /** Intercepts the handler the manager constructs, stubbing only what the manager itself writes. */
    private fun mockLibraryConstruction() {
        mockkConstructor(AudioSwitchHandler::class)
        every { anyConstructed<AudioSwitchHandler>().loggingEnabled = any() } just Runs
        every { anyConstructed<AudioSwitchHandler>().preferredDeviceList = any() } just Runs
        every { anyConstructed<AudioSwitchHandler>().selectDevice(any()) } just Runs
    }

    private fun stubLibrary(devices: List<AudioDevice> = emptyList(), selected: AudioDevice? = null) {
        mockLibraryConstruction()
        pulledDevices = devices
        pulledSelection = selected
        every { anyConstructed<AudioSwitchHandler>().selectedAudioDevice } answers { pulledSelection }
        every { anyConstructed<AudioSwitchHandler>().availableAudioDevices } answers { pulledDevices }
    }

    /**
     * The deterministic RACE-1 setup: the FIRST list read commits a push that ADDS [bt] as a side
     * effect and still returns the pre-push list; every later read returns the post-push view.
     *
     * Injecting on call #1 only is mandatory, not tidiness: the loop re-reads per attempt, so a side
     * effect that re-fired would commit a new snapshot on every attempt and the `compareAndSet` could
     * never win — the row would hang instead of failing.
     */
    private fun stubPushRacingFirstRead(manager: AudioDeviceManager) {
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
    }

    private fun AudioDeviceManager.kinds(): List<AudioDeviceKind> =
        routeSnapshot.value.availableDevices.map { it.kind }

    // ── RS-1 ────────────────────────────────────────────────────────────────────
    /**
     * The pre-state is expressed through the contract entries that can actually produce it: a
     * `Confirmed(ear)` snapshot carrying `confirmed = ear`. (`Applying` plus a `confirmed` of the same
     * kind is unreachable — R6 clears `confirmed` whenever it opens a library attempt.) What the row
     * pins is the outcome: the list is back and the library's own pick is armed for verification.
     */
    @Test
    fun `an invalidate the library can disprove comes back with the list already restored`() {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()
        manager.confirmViaLibrary(listOf(ear, spk), ear)

        manager.onAudioSwitchInvalidated("roomState=CONNECTING")

        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), manager.routeState)
        assertNull(manager.confirmed)
    }

    // ── RS-2 ────────────────────────────────────────────────────────────────────
    /**
     * One publish, not two. `pendingRoute` is a conflating derivation of this flow, so an intermediate
     * `Idle`/empty frame could let `distinctUntilChanged` suppress the applier's re-arm after its own
     * ownership check had already abandoned the attempt — `Applying` with no driver, forever.
     */
    @Test
    fun `the wipe and the reseed reach collectors as exactly one snapshot`() = runTest(UnconfinedTestDispatcher()) {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()
        manager.confirmViaLibrary(listOf(ear, spk), ear)

        manager.routeSnapshot.test {
            awaitItem()
            manager.onAudioSwitchInvalidated("roomState=CONNECTING")
            val after = awaitItem()
            assertEquals(2, after.availableDevices.size)
            assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), after.state)
            expectNoEvents()
        }
    }

    // ── RS-2b ───────────────────────────────────────────────────────────────────
    /**
     * The same single-frame guarantee under contention: a lost attempt publishes nothing at all
     * (`compareAndSet` returns false without touching the value), so the retry loop cannot turn one
     * publish into several. Here the winning attempt recomputes a snapshot content-identical to the one
     * the concurrent push published, so it publishes no frame of its own either — stronger than the
     * requirement, and the reason the `switchInvalidated` log gate cannot be `after != before` alone.
     */
    @Test
    fun `a contended invalidate never publishes an empty or Idle frame`() = runTest(UnconfinedTestDispatcher()) {
        mockLibraryConstruction()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(ear, spk), ear)
        stubPushRacingFirstRead(manager)
        val seen = mutableListOf<AudioRouteSnapshot>()

        manager.routeSnapshot.test {
            seen += awaitItem()
            manager.onAudioSwitchInvalidated("roomState=CONNECTING")
            seen += awaitItem()
            expectNoEvents()
        }

        assertTrue("no empty frame", seen.none { it.availableDevices.isEmpty() })
        assertTrue("no Idle frame", seen.none { it.state == AudioRouteState.Idle })
        assertEquals(3, manager.routeSnapshot.value.availableDevices.size)
    }

    // ── RS-3 ────────────────────────────────────────────────────────────────────
    /** No stubbing at all: a genuinely dead switch answers empty, so the pull is a no-op. */
    @Test
    fun `an invalidate with no live switch behaves exactly like a bare wipe`() {
        val manager = newManager()
        manager.seedDevices(ear, spk)
        manager.select(spk)
        manager.onRouteConfirmed(spk)

        manager.onAudioSwitchInvalidated("roomState=DISCONNECTED")

        val snap = manager.routeSnapshot.value
        assertTrue(snap.availableDevices.isEmpty())
        assertEquals(AudioRouteState.Idle, snap.state)
        assertNull(snap.confirmed)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, snap.requested?.kind)
    }

    // ── RS-4 ────────────────────────────────────────────────────────────────────
    @Test
    fun `the wipe still outranks R5 so a dead generation's failure cannot latch`() {
        stubLibrary(listOf(bt, spk), bt)
        val manager = newManager()
        manager.seedDevices(bt, spk)
        manager.select(bt)
        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)

        manager.onAudioSwitchInvalidated("roomState=CONNECTING")

        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
    }

    // ── RS-5 ────────────────────────────────────────────────────────────────────
    /** The wipe zeroes every discriminator the reducer branches on, so a pulled view reaches R3 or R6 only. */
    @Test
    fun `a pulled view reaches only R3 or R6`() {
        stubLibrary(listOf(ear, spk), null)
        val manager = newManager()
        manager.confirmViaLibrary(listOf(ear, spk), ear)

        manager.onAudioSwitchInvalidated("first")

        assertEquals(AudioRouteState.Idle, manager.routeState)
        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)

        pulledSelection = ear
        manager.onAudioSwitchInvalidated("second")

        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), manager.routeState)
        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
    }

    // ── RS-6 ────────────────────────────────────────────────────────────────────
    @Test
    fun `requested outlives the AudioSwitch across a reseeding invalidate`() {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()
        manager.seedDevices(ear, spk)
        manager.select(spk)

        manager.onAudioSwitchInvalidated("roomState=CONNECTING")

        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), manager.routeState)
    }

    // ── RS-7 ────────────────────────────────────────────────────────────────────
    @Test
    fun `reseedFromLibrary recovers an empty list and never overwrites a populated one`() {
        stubLibrary(listOf(bt), bt)
        val manager = newManager()
        manager.seedDevices(ear, spk)
        val before = manager.routeSnapshot.value

        manager.reseedFromLibrary("test")

        assertEquals(before, manager.routeSnapshot.value)
        assertEquals(listOf(AudioDeviceKind.EARPIECE, AudioDeviceKind.SPEAKERPHONE), manager.kinds())
    }

    // ── RS-7b ───────────────────────────────────────────────────────────────────
    /**
     * The premise behind deciding the `applied` log flag by reference: `reduceLibraryChange` assigns the
     * caller's list once (R0) and every return path derives from that by copying OTHER fields only.
     * Covers a `withDevices.copy(...)`-returning branch (R6) and a bare `withDevices` branch (R1), plus
     * the dropped case. If a future reducer edit inserts a copy/sort/filter this row fails at the exact
     * line that would otherwise have turned `applied` into a silent lie.
     */
    @Test
    fun `the applied flag's reducer-identity premise holds on every return shape`() {
        val pulled = listOf(ear, spk)
        stubLibrary(pulled, ear)

        // (a) R6 — withDevices.copy(confirmed, state)
        val r6 = newManager()
        r6.reseedFromLibrary("a")
        assertSame(pulled, r6.routeSnapshot.value.availableDevices)
        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), r6.routeState)

        // (b) dropped — the short-circuit returns the snapshot itself
        val dropped = listOf(bt)
        pulledDevices = dropped
        pulledSelection = bt
        val kept = newManager()
        kept.seedDevices(ear, spk)
        kept.reseedFromLibrary("b")
        assertNotSame(dropped, kept.routeSnapshot.value.availableDevices)

        // (c) R1 — bare withDevices, with a USER attempt in flight
        pulledDevices = pulled
        pulledSelection = ear
        val r1 = newManager()
        r1.select(spk)
        r1.reseedFromLibrary("c")
        assertSame(pulled, r1.routeSnapshot.value.availableDevices)
        assertEquals(AudioRouteState.Applying(spk, RouteOrigin.USER), r1.routeState)
    }

    // ── RS-8 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a reseed restores the list without disturbing a user attempt in flight`() {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()
        manager.select(spk)

        manager.reseedFromLibrary("test")

        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioRouteState.Applying(spk, RouteOrigin.USER), manager.routeState)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
    }

    // ── RS-9 ────────────────────────────────────────────────────────────────────
    /** An empty pull must short-circuit: feeding `emptyList()` to the reducer would hit R3 and kill the attempt. */
    @Test
    fun `an empty pull is a no-op, never a wipe of a pending attempt`() {
        stubLibrary(emptyList(), null)
        val manager = newManager()
        manager.select(spk)
        val before = manager.routeSnapshot.value

        manager.reseedFromLibrary("test")

        assertSame(before, manager.routeSnapshot.value)
    }

    // ── RS-10 ───────────────────────────────────────────────────────────────────
    @Test
    fun `both pull entry points read the library and never drive it`() {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()

        manager.onAudioSwitchInvalidated("test")
        manager.reseedFromLibrary("test")

        verify(exactly = 0) { anyConstructed<AudioSwitchHandler>().selectDevice(any()) }
        verify(atLeast = 1) { anyConstructed<AudioSwitchHandler>().availableAudioDevices }
    }

    // ── RS-11 ───────────────────────────────────────────────────────────────────
    /**
     * Selection before list, and — uncontended — exactly one pull: the per-attempt re-read must not
     * degenerate into a per-call double read. Reading in the opposite order to the library's writes is
     * what makes a straddled read yield an older selection with a newer list, never a selection the
     * list has never contained.
     */
    @Test
    fun `an uncontended invalidate pulls exactly once, selection before list`() {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()

        manager.onAudioSwitchInvalidated("test")

        verifyOrder {
            anyConstructed<AudioSwitchHandler>().selectedAudioDevice
            anyConstructed<AudioSwitchHandler>().availableAudioDevices
        }
        verify(exactly = 1) { anyConstructed<AudioSwitchHandler>().selectedAudioDevice }
        verify(exactly = 1) { anyConstructed<AudioSwitchHandler>().availableAudioDevices }
    }

    // ── RS-13 ───────────────────────────────────────────────────────────────────
    @Test
    fun `a genuine rebuild stays empty and is recovered by the next real push`() {
        stubLibrary(emptyList(), null)
        val manager = newManager()
        manager.confirmViaLibrary(listOf(ear, spk), ear)

        manager.onAudioSwitchInvalidated("roomState=DISCONNECTED")
        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())

        manager.onLibraryDevicesChanged(listOf(ear, spk), ear)

        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), manager.routeState)
    }

    // ── RS-22 ───────────────────────────────────────────────────────────────────
    /**
     * The library assumption RS-3 and the guard's own empty-handler rows rest on: off-device, with no
     * `start()` ever called, construction does not throw and both pulled properties answer "nothing".
     * No constructor mock here on purpose — this row is about the real class.
     */
    @Test
    fun `an AudioSwitchHandler with no live switch answers empty and null`() {
        val manager = newManager()

        val handler = manager.audioHandler

        assertTrue(handler.availableAudioDevices.isEmpty())
        assertNull(handler.selectedAudioDevice)
    }

    // ── RS-23 ───────────────────────────────────────────────────────────────────
    /**
     * Site B's race, deterministic: the push commits while the pull is being taken, the CAS observes a
     * non-empty list and drops the pull. No timing dependency — the push is the read's side effect.
     */
    @Test
    fun `a push that lands during a reseed wins over the pull`() {
        mockLibraryConstruction()
        val manager = newManager()
        every { anyConstructed<AudioSwitchHandler>().selectedAudioDevice } returns ear
        every { anyConstructed<AudioSwitchHandler>().availableAudioDevices } answers {
            manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
            listOf(bt)
        }

        manager.reseedFromLibrary("test")

        assertEquals(listOf(AudioDeviceKind.EARPIECE, AudioDeviceKind.SPEAKERPHONE), manager.kinds())
    }

    // ── RS-24 ───────────────────────────────────────────────────────────────────
    /**
     * The RACE-1 regression pin. A push ADDS a device inside the invalidate's window; the pull that was
     * taken before that push must never be published over it. The two list reads are the behavioural
     * proxy for `attempts=2` (log content is never asserted): the second read is the re-read that
     * absorbs the pushed device.
     *
     * Against a hoisted single pull this row yields a two-entry list with `bt` permanently absent — and
     * permanently is literal: the list stays non-empty, so the starvation watchdog's `isEmpty()`
     * predicate cannot see the loss, and the library re-pushes only on a further physical change.
     */
    @Test
    fun `a device delivered by a push mid-invalidate is never overwritten by the staler pull`() {
        mockLibraryConstruction()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(ear, spk), ear)
        stubPushRacingFirstRead(manager)

        manager.onAudioSwitchInvalidated("roomState=CONNECTING")

        assertEquals(
            listOf(
                AudioDeviceKind.EARPIECE,
                AudioDeviceKind.SPEAKERPHONE,
                AudioDeviceKind.BLUETOOTH_HEADSET,
            ),
            manager.kinds(),
        )
        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
        verify(exactly = 2) { anyConstructed<AudioSwitchHandler>().availableAudioDevices }
    }

    // ── RS-24c ──────────────────────────────────────────────────────────────────
    /**
     * The tolerated residual, pinned so nobody "fixes" it into a regression. Two commits inside one
     * attempt whose NET content equals the sampled snapshot make the compare-and-set succeed on the
     * first try, and a pull taken at the instant the extra device existed publishes it as a phantom.
     *
     * Tolerated because the failure direction is visible and finite, where the race it replaces was a
     * silent omission. The bound is scoped to the ROUTING-ATTEMPT lifecycle, and only because the
     * phantom was also the pulled selection: R6 arms it, the applier's own budget ends that attempt as
     * `Failed`, and `settledRoute` then skips the failed kind. A phantom row that nothing selected has
     * no such bound — it is an extra row in the panel until the library's next push, which is the
     * accepted cost. Closing the window would need an atomic read of (device set, selection, snapshot)
     * that neither the library nor `StateFlow` offers.
     */
    @Test
    fun `the tolerated ABA residual publishes a visible phantom, never a silent omission`() {
        mockLibraryConstruction()
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
        var reads = 0
        every { anyConstructed<AudioSwitchHandler>().selectedAudioDevice } returns bt
        every { anyConstructed<AudioSwitchHandler>().availableAudioDevices } answers {
            if (reads++ == 0) {
                manager.onLibraryDevicesChanged(listOf(ear, spk, bt), bt)
                manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
            }
            listOf(ear, spk, bt)
        }

        manager.onAudioSwitchInvalidated("roomState=CONNECTING")

        verify(exactly = 1) { anyConstructed<AudioSwitchHandler>().availableAudioDevices }
        assertEquals(3, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)

        // The bounded half: the attempt the phantom armed ends as the applier's own budget expires,
        // after which the depicted route is the earpiece again.
        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)

        assertEquals(AudioDeviceKind.EARPIECE, manager.routeSnapshot.value.settledRoute?.kind)
    }
}

/**
 * The ring anchor is the device the horn depicts, exhaustively — so "one tap always moves audio
 * somewhere else" is arithmetic rather than luck (rows RT-25a, RT-25b).
 *
 * Both halves run the `x = bt` column, i.e. an in-flight target the two-device list does not hold:
 * that is the family where an un-narrowed anchor lands `indexOfFirst` on -1 and the ring restarts at
 * `devices[0]` — in a 1v1 the earpiece the call is already using.
 *
 * A fifth top-level class in this file because detekt's `LargeClass` gate is measured per class.
 */
class AudioRouteAnchorInvariantTest {

    private val ear = earDevice()
    private val spk = spkDevice()
    private val bt = btDevice()
    private val devices = listOf(ear, spk)

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ── RT-25a ──────────────────────────────────────────────────────────────────
    @Test
    fun `the anchor equals the depicted kind and is always in the ring`() {
        val targets = listOf(ear, spk, bt)
        val requestedValues = listOf(null, spk)
        var rows = 0

        for (x in targets) {
            // `confirmed` is x only while x is enumerable: R2 clears a confirmed device the list no
            // longer holds, so the non-member shape cannot rest.
            val confirmedForConfirmedState = x.takeIf { d -> devices.any { it.kind == d.kind } }
            val states = listOf<Pair<AudioRouteState, AudioDevice?>>(
                AudioRouteState.Idle to null,
                AudioRouteState.Applying(x, RouteOrigin.USER) to null,
                AudioRouteState.Applying(x, RouteOrigin.LIBRARY) to null,
                AudioRouteState.Confirmed(x) to confirmedForConfirmedState,
                AudioRouteState.Failed(x, AudioRouteFailure.TIMEOUT) to null,
            )
            for ((state, confirmed) in states) {
                for (requested in requestedValues) {
                    val snapshot = AudioRouteSnapshot(
                        availableDevices = devices,
                        requested = requested,
                        confirmed = confirmed,
                        state = state,
                    )
                    val label = "state=$state confirmed=${confirmed?.kind} requested=${requested?.kind}"
                    val isToggle = !shouldShowAudioDevicePanel(snapshot.toDeviceRows())
                    assertTrue(label, isToggle)

                    val anchor = snapshot.userPendingRoute ?: snapshot.settledRoute
                    assertNotNull(label, anchor)
                    val presentation = snapshot.hornPresentation(isOneVOneCall = true, isToggle = isToggle)
                    assertEquals(label, anchor?.kind, presentation.kind)
                    assertTrue(label, devices.any { it.kind == anchor?.kind })
                    rows++
                }
            }
        }

        assertEquals(30, rows)
    }

    // ── RT-25b ──────────────────────────────────────────────────────────────────
    @Test
    fun `a tap never selects the kind the horn is already depicting`() {
        // Each recipe drives a real manager to one resting shape of the RT-25a table. `Applying(bt)`
        // shapes go through select-on-empty-list (T3) plus the push R1 preserves the attempt across;
        // `Confirmed(bt)` normalises to Idle via R2, which is the resting shape it has.
        val recipes = listOf<Pair<String, AudioDeviceManager.() -> Unit>>(
            "Idle" to { seedDevices(ear, spk) },
            "Idle with a stale requested" to {
                seedDevices(ear, spk)
                select(spk)
                onRouteConfirmed(spk)
                onLibraryDevicesChanged(devices, null)
            },
            "Applying(ear, USER)" to {
                seedDevices(ear, spk)
                select(ear)
            },
            "Applying(spk, USER)" to {
                seedDevices(ear, spk)
                select(spk)
            },
            "Applying(bt, USER)" to {
                select(bt)
                onLibraryDevicesChanged(devices, null)
            },
            "Applying(ear, LIBRARY)" to { onLibraryDevicesChanged(devices, ear) },
            "Applying(spk, LIBRARY)" to { onLibraryDevicesChanged(devices, spk) },
            "Applying(ear, LIBRARY) with requested=spk" to {
                seedDevices(ear, spk)
                select(spk)
                onRouteConfirmed(spk)
                onLibraryDevicesChanged(devices, ear)
            },
            "Applying(bt, LIBRARY)" to {
                onLibraryDevicesChanged(listOf(bt, ear, spk), bt)
                onLibraryDevicesChanged(devices, null)
            },
            "Confirmed(ear)" to { confirmViaLibrary(devices, ear) },
            "Confirmed(spk)" to { confirmViaLibrary(devices, spk) },
            "Confirmed(bt) normalised by R2" to {
                confirmViaLibrary(listOf(bt, ear, spk), bt)
                onLibraryDevicesChanged(devices, null)
            },
            "Failed(ear, TIMEOUT)" to {
                seedDevices(ear, spk)
                select(ear)
                onRouteFailed(ear, AudioRouteFailure.TIMEOUT)
            },
            "Failed(spk, TIMEOUT)" to {
                seedDevices(ear, spk)
                select(spk)
                onRouteFailed(spk, AudioRouteFailure.TIMEOUT)
            },
            "Failed(bt, TIMEOUT)" to {
                select(bt)
                onLibraryDevicesChanged(devices, null)
                onRouteFailed(bt, AudioRouteFailure.TIMEOUT)
            },
        )

        for ((label, drive) in recipes) {
            val manager = newManager()
            manager.drive()
            val snapshot = manager.routeSnapshot.value
            val isToggle = !shouldShowAudioDevicePanel(snapshot.toDeviceRows())
            assertTrue(label, isToggle)
            val depicted = snapshot.hornPresentation(isOneVOneCall = true, isToggle = isToggle).kind

            manager.switchToNext()

            val applying = manager.routeState as? AudioRouteState.Applying
            assertNotNull(label, applying)
            assertNotEquals(label, depicted, applying?.device?.kind)
            assertEquals(label, RouteOrigin.USER, applying?.origin)
            // The tap lands on a device the ring actually holds.
            assertTrue(label, devices.any { it.kind == applying?.device?.kind })
        }
    }
}
