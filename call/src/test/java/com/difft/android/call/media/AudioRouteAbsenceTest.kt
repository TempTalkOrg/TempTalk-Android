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
    @Suppress("DEPRECATION") 
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

    





    private fun TestScope.poll(devices: List<AudioDevice>) {
        host.setDevices(devices)
        advanceTimeBy(500)
        runCurrent()
    }

    
    @Test
    fun `an empty device list parks the attempt without spending the budget`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, devices = emptyList()))
        advanceTimeBy(10_000)

        
        
        
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

    
    @Test
    fun `DEVICE_GONE is declared on the third consecutive missing poll`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()

        poll(absent) 
        assertTrue(host.failed.isEmpty())
        poll(absent) 
        assertTrue(host.failed.isEmpty())
        poll(absent) 

        assertEquals(listOf(bt to AudioRouteFailure.DEVICE_GONE), host.failed)
        assertEquals(1_500, currentTime)
    }

    
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

    
    @Test
    fun `a twitch that recovers and then fails to route is a TIMEOUT, not DEVICE_GONE`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()

        poll(absent)  
        poll(present) 
        advanceTimeBy(5_000)

        
        
        
        assertEquals(listOf(bt to AudioRouteFailure.TIMEOUT), host.failed)
    }

    
    @Test
    fun `intermittent absence that is still missing at the deadline is DEVICE_GONE`() = runTest {
        startApplier()
        host.emit(applyingSnapshot(bt, present))
        runCurrent()

        
        
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
