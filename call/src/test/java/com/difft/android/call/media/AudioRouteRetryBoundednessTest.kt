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
    @Suppress("DEPRECATION") 
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

    
    private fun failedManager(cause: AudioRouteFailure): AudioDeviceManager = newManager().apply {
        onLibraryDevicesChanged(devices, null)
        select(bt)
        onRouteFailed(bt, cause)
    }

    
    @Test
    fun `a device that comes back after DEVICE_GONE may be retried`() {
        val manager = failedManager(AudioRouteFailure.DEVICE_GONE)

        manager.onLibraryDevicesChanged(devices, bt)

        
        
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, (manager.routeState as AudioRouteState.Applying).device.kind)
        assertNull(manager.confirmed)
        assertEquals(AudioDeviceKind.BLUETOOTH_HEADSET, manager.requested?.kind)
    }

    
    @Test
    fun `a failure with the device present keeps blocking library-driven retries`() {
        listOf(AudioRouteFailure.TIMEOUT, AudioRouteFailure.ERROR).forEach { cause ->
            val manager = failedManager(cause)
            val before = manager.routeSnapshot.value

            repeat(3) { manager.onLibraryDevicesChanged(devices, bt) }

            assertEquals("cause=$cause must stay latched", before, manager.routeSnapshot.value)
        }
    }

    
    @Test
    fun `each DEVICE_GONE buys at most one more attempt`() = runTest {
        val manager = failedManager(AudioRouteFailure.DEVICE_GONE)
        startApplier(manager)
        val pending = recordPendingRoutes(manager)

        
        manager.onLibraryDevicesChanged(devices, bt)
        advanceTimeBy(5_600)

        
        assertEquals(AudioRouteFailure.TIMEOUT, (manager.routeState as AudioRouteState.Failed).cause)
        val callsAtLatch = selectCalls.size

        
        repeat(3) { manager.onLibraryDevicesChanged(devices, bt) }
        advanceTimeBy(6_000)

        assertEquals(listOf(AudioDeviceKind.BLUETOOTH_HEADSET), pending)
        assertEquals("no further driving", callsAtLatch, selectCalls.size)
        assertEquals(AudioRouteFailure.TIMEOUT, (manager.routeState as AudioRouteState.Failed).cause)
    }

    
    










    @Test
    @Suppress("DEPRECATION") 
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

    
    @Test
    fun `the applier never records a route intent of its own`() = runTest {
        val manager = newManager()
        startApplier(manager)

        
        
        
        manager.onLibraryDevicesChanged(devices, bt)
        advanceTimeBy(5_600)

        assertTrue("the attempt really ran", selectCalls.isNotEmpty())
        assertEquals(AudioRouteFailure.TIMEOUT, (manager.routeState as AudioRouteState.Failed).cause)
        assertNull(manager.requested)
    }

    
    





    @Test
    @Suppress("DEPRECATION") 
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

    
    




    @Test
    fun `a wake storm cannot make an attempt exceed twice its poll-only rounds`() = runTest {
        val manager = newManager()
        val applier = startApplier(manager)
        manager.onLibraryDevicesChanged(devices, bt)
        runCurrent()

        
        repeat(40) {
            applier.wakeSignals.scoReceiver.onReceive(context, scoIntent(AudioManager.SCO_AUDIO_STATE_CONNECTED))
            runCurrent()
        }
        
        
        assertEquals("early wakes are honoured, then capped", 22, selectCalls.size)

        advanceTimeBy(5_600)

        assertEquals(AudioRouteFailure.TIMEOUT, (manager.routeState as AudioRouteState.Failed).cause)
        assertTrue("at most 20 rounds of two calls each", selectCalls.size <= 40)
    }

    
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

        
        assertEquals(listOf(AudioRouteState.Failed(bt, AudioRouteFailure.TIMEOUT)), terminals)
    }
}
