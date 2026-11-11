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



private fun newManager(callType: String = CallType.ONE_ON_ONE.type) =
    AudioDeviceManager(mockk<Context>(relaxed = true), callType, mockk<UserManager>(relaxed = true))


private fun AudioDeviceManager.seedDevices(vararg devices: AudioDevice) =
    onLibraryDevicesChanged(devices.toList(), null)


private fun AudioDeviceManager.confirmViaLibrary(devices: List<AudioDevice>, target: AudioDevice) {
    onLibraryDevicesChanged(devices, target)
    onRouteConfirmed(target)
}






@OptIn(ExperimentalCoroutinesApi::class)
class AudioDeviceManagerRouteTest {

    


    private fun AudioDeviceManager.audioHandlerInitialized(): Boolean {
        val field = AudioDeviceManager::class.java.getDeclaredField("audioHandler\$delegate")
        field.isAccessible = true
        return (field.get(this) as Lazy<*>).isInitialized()
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

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

    @Test
    fun `R1 covers every callback in the window, not only the null one`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), spk)
        manager.select(bt)

        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)

        
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.confirmed?.kind)
        assertEquals(AudioRouteState.Applying(bt), manager.routeState)
    }

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

    @Test
    fun `R4 keeps the check mark when only the bluetooth name changed`() = runTest(UnconfinedTestDispatcher()) {
        val btA = btDevice("Bluetooth")
        val spk = spkDevice()
        val renamed = btDevice("Alice's AirPods")
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

    @Test
    fun `R6 turns the library's own pick into an attempt that must be verified`() {
        val bt = btDevice()
        val manager = newManager()

        manager.onLibraryDevicesChanged(listOf(bt, spkDevice()), bt)

        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
        assertNull(manager.confirmed)
        assertNull("the library's pick is not user intent", manager.requested)
    }

    
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

    @Test
    fun `R1a does not fire when the library re-picks the same kind`() = runTest(UnconfinedTestDispatcher()) {
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()

        manager.pendingRoute.test {
            manager.onLibraryDevicesChanged(listOf(ear), ear)
            assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), awaitItem())

            
            manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
            expectNoEvents()
        }
        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), manager.routeState)
    }

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

    



    @Test
    fun `R1a does not supersede a LIBRARY attempt that matches the requested kind`() {
        val bt = btDevice()
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.confirmViaLibrary(listOf(bt, spk), bt)
        manager.select(bt)
        manager.onAudioSwitchInvalidated("test")
        
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), bt)
        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)

        
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), ear)

        assertEquals(AudioRouteState.Applying(bt, RouteOrigin.LIBRARY), manager.routeState)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    



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

    



    @Test
    fun `switchToNext with nothing confirmed advances away from the library's own pick`() {
        val bt = btDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(bt, spk)

        manager.switchToNext()

        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
    }

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

    @Test
    fun `select refuses a device the library does not enumerate`() {
        val manager = newManager()
        manager.seedDevices(spkDevice())
        val before = manager.routeSnapshot.value

        manager.select(btDevice())

        assertEquals(before, manager.routeSnapshot.value)
    }

    @Test
    fun `select is accepted while the device list is still empty`() {
        val bt = btDevice()
        val manager = newManager()

        manager.select(bt)

        assertEquals(AudioRouteState.Applying(bt), manager.routeState)
        assertSame(bt, manager.requested)
    }

    @Test
    fun `the initial snapshot never comes from the library`() {
        val manager = newManager()

        assertEquals(AudioRouteSnapshot(), manager.routeSnapshot.value)
        assertFalse(
            "construction must not read the library's selectedAudioDevice",
            manager.audioHandlerInitialized(),
        )
    }

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

    @Test
    fun `kind maps every AudioDevice subclass`() {
        assertEquals(AudioDeviceKind.EARPIECE, earDevice().kind)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, spkDevice().kind)
        assertEquals(AudioDeviceKind.WIRED_HEADSET, wiredDevice().kind)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, btDevice().kind)
    }

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


class AudioRouteSnapshotResolutionTest {

    @After
    fun tearDown() {
        clearAllMocks()
    }

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

    @Test
    fun `settledRoute is null when nothing is observed and nothing is enumerated`() {
        assertNull(AudioRouteSnapshot().settledRoute)
    }

    @Test
    fun `userPendingRoute reports an enumerable non-bluetooth USER attempt`() {
        val spk = spkDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spk),
            state = AudioRouteState.Applying(spk, RouteOrigin.USER),
        )

        assertSame(spk, snapshot.userPendingRoute)
    }

    @Test
    fun `userPendingRoute ignores a LIBRARY attempt because no gesture is outstanding`() {
        val spk = spkDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spk),
            state = AudioRouteState.Applying(spk, RouteOrigin.LIBRARY),
        )

        assertNull(snapshot.userPendingRoute)
    }

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

    



    @Test
    fun `userPendingRoute rejects a USER attempt the device list does not contain`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spkDevice()),
            state = AudioRouteState.Applying(btDevice(), RouteOrigin.USER),
        )

        assertNull(snapshot.userPendingRoute)
    }

    
    @Test
    fun `userPendingRoute rejects a non-enumerable wired attempt too`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(earDevice(), spkDevice()),
            state = AudioRouteState.Applying(wiredDevice(), RouteOrigin.USER),
        )

        assertNull(snapshot.userPendingRoute)
    }

    


    @Test
    fun `userPendingRoute rejects an enumerable bluetooth attempt`() {
        val bt = btDevice()
        val snapshot = AudioRouteSnapshot(
            availableDevices = listOf(bt, spkDevice()),
            state = AudioRouteState.Applying(bt, RouteOrigin.USER),
        )

        assertNull(snapshot.userPendingRoute)
    }

    
    @Test
    fun `userPendingRoute is null while the device list is still empty`() {
        val snapshot = AudioRouteSnapshot(
            availableDevices = emptyList(),
            state = AudioRouteState.Applying(spkDevice(), RouteOrigin.USER),
        )

        assertNull(snapshot.userPendingRoute)
    }

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





