package com.difft.android.call.ui

import com.difft.android.call.R
import com.difft.android.call.btDevice
import com.difft.android.call.earDevice
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.manager.AudioRouteSnapshot
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.RouteOrigin
import com.difft.android.call.spkDevice
import com.difft.android.call.wiredDevice
import com.twilio.audioswitch.AudioDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot → row / horn-icon / gating derivations (design inventory group C, rows #56–#76).
 *
 * These are the rules that decide whether the check mark appears at all, which is why they live in
 * pure functions: the project adds no screenshot baselines, so this is the only place the `✓` logic
 * can be pinned cheaply and exhaustively.
 */
class AudioRouteRowsTest {

    private fun row(rows: List<AudioDeviceRow>, kind: AudioDeviceKind) = rows.first { it.kind == kind }

    private fun rowsOf(vararg kinds: AudioDeviceKind): List<AudioDeviceRow> = kinds.map { kind ->
        AudioDeviceRow(device = deviceFor(kind), kind = kind, status = AudioRouteRowStatus.NONE)
    }

    private fun deviceFor(kind: AudioDeviceKind): AudioDevice = when (kind) {
        AudioDeviceKind.EARPIECE -> earDevice()
        AudioDeviceKind.SPEAKERPHONE -> spkDevice()
        AudioDeviceKind.WIRED_HEADSET -> wiredDevice()
        AudioDeviceKind.BLUETOOTH_HEADSET -> btDevice()
    }

    // ── #56 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a confirmed device is the active row`() {
        val bt = btDevice()
        val spk = spkDevice()
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(bt, spk),
            confirmed = bt,
            state = AudioRouteState.Confirmed(bt),
        ).toDeviceRows()

        assertEquals(2, rows.size)
        assertEquals(AudioRouteRowStatus.ACTIVE, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
        assertEquals(AudioRouteRowStatus.NONE, row(rows, AudioDeviceKind.SPEAKERPHONE).status)
    }

    // ── #57 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `an attempt in flight never renders as success`() {
        val bt = btDevice()
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(bt, spkDevice()),
            state = AudioRouteState.Applying(bt),
        ).toDeviceRows()

        assertEquals(AudioRouteRowStatus.CONNECTING, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
        assertTrue(rows.none { it.status == AudioRouteRowStatus.ACTIVE })
    }

    // ── #58 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a failed attempt never renders as success`() {
        val bt = btDevice()
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(bt, spkDevice()),
            state = AudioRouteState.Failed(bt, AudioRouteFailure.TIMEOUT),
        ).toDeviceRows()

        assertEquals(AudioRouteRowStatus.FAILED, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
        assertTrue(rows.none { it.status == AudioRouteRowStatus.ACTIVE })
    }

    // ── #59 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `while switching, the old route keeps the check mark`() {
        val bt = btDevice()
        val spk = spkDevice()
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(bt, spk),
            confirmed = spk,
            state = AudioRouteState.Applying(bt),
        ).toDeviceRows()

        assertEquals(AudioRouteRowStatus.CONNECTING, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
        assertEquals(AudioRouteRowStatus.ACTIVE, row(rows, AudioDeviceKind.SPEAKERPHONE).status)
    }

    // ── #60 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `re-activating the confirmed device shows connecting, not active`() {
        val bt = btDevice()
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(bt, spkDevice()),
            confirmed = bt,
            state = AudioRouteState.Applying(bt),
        ).toDeviceRows()

        assertEquals(AudioRouteRowStatus.CONNECTING, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
    }

    // ── #61 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `failure outranks a stale confirmation`() {
        val bt = btDevice()
        // Unreachable through the manager (T6 clears `confirmed`); pinned as a defensive invariant.
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(bt, spkDevice()),
            confirmed = bt,
            state = AudioRouteState.Failed(bt, AudioRouteFailure.ERROR),
        ).toDeviceRows()

        assertEquals(AudioRouteRowStatus.FAILED, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
    }

    // ── #62 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a renamed bluetooth device is still the active row`() {
        val confirmedBt = btDevice("Bluetooth")
        val listedBt = btDevice("Nate's AirPods")
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(listedBt, spkDevice()),
            confirmed = confirmedBt,
            state = AudioRouteState.Confirmed(confirmedBt),
        ).toDeviceRows()

        assertEquals(AudioRouteRowStatus.ACTIVE, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
    }

    // ── #63 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `an empty device list renders no ghost rows`() {
        val rows = AudioRouteSnapshot(state = AudioRouteState.Applying(btDevice())).toDeviceRows()

        assertTrue(rows.isEmpty())
    }

    // ── #64 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `the horn depicts the confirmed route`() {
        val ear = earDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(btDevice(), ear, spkDevice()),
            confirmed = ear,
            state = AudioRouteState.Confirmed(ear),
        )

        assertEquals(AudioDeviceKind.EARPIECE, snapshot.hornIconKind(isOneVOneCall = true))
    }

    // ── #65 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `the horn does not jump ahead of an in-flight attempt`() {
        val bt = btDevice()
        val spk = spkDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(bt, spk),
            confirmed = spk,
            state = AudioRouteState.Applying(bt),
        )

        assertEquals(AudioDeviceKind.SPEAKERPHONE, snapshot.hornIconKind(isOneVOneCall = true))
    }

    // ── #66 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `the horn skips the device that just failed`() {
        val bt = btDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(bt, earDevice(), spkDevice()),
            state = AudioRouteState.Failed(bt, AudioRouteFailure.TIMEOUT),
        )

        assertEquals(AudioDeviceKind.EARPIECE, snapshot.hornIconKind(isOneVOneCall = true))
    }

    // ── #67 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `with nothing observed the horn follows the library's priority order`() {
        val snapshot = AudioRouteSnapshot(availableDevices = listOf(earDevice(), spkDevice()))

        assertEquals(AudioDeviceKind.EARPIECE, snapshot.hornIconKind(isOneVOneCall = true))
    }

    // ── #68 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `with nothing enumerated a one-to-one call mirrors its earpiece preference`() {
        assertEquals(AudioDeviceKind.EARPIECE, AudioRouteSnapshot().hornIconKind(isOneVOneCall = true))
    }

    // ── #69 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `with nothing enumerated a meeting mirrors its speaker preference`() {
        assertEquals(AudioDeviceKind.SPEAKERPHONE, AudioRouteSnapshot().hornIconKind(isOneVOneCall = false))
    }

    // ── #70 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `two devices including bluetooth need the panel`() {
        assertTrue(
            shouldShowAudioDevicePanel(
                rowsOf(AudioDeviceKind.SPEAKERPHONE, AudioDeviceKind.BLUETOOTH_HEADSET),
            ),
        )
    }

    // ── #71 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `two non-bluetooth devices keep the one-tap toggle`() {
        assertFalse(
            shouldShowAudioDevicePanel(
                rowsOf(AudioDeviceKind.EARPIECE, AudioDeviceKind.SPEAKERPHONE),
            ),
        )
    }

    // ── #72 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `three devices need the panel`() {
        assertTrue(
            shouldShowAudioDevicePanel(
                rowsOf(
                    AudioDeviceKind.BLUETOOTH_HEADSET,
                    AudioDeviceKind.EARPIECE,
                    AudioDeviceKind.SPEAKERPHONE,
                ),
            ),
        )
    }

    // ── #73 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a single device needs the panel so the tap is not dead`() {
        assertTrue(shouldShowAudioDevicePanel(rowsOf(AudioDeviceKind.SPEAKERPHONE)))
    }

    // ── #74 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `an empty row list is defined as panel, and the caller short-circuits first`() {
        assertTrue(shouldShowAudioDevicePanel(emptyList()))
    }

    // ── #75 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `labelRes covers every status`() {
        assertNull(AudioRouteRowStatus.NONE.labelRes())
        assertEquals(R.string.call_audio_device_status_active, AudioRouteRowStatus.ACTIVE.labelRes())
        assertEquals(R.string.call_audio_device_status_connecting, AudioRouteRowStatus.CONNECTING.labelRes())
        assertEquals(R.string.call_audio_device_status_failed, AudioRouteRowStatus.FAILED.labelRes())
    }

    // ── #76 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `every kind maps to a distinct picker and horn icon`() {
        val picker = AudioDeviceKind.entries.map { it.pickerIconRes() }
        val horn = AudioDeviceKind.entries.map { it.hornIconRes() }

        assertEquals(
            listOf(
                R.drawable.tabler_device_phone,
                R.drawable.tabler_device_speaker,
                R.drawable.tabler_device_headphones,
                R.drawable.tabler_device_airpods,
            ),
            picker,
        )
        assertEquals(
            listOf(
                R.drawable.call_btn_volume_phone,
                R.drawable.call_btn_volume_speaker,
                R.drawable.call_btn_volume_headphones,
                R.drawable.call_btn_volume_airpod,
            ),
            horn,
        )
    }
}

