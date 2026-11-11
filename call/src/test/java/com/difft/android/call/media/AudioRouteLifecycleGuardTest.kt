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





@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteLifecycleGuardTest {

    private val context = mockk<Context>(relaxed = true)
    private val userManager = mockk<UserManager>(relaxed = true)

    private val bt = btDevice()
    private val spk = spkDevice()
    private val ear = earDevice()

    
    private var pulledDevices: List<AudioDevice> = emptyList()
    private var pulledSelection: AudioDevice? = null

    @After
    fun tearDown() {
        unmockkConstructor(AudioSwitchHandler::class)
        clearAllMocks()
    }

    private fun newManager() = AudioDeviceManager(context, CallType.ONE_ON_ONE.type, userManager)

    



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

    
    private fun TestScope.recordConfirmedKinds(manager: AudioDeviceManager): List<AudioDeviceKind?> {
        val seen = mutableListOf<AudioDeviceKind?>()
        backgroundScope.launch { manager.routeSnapshot.collect { seen += it.confirmed?.kind } }
        runCurrent()
        return seen
    }

    private fun applyingKind(manager: AudioDeviceManager): AudioDeviceKind? =
        (manager.routeState as? AudioRouteState.Applying)?.device?.kind

    @Test
    fun `an ordinary reconnect touches nothing`() = runTest {
        
        
        
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
        
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), bt)
        runCurrent()

        assertEquals(AudioDeviceKind.EARPIECE, applyingKind(manager))
        assertEquals(AudioDeviceKind.EARPIECE, manager.requested?.kind)
    }

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

        
        
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
        assertNull(manager.requested)
    }

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

        
        assertEquals(listOf(AudioDeviceKind.BLUETOOTH_HEADSET), pending)
    }

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
        
        manager.onLibraryDevicesChanged(listOf(spk, ear), spk)
        runCurrent()

        assertEquals(AudioDeviceKind.SPEAKERPHONE, applyingKind(manager))
        assertEquals("intent is kept for the next generation", AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    @Test
    fun `a CONNECTING seen without DISCONNECTED still invalidates`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        manager.onRouteConfirmed(bt)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        
        
        
        roomState.value = Room.State.CONNECTING
        runCurrent()

        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
        assertEquals(AudioRouteState.Idle, manager.routeState)
    }

    @Test
    fun `a late CONNECTING does not wipe a list the new generation already reported`() = runTest {
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        manager.onRouteConfirmed(bt)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        
        manager.onLibraryDevicesChanged(listOf(bt, spk), bt)
        runCurrent()
        roomState.value = Room.State.CONNECTING
        runCurrent()

        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
    }

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

        
        manager.onLibraryDevicesChanged(listOf(bt, spk, ear), ear)
        runCurrent()

        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, applyingKind(manager))
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    @Test
    fun `a switch that never reports devices is flagged as starved`() = runTest {
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)

        advanceTimeBy(3_001)

        
        assertTrue(guard.switchStarved.value)
    }

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

        
        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.confirmed?.kind)
        assertFalse("a stale warning would misreport the next call", guard.switchStarved.value)
    }

    @Test
    fun `Room State is exhaustively handled`() = runTest {
        
        
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

    
    @Test
    fun `the starvation watchdog heals a call the library will never push to`() = runTest {
        stubLibrary(emptyList(), null)
        val manager = newManager()
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        val guard = startGuard(manager, roomState)

        advanceTimeBy(2_000)
        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
        
        pulledDevices = listOf(ear, spk)
        pulledSelection = ear
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
        assertEquals(AudioDeviceKind.EARPIECE, applyingKind(manager))
        assertFalse(guard.switchStarved.value)
    }

    



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

    



    @Test
    fun `a heal does not restart the attempt the ready-edge replay already owns`() = runTest {
        stubLibrary(listOf(ear, spk), ear)
        val manager = newManager()
        
        manager.select(spk)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)
        val pending = recordPendingRoutes(manager)

        advanceTimeBy(3_001)

        assertEquals(listOf(AudioDeviceKind.SPEAKERPHONE), pending)
        assertEquals(2, manager.routeSnapshot.value.availableDevices.size)
    }

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
