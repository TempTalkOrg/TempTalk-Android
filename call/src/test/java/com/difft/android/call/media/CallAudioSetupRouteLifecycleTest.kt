package com.difft.android.call.media

import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.UserManager
import com.difft.android.call.btDevice
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.AudioRouteSnapshot
import com.difft.android.call.spkDevice
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import io.livekit.android.room.Room
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CallAudioSetupRouteLifecycleTest {

    private val bt = btDevice()
    private val spk = spkDevice()

    private val audioHandler = mockk<io.livekit.android.audio.AudioSwitchHandler>(relaxed = true)
    private val audioDeviceManager = mockk<AudioDeviceManager>(relaxed = true) {
        every { this@mockk.audioHandler } returns this@CallAudioSetupRouteLifecycleTest.audioHandler
        every { routeSnapshot } returns MutableStateFlow(
            AudioRouteSnapshot(availableDevices = listOf(bt, spk))
        )
        every { availableDevices } returns MutableStateFlow(listOf(bt, spk))
    }
    private val audioProcessor = mockk<AudioPipelineProcessor>(relaxed = true)
    private val callConfig = mockk<CallConfig>(relaxed = true) {
        every { denoise } returns null
    }
    private val userManager = mockk<UserManager>(relaxed = true) {
        every { getUserData() } returns null
    }
    private val routeApplier = mockk<AudioRouteApplier>(relaxed = true)

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun newSetup(scope: kotlinx.coroutines.CoroutineScope) = CallAudioSetup(
        scope = scope,
        audioDeviceManager = audioDeviceManager,
        audioProcessor = audioProcessor,
        callConfig = callConfig,
        isDenoiseEnabledProvider = { true },
        userManager = userManager,
        routeApplier = routeApplier,
    )

    @Test
    fun `binding the room state after stop creates no guard`() = runTest {
        val setup = newSetup(backgroundScope)
        val roomState = MutableStateFlow(Room.State.CONNECTED)

        setup.stop()
        setup.bindRoomState(roomState)
        roomState.value = Room.State.DISCONNECTED
        runCurrent()

        
        verify(exactly = 0) { audioDeviceManager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 1) { routeApplier.stop() }
    }

    @Test
    fun `binding the room state twice creates a single guard`() = runTest {
        val setup = newSetup(backgroundScope)
        val roomState = MutableStateFlow(Room.State.CONNECTED)

        setup.bindRoomState(roomState)
        setup.bindRoomState(roomState)
        roomState.value = Room.State.DISCONNECTED
        runCurrent()

        verify(exactly = 1) { audioDeviceManager.onAudioSwitchInvalidated(any(), any()) }
    }

    
    @Test
    fun `stop cannot interleave between listener registration and applier start`() {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val setup = newSetup(ioScope)
            val stopReturned = CountDownLatch(1)
            val stopCompletedDuringBringUp = AtomicBoolean(true)
            every { routeApplier.start() } answers {
                
                
                Thread { setup.stop(); stopReturned.countDown() }.start()
                stopCompletedDuringBringUp.set(stopReturned.await(300, TimeUnit.MILLISECONDS))
            }

            setup.start()

            verify(timeout = 5_000) { routeApplier.start() }
            assertTrue("stop() never returned", stopReturned.await(5, TimeUnit.SECONDS))
            assertFalse(
                "stop() interleaved inside the bring-up critical section",
                stopCompletedDuringBringUp.get(),
            )
            
            verifyOrder {
                audioHandler.registerAudioDeviceChangeListener(any())
                routeApplier.start()
                routeApplier.stop()
            }
        } finally {
            ioScope.cancel()
        }
    }
}
