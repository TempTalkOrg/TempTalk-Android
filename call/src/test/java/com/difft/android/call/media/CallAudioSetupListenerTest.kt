package com.difft.android.call.media

import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.UserManager
import com.difft.android.call.btDevice
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.spkDevice
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import io.livekit.android.audio.AudioSwitchHandler
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-change listener wiring in [CallAudioSetup] (design inventory rows #24 and #25).
 *
 * `start()` registers from `Dispatchers.IO`, which no test scheduler controls, so registration is
 * awaited with a MockK verification timeout rather than by advancing virtual time.
 *
 * Robolectric rather than plain JVM: mocking `AudioPipelineProcessor` runs its class initializer,
 * which calls `android.util.Log` — unmocked in the plain android.jar stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallAudioSetupListenerTest {

    private val audioHandler = mockk<AudioSwitchHandler>(relaxed = true)
    private val audioDeviceManager = mockk<AudioDeviceManager>(relaxed = true) {
        every { this@mockk.audioHandler } returns this@CallAudioSetupListenerTest.audioHandler
    }
    private val audioProcessor = mockk<AudioPipelineProcessor>(relaxed = true)
    private val callConfig = mockk<CallConfig>(relaxed = true) {
        every { denoise } returns null
    }
    private val userManager = mockk<UserManager>(relaxed = true) {
        every { getUserData() } returns null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        clearAllMocks()
    }

    private fun startAndCaptureListener(): AudioDeviceChangeListener {
        val setup = CallAudioSetup(
            scope = scope,
            audioDeviceManager = audioDeviceManager,
            audioProcessor = audioProcessor,
            callConfig = callConfig,
            isDenoiseEnabledProvider = { true },
            userManager = userManager,
            routeApplier = mockk(relaxed = true),
        )
        setup.start()
        val listener = slot<AudioDeviceChangeListener>()
        verify(timeout = 5_000) { audioHandler.registerAudioDeviceChangeListener(capture(listener)) }
        return listener.captured
    }

    // ── #24 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `the listener forwards the whole device list, not just the selection`() {
        val bt = btDevice()
        val spk = spkDevice()
        val listener = startAndCaptureListener()

        listener.invoke(listOf(bt, spk), bt)

        val devices = slot<List<AudioDevice>>()
        verify(exactly = 1) { audioDeviceManager.onLibraryDevicesChanged(capture(devices), bt) }
        assertEquals(2, devices.captured.size)
    }

    // ── #25 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a null selection does not recompute denoise`() {
        val listener = startAndCaptureListener()

        listener.invoke(listOf(btDevice()), null)

        // The null half of every retry round's selectDevice round trip must not flap denoise.
        verify(exactly = 0) { audioProcessor.setDenoiseEnabled(any()) }
        verify(exactly = 1) { audioDeviceManager.onLibraryDevicesChanged(any(), null) }
    }
}
