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

/**
 * Route lifecycle wiring through [CallAudioSetup]: Phase-B guard binding (design inventory rows
 * #116 and #117) and the `start()` bring-up critical section (row #127).
 *
 * Row #127 uses a real IO scope and a real thread rather than `runTest`: it asserts that a
 * concurrent `stop()` cannot complete while the bring-up holds the setup monitor, which a single
 * virtual-time thread cannot express.
 *
 * A mock manager on purpose: both rows assert *how many times* the generation reset reaches the
 * manager (never after `stop()`, exactly once for two binds), which is what distinguishes "one guard"
 * from "two guards" — a snapshot assertion cannot, because the second guard's invalidation is a
 * silent no-op on an already-clean snapshot.
 *
 * Robolectric rather than plain JVM: mocking `AudioPipelineProcessor` runs its class initializer,
 * which calls `android.util.Log` — unmocked in the plain android.jar stub.
 */
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

    // ── #116 ────────────────────────────────────────────────────────────────────
    @Test
    fun `binding the room state after stop creates no guard`() = runTest {
        val setup = newSetup(backgroundScope)
        val roomState = MutableStateFlow(Room.State.CONNECTED)

        setup.stop()
        setup.bindRoomState(roomState)
        roomState.value = Room.State.DISCONNECTED
        runCurrent()

        // A hang-up racing Phase B must not leak the guard's collectors.
        verify(exactly = 0) { audioDeviceManager.onAudioSwitchInvalidated(any(), any()) }
        verify(exactly = 1) { routeApplier.stop() }
    }

    // ── #117 ────────────────────────────────────────────────────────────────────
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

    // ── start() critical section: stop() must not interleave with it ──────────────
    @Test
    fun `stop cannot interleave between listener registration and applier start`() {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val setup = newSetup(ioScope)
            val stopReturned = CountDownLatch(1)
            val stopCompletedDuringBringUp = AtomicBoolean(true)
            every { routeApplier.start() } answers {
                // A concurrent hang-up must not be able to run to completion here: if it could, it
                // would flip `stopped` and unregister the listener while this bring-up is still
                // mid-flight, and the applier started right after would keep its SCO receiver and
                // collect job alive for the rest of the process — unreachable for a second cleanup.
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
            // The cleanup therefore always lands after the bring-up: nothing is left running.
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
