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

    @Test
    fun `failure outranks a stale confirmation`() {
        val bt = btDevice()
        
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(bt, spkDevice()),
            confirmed = bt,
            state = AudioRouteState.Failed(bt, AudioRouteFailure.ERROR),
        ).toDeviceRows()

        assertEquals(AudioRouteRowStatus.FAILED, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
    }

    @Test
    fun `a renamed bluetooth device is still the active row`() {
        val confirmedBt = btDevice("Bluetooth")
        val listedBt = btDevice("Alice's AirPods")
        val rows = AudioRouteSnapshot(
            availableDevices = listOf(listedBt, spkDevice()),
            confirmed = confirmedBt,
            state = AudioRouteState.Confirmed(confirmedBt),
        ).toDeviceRows()

        assertEquals(AudioRouteRowStatus.ACTIVE, row(rows, AudioDeviceKind.BLUETOOTH_HEADSET).status)
    }

    @Test
    fun `an empty device list renders no ghost rows`() {
        val rows = AudioRouteSnapshot(state = AudioRouteState.Applying(btDevice())).toDeviceRows()

        assertTrue(rows.isEmpty())
    }

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

    @Test
    fun `the horn skips the device that just failed`() {
        val bt = btDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(bt, earDevice(), spkDevice()),
            state = AudioRouteState.Failed(bt, AudioRouteFailure.TIMEOUT),
        )

        assertEquals(AudioDeviceKind.EARPIECE, snapshot.hornIconKind(isOneVOneCall = true))
    }

    @Test
    fun `with nothing observed the horn follows the library's priority order`() {
        val snapshot = AudioRouteSnapshot(availableDevices = listOf(earDevice(), spkDevice()))

        assertEquals(AudioDeviceKind.EARPIECE, snapshot.hornIconKind(isOneVOneCall = true))
    }

    @Test
    fun `with nothing enumerated a one-to-one call mirrors its earpiece preference`() {
        assertEquals(AudioDeviceKind.EARPIECE, AudioRouteSnapshot().hornIconKind(isOneVOneCall = true))
    }

    @Test
    fun `with nothing enumerated a meeting mirrors its speaker preference`() {
        assertEquals(AudioDeviceKind.SPEAKERPHONE, AudioRouteSnapshot().hornIconKind(isOneVOneCall = false))
    }

    @Test
    fun `two devices including bluetooth need the panel`() {
        assertTrue(
            shouldShowAudioDevicePanel(
                rowsOf(AudioDeviceKind.SPEAKERPHONE, AudioDeviceKind.BLUETOOTH_HEADSET),
            ),
        )
    }

    @Test
    fun `two non-bluetooth devices keep the one-tap toggle`() {
        assertFalse(
            shouldShowAudioDevicePanel(
                rowsOf(AudioDeviceKind.EARPIECE, AudioDeviceKind.SPEAKERPHONE),
            ),
        )
    }

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

    @Test
    fun `a single device needs the panel so the tap is not dead`() {
        assertTrue(shouldShowAudioDevicePanel(rowsOf(AudioDeviceKind.SPEAKERPHONE)))
    }

    @Test
    fun `an empty row list is defined as panel, and the caller short-circuits first`() {
        assertTrue(shouldShowAudioDevicePanel(emptyList()))
    }

    @Test
    fun `labelRes covers every status`() {
        assertNull(AudioRouteRowStatus.NONE.labelRes())
        assertEquals(R.string.call_audio_device_status_active, AudioRouteRowStatus.ACTIVE.labelRes())
        assertEquals(R.string.call_audio_device_status_connecting, AudioRouteRowStatus.CONNECTING.labelRes())
        assertEquals(R.string.call_audio_device_status_failed, AudioRouteRowStatus.FAILED.labelRes())
    }

    @Test
    fun `every kind maps to a distinct picker icon and its horn glyph`() {
        
        
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
                R.drawable.call_ic_volume_speaker,
                R.drawable.call_ic_volume_speaker,
                R.drawable.call_ic_volume_headphones,
                R.drawable.call_ic_volume_airpod,
            ),
            horn,
        )
    }
}






class HornPresentationTest {

    private val ear = earDevice()
    private val spk = spkDevice()
    private val bt = btDevice()
    private val wired = wiredDevice()

    
    private fun AudioRouteSnapshot.derivedIsToggle(): Boolean =
        !shouldShowAudioDevicePanel(toDeviceRows())

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

    @Test
    fun `an open toggle gate with a bluetooth target still draws the settled route`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            confirmed = ear,
            state = AudioRouteState.Applying(bt, RouteOrigin.USER),
        )
        val isToggle = snapshot.derivedIsToggle()

        
        assertTrue(isToggle)
        val presentation = snapshot.hornPresentation(isOneVOneCall = true, isToggle = isToggle)
        assertEquals(HornPresentation(AudioDeviceKind.EARPIECE, pending = false), presentation)
        assertNotEquals(AudioDeviceKind.BLUETOOTH_HEADSET, presentation.kind)
    }

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

    @Test
    fun `a forced toggle gate never dims to a bluetooth icon`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(bt, spk),
            confirmed = spk,
            state = AudioRouteState.Applying(bt, RouteOrigin.USER),
        )

        
        assertEquals(
            HornPresentation(AudioDeviceKind.SPEAKERPHONE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = true),
        )
    }

    @Test
    fun `the panel path gets no pending visual even for a toggle-eligible target`() {
        
        
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

    @Test
    fun `starvation is not masked by the optimistic layer`() {
        val snapshot = AudioRouteSnapshot(state = AudioRouteState.Applying(spk, RouteOrigin.USER))

        assertTrue(shouldShowAudioDevicePanel(snapshot.toDeviceRows()))
        assertEquals(
            HornPresentation(AudioDeviceKind.EARPIECE, pending = false),
            snapshot.hornPresentation(isOneVOneCall = true, isToggle = false),
        )
    }

    @Test
    fun `hornIconKind ignores an in-flight attempt`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(ear, spk),
            confirmed = ear,
            state = AudioRouteState.Applying(spk, RouteOrigin.USER),
        )

        assertEquals(AudioDeviceKind.EARPIECE, snapshot.hornIconKind(isOneVOneCall = true))
    }

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