/**
 * Horn presentation: what the toggle surface draws, and when it draws it as still-being-driven
 * (rows RT-7..RT-14).
 *
 * The rows that derive `isToggle` from a real `(devices, state)` pair are the load-bearing ones: a
 * two-device `[ear, spk]` list opens the toggle gate legitimately while the in-flight target is
 * Bluetooth or an unplugged headset, and that snapshot family is where a dimmed Bluetooth horn icon
 * (the disagreement PR #1120 removed) would re-enter through the optimistic layer.
 *
 * A second top-level class in this file because detekt's `LargeClass` gate is measured per class.
 */
class HornPresentationTest {

    private val ear = earDevice()
    private val spk = spkDevice()
    private val bt = btDevice()
    private val wired = wiredDevice()

    /** The derivation production uses: the panel formula decides, the presentation obeys. */
    private fun AudioRouteSnapshot.derivedIsToggle(): Boolean =
        !shouldShowAudioDevicePanel(toDeviceRows())

    // ── RT-7 ────────────────────────────────────────────────────────────────────
    @Test
    fun `an in-flight user attempt is depicted as its target, pending`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            confirmed = ear,
            state = AudioRouteState.Applying(spk, RouteOrigin.USER),
        )

        assertEquals(
            HornPresentation(AudioDeviceKind.SPEAKERPHONE, pending = true),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = true),
        )
    }

    // ── RT-8 ────────────────────────────────────────────────────────────────────
    @Test
    fun `a library attempt gets no pending visual`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            state = AudioRouteState.Applying(ear, RouteOrigin.LIBRARY),
        )

        assertEquals(
            HornPresentation(AudioDeviceKind.EARPIECE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = true),
        )
    }

    // ── RT-9 ────────────────────────────────────────────────────────────────────
    @Test
    fun `on the panel path a bluetooth activation gets no optimistic horn`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(bt, spk),
            confirmed = spk,
            state = AudioRouteState.Applying(bt, RouteOrigin.USER),
        )

        assertEquals(
            HornPresentation(AudioDeviceKind.SPEAKERPHONE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = false),
        )
    }

    // ── RT-9a ───────────────────────────────────────────────────────────────────
    @Test
    fun `an open toggle gate with a bluetooth target still draws the settled route`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            confirmed = ear,
            state = AudioRouteState.Applying(bt, RouteOrigin.USER),
        )
        val isToggle = snapshot.derivedIsToggle()

        // Two non-Bluetooth rows: the gate really is open, so the narrowing is what protects the horn.
        assertTrue(isToggle)
        val presentation = snapshot.hornPresentation(isOneVOneCall = true, isToggle = isToggle)
        assertEquals(HornPresentation(AudioDeviceKind.EARPIECE, pending = false), presentation)
        assertNotEquals(AudioDeviceKind.BLUETOOTH_HEADSET, presentation.kind)
    }

    // ── RT-9b ───────────────────────────────────────────────────────────────────
    @Test
    fun `an open toggle gate with an unplugged wired target still draws the settled route`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            state = AudioRouteState.Applying(wired, RouteOrigin.USER),
        )
        val isToggle = snapshot.derivedIsToggle()

        assertTrue(isToggle)
        assertEquals(
            HornPresentation(AudioDeviceKind.EARPIECE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = isToggle),
        )
    }

    // ── RT-9c ───────────────────────────────────────────────────────────────────
    @Test
    fun `a forced toggle gate never dims to a bluetooth icon`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(bt, spk),
            confirmed = spk,
            state = AudioRouteState.Applying(bt, RouteOrigin.USER),
        )

        // isToggle is forced open: the horn must hold even if the panel formula is later loosened.
        assertEquals(
            HornPresentation(AudioDeviceKind.SPEAKERPHONE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = true),
        )
    }

    // ── RT-9d ───────────────────────────────────────────────────────────────────
    @Test
    fun `the panel path gets no pending visual even for a toggle-eligible target`() {
        // Three non-Bluetooth devices: the panel path with a userPendingRoute that IS toggle-eligible
        // — the only shape that can detect the isToggle gate being dropped as "redundant".
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk, wired),
            state = AudioRouteState.Applying(spk, RouteOrigin.USER),
        )
        val isToggle = snapshot.derivedIsToggle()

        assertFalse(isToggle)
        assertEquals(
            HornPresentation(AudioDeviceKind.EARPIECE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = isToggle),
        )
    }

    // ── RT-10 ───────────────────────────────────────────────────────────────────
    @Test
    fun `confirmation turns the pending icon solid without changing its kind`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            confirmed = spk,
            state = AudioRouteState.Confirmed(spk),
        )

        assertEquals(
            HornPresentation(AudioDeviceKind.SPEAKERPHONE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = true),
        )
    }

    // ── RT-11 ───────────────────────────────────────────────────────────────────
    @Test
    fun `a failed attempt reverts to the route audio is actually on`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            requested = spk,
            state = AudioRouteState.Failed(spk, AudioRouteFailure.TIMEOUT),
        )

        assertEquals(
            HornPresentation(AudioDeviceKind.EARPIECE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = true),
        )
    }

    // ── RT-12 ───────────────────────────────────────────────────────────────────
    @Test
    fun `starvation is not masked by the optimistic layer`() {
        val snapshot = AudioRouteSnapshot(state = AudioRouteState.Applying(spk, RouteOrigin.USER))

        assertTrue(shouldShowAudioDevicePanel(snapshot.toDeviceRows()))
        assertEquals(
            HornPresentation(AudioDeviceKind.EARPIECE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = false),
        )
    }

    // ── RT-13 ───────────────────────────────────────────────────────────────────
    @Test
    fun `hornIconKind ignores an in-flight attempt`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            confirmed = ear,
            state = AudioRouteState.Applying(spk, RouteOrigin.USER),
        )

        assertEquals(AudioDeviceKind.EARPIECE, snapshot.hornIconKind(isOneVOneCall = true))
    }

    // ── RT-14 ───────────────────────────────────────────────────────────────────
    @Test
    fun `panel row statuses are unchanged by the optimistic layer`() {
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(bt, spk),
            confirmed = spk,
            state = AudioRouteState.Applying(bt, RouteOrigin.USER),
        ).toDeviceRows()

        assertEquals(2, rows.size)
        assertEquals(
            AudioRouteRowStatus.CONNECTING,
            rows.first { it.kind == AudioDeviceKind.BLUETOOTH_HEADSET }.status,
        )
        assertEquals(
            AudioRouteRowStatus.ACTIVE,
            rows.first { it.kind == AudioDeviceKind.SPEAKERPHONE }.status,
        )
    }
}