class AudioDeviceManagerSwitchRingTest {

    @After
    fun tearDown() {
        clearAllMocks()
    }

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

    @Test
    fun `a tap during the library's own attempt advances from the library pick`() {
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        
        manager.onLibraryDevicesChanged(listOf(ear, spk), ear)

        manager.switchToNext()

        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
    }

    @Test
    fun `a tap after a failed attempt retries it instead of alternating back`() {
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        manager.seedDevices(ear, spk)
        manager.select(spk)
        manager.onRouteFailed(spk, AudioRouteFailure.TIMEOUT)

        manager.switchToNext()

        
        
        
        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
    }

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

    @Test
    fun `a tap anchored on a pending target the list no longer holds still advances`() {
        val bt = btDevice()
        val ear = earDevice()
        val spk = spkDevice()
        val manager = newManager()
        
        manager.select(bt)
        manager.onLibraryDevicesChanged(listOf(ear, spk), null)
        assertEquals(AudioRouteState.Applying(bt), manager.routeState)

        manager.switchToNext()

        
        
        
        assertEquals(AudioRouteState.Applying(spk), manager.routeState)
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
    }
}







@OptIn(ExperimentalCoroutinesApi::class)
class AudioDeviceManagerReseedTest {

    private val ear = earDevice()
    private val spk = spkDevice()
    private val bt = btDevice()

    
    private var pulledDevices: List<AudioDevice> = emptyList()
    private var pulledSelection: AudioDevice? = null

    @After
    fun tearDown() {
        unmockkConstructor(AudioSwitchHandler::class)
        clearAllMocks()
    }

    
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

    



    @Test
    fun `the applied flag's reducer-identity premise holds on every return shape`() {
        val pulled = listOf(ear, spk)
        stubLibrary(pulled, ear)

        
        val r6 = newManager()
        r6.reseedFromLibrary("a")
        assertSame(pulled, r6.routeSnapshot.value.availableDevices)
        assertEquals(AudioRouteState.Applying(ear, RouteOrigin.LIBRARY), r6.routeState)

        
        val dropped = listOf(bt)
        pulledDevices = dropped
        pulledSelection = bt
        val kept = newManager()
        kept.seedDevices(ear, spk)
        kept.reseedFromLibrary("b")
        assertNotSame(dropped, kept.routeSnapshot.value.availableDevices)

        
        pulledDevices = pulled
        pulledSelection = ear
        val r1 = newManager()
        r1.select(spk)
        r1.reseedFromLibrary("c")
        assertSame(pulled, r1.routeSnapshot.value.availableDevices)
        assertEquals(AudioRouteState.Applying(spk, RouteOrigin.USER), r1.routeState)
    }

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

    
    @Test
    fun `an empty pull is a no-op, never a wipe of a pending attempt`() {
        stubLibrary(emptyList(), null)
        val manager = newManager()
        manager.select(spk)
        val before = manager.routeSnapshot.value

        manager.reseedFromLibrary("test")

        assertSame(before, manager.routeSnapshot.value)
    }

    @Test
    fun `both pull entry points read the library and never drive it`() {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()

        manager.onAudioSwitchInvalidated("test")
        manager.reseedFromLibrary("test")

        verify(exactly = 0) { anyConstructed<AudioSwitchHandler>().selectDevice(any()) }
        verify(atLeast = 1) { anyConstructed<AudioSwitchHandler>().availableAudioDevices }
    }

    



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

    



    @Test
    fun `an AudioSwitchHandler with no live switch answers empty and null`() {
        val manager = newManager()

        val handler = manager.audioHandler

        assertTrue(handler.availableAudioDevices.isEmpty())
        assertNull(handler.selectedAudioDevice)
    }

    



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

        
        
        manager.onRouteFailed(bt, AudioRouteFailure.TIMEOUT)

        assertEquals(AudioDeviceKind.EARPIECE, manager.routeSnapshot.value.settledRoute?.kind)
    }
}





class AudioRouteAnchorInvariantTest {

    private val ear = earDevice()
    private val spk = spkDevice()
    private val bt = btDevice()
    private val devices = listOf(ear, spk)

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `the anchor equals the depicted kind and is always in the ring`() {
        val targets = listOf(ear, spk, bt)
        val requestedValues = listOf(null, spk)
        var rows = 0

        for (x in targets) {
            
            
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

    @Test
    fun `a tap never selects the kind the horn is already depicting`() {
        
        
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
            
            assertTrue(label, devices.any { it.kind == applying?.device?.kind })
        }
    }
}
