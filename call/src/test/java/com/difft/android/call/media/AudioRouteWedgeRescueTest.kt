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






@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteWedgeRescueTest {

    private val context = mockk<Context>(relaxed = true)
    private val userManager = mockk<UserManager>(relaxed = true)

    private val bt = btDevice()
    private val spk = spkDevice()
    private val ear = earDevice()

    
    private val handler = mockk<AudioSwitchHandler>(relaxed = true)

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
        every { anyConstructed<AudioSwitchHandler>().start() } just Runs
        every { anyConstructed<AudioSwitchHandler>().stop() } just Runs
    }

    




    private fun mockedManager(snapshots: MutableStateFlow<AudioRouteSnapshot>) =
        mockk<AudioDeviceManager>(relaxed = true) {
            every { routeSnapshot } returns snapshots
            every { availableDevices } returns snapshots.map { it.availableDevices }.distinctUntilChanged()
            every { audioHandler } returns handler
        }

    
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

    


    private fun TestScope.settleRebuild() {
        advanceTimeBy(300)
        runCurrent()
    }

    private fun applyingKind(manager: AudioDeviceManager): AudioDeviceKind? =
        (manager.routeState as? AudioRouteState.Applying)?.device?.kind

    
    @Test
    fun `two consecutive timeouts on one kind rebuild the switch exactly once`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        settleRebuild()
        
        
        verify(exactly = 0) { handler.stop() }

        driveAttempt(snapshots, spk)
        settleRebuild()

        
        verifyOrder {
            manager.onAudioSwitchInvalidated("wedgeRescue", false)
            handler.stop()
            handler.start()
        }
        verify(exactly = 1) { manager.onAudioSwitchInvalidated("wedgeRescue", false) }
        verify(exactly = 1) { handler.stop() }
        verify(exactly = 1) { handler.start() }

        
        
        driveAttempt(snapshots, spk)
        settleRebuild()
        verify(exactly = 1) { handler.stop() }
    }

    
    @Test
    fun `a confirmed route between two timeouts ends the episode`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        
        
        snapshots.value = snapshots.value.copy(state = AudioRouteState.Confirmed(spk))
        runCurrent()
        driveAttempt(snapshots, spk)
        settleRebuild()

        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    
    





    @Test
    fun `a cross-kind confirm between two timeouts ends the episode`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        
        snapshots.value = snapshots.value.copy(
            state = AudioRouteState.Applying(spk),
            confirmed = ear,
            confirmations = snapshots.value.confirmations + 1,
        )
        runCurrent()
        driveAttempt(snapshots, spk)
        settleRebuild()

        
        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    
    





    @Test
    fun `a confirm conflated into the second failure frame still ends the episode`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        
        
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

    
    @Test
    fun `timeouts on two different kinds are two episodes, not a streak`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        driveAttempt(snapshots, ear)
        settleRebuild()

        
        
        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    
    @Test
    fun `a device that keeps going away never rescues`() = runTest {
        val snapshots = snapshotFlow(listOf(spk, ear, bt))
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        repeat(3) { driveAttempt(snapshots, bt, AudioRouteFailure.DEVICE_GONE) }
        settleRebuild()

        
        
        
        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }
    }

    
    @Test
    fun `the rescue budget is spent after two rebuilds`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        
        repeat(3) {
            driveAttempt(snapshots, spk)
            driveAttempt(snapshots, spk)
            settleRebuild()
        }

        
        verify(exactly = 2) { manager.onAudioSwitchInvalidated("wedgeRescue", false) }
        verify(exactly = 2) { handler.stop() }
        verify(exactly = 2) { handler.start() }
    }

    
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

        
        
        
        verify(exactly = 0) { manager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 0) { handler.stop() }

        
        
        roomState.value = Room.State.CONNECTED
        runCurrent()
        driveAttempt(snapshots, spk)
        settleRebuild()
        verify(exactly = 0) { handler.stop() }
    }

    
    



    @Test
    fun `a disconnect during the settle delay aborts the rebuild`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        val roomState = MutableStateFlow(Room.State.CONNECTED)
        startGuard(manager, roomState)

        driveAttempt(snapshots, spk)
        driveAttempt(snapshots, spk)

        
        verify(exactly = 1) { handler.stop() }
        verify(exactly = 0) { handler.start() }

        advanceTimeBy(100)
        roomState.value = Room.State.DISCONNECTED
        runCurrent()
        settleRebuild()

        
        verify(exactly = 0) { handler.start() }
        verify(exactly = 1) { handler.stop() }
    }

    
    





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

        
        verify(exactly = 1) { handler.stop() }
        verify(exactly = 0) { handler.start() }

        roomState.value = Room.State.CONNECTED
        runCurrent()

        verify(exactly = 1) { handler.start() }
    }

    
    



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

    
    





    @Test
    fun `a second timeout still counts when the intervening Applying is conflated away`() = runTest {
        val snapshots = snapshotFlow()
        val manager = mockedManager(snapshots)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        driveAttempt(snapshots, spk)
        
        
        
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

    
    



    @Test
    fun `a failure republished under a changed device list is not a second timeout`() = runTest {
        mockLibraryConstruction()
        val manager = newManager()
        manager.onLibraryDevicesChanged(listOf(spk, ear), null)
        manager.select(spk)
        startGuard(manager, MutableStateFlow(Room.State.CONNECTED))

        manager.onRouteFailed(spk, AudioRouteFailure.TIMEOUT)
        runCurrent()
        
        
        manager.onLibraryDevicesChanged(listOf(spk, ear, bt), spk)
        settleRebuild()

        assertTrue(manager.routeState is AudioRouteState.Failed)
        assertEquals(3, manager.routeSnapshot.value.availableDevices.size)
        verify(exactly = 0) { anyConstructed<AudioSwitchHandler>().stop() }
    }

    
    


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
        
        
        assertTrue(manager.routeSnapshot.value.availableDevices.isEmpty())
        assertEquals("intent outlives the rescue", AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)

        
        manager.onLibraryDevicesChanged(listOf(ear, spk), ear)
        runCurrent()

        
        assertEquals(AudioDeviceKind.SPEAKERPHONE, applyingKind(manager))
        assertEquals(AudioDeviceKind.SPEAKERPHONE, manager.requested?.kind)
    }
}
