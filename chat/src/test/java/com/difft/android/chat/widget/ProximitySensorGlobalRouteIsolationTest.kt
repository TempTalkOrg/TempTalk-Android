package com.difft.android.chat.widget

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.chat.messages.TestScopeApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/**
 * T4-8 … T4-12 — `ProximitySensorManager` as a pure sensor source.
 *
 * T4-8 is the C-3 regression guard and the enforcement point of the Single Global Route Writer
 * invariant: with `AudioManager.mode` pre-set to `MODE_IN_COMMUNICATION` (an ongoing call), a full
 * near/far/stop cycle must leave every piece of process-global communication state untouched. The
 * class holding no `AudioManager` reference at all is the compile-time half of that guard; this is
 * the runtime half, and it covers the whole listener chain down to the player.
 *
 * Verify: :chat:testDebugUnitTest
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ProximitySensorGlobalRouteIsolationTest {

    private companion object {
        const val MAX_RANGE = 5f
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var app: TestScopeApplication
    private lateinit var sensorManager: SensorManager
    private lateinit var audioManager: AudioManager
    private lateinit var proximitySensor: Sensor

    private val nearFlips = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as TestScopeApplication
        sensorManager = app.getSystemService(SensorManager::class.java)
        audioManager = app.getSystemService(AudioManager::class.java)

        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { application } returns app
        every { appScope } returns testScope

        proximitySensor = ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY)
        shadowOf(proximitySensor).setMaximumRange(MAX_RANGE)

        // Object singletons survive across test methods inside one Robolectric sandbox.
        ProximitySensorManager.stop()
        setMediaPlayer(null)
        nearFlips.clear()
        ProximitySensorManager.setAudioDeviceChangeListener(
            object : ProximitySensorManager.AudioDeviceChangeListener {
                override fun onAudioDeviceChanged(isNear: Boolean) {
                    nearFlips += isNear
                    AudioMessageManager.applyProximityRoute(isNear)
                }
            },
        )
    }

    @After
    fun tearDown() {
        ProximitySensorManager.stop()
        setMediaPlayer(null)
        unmockkAll()
    }

    @Test
    fun `T4-8 a full proximity lifecycle never writes process-global audio state`() = runTest(testDispatcher) {
        val shadowAudio = shadowOf(audioManager)
        shadowAudio.setOutputDevices(
            listOf(
                deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
                deviceOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            ),
        )
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION // an ongoing call owns the route
        val player = mockk<MediaPlayer>(relaxed = true)
        every { player.setPreferredDevice(any()) } returns true
        setMediaPlayer(player)
        shadowOf(sensorManager).addSensor(proximitySensor)

        ProximitySensorManager.start()
        deliverProximity(near = true)
        advanceUntilIdle()
        deliverProximity(near = false)
        advanceUntilIdle()
        ProximitySensorManager.stop()

        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
        assertNull(audioManager.communicationDevice)
        assertFalse(audioManager.isSpeakerphoneOn)
        // The route was still applied — at the player, not globally.
        assertEquals(listOf(true, false), nearFlips)
        verify(exactly = 1) { player.setPreferredDevice(match { it?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) }
        verify(exactly = 1) { player.setPreferredDevice(null) }
    }

    @Test
    fun `T4-9 stop resets the cached proximity value so the same value propagates again`() = runTest(testDispatcher) {
        shadowOf(sensorManager).addSensor(proximitySensor)

        ProximitySensorManager.start()
        deliverProximity(near = true)
        advanceUntilIdle()
        ProximitySensorManager.stop()
        ProximitySensorManager.start()
        deliverProximity(near = true)
        advanceUntilIdle()

        assertEquals(listOf(true, true), nearFlips)
    }

    @Test
    fun `T4-10 a second start cycles the registration and never leaves two behind`() = runTest(testDispatcher) {
        shadowOf(sensorManager).addSensor(proximitySensor)

        ProximitySensorManager.start()
        ProximitySensorManager.start()
        deliverProximity(near = true)
        advanceUntilIdle()

        assertEquals(1, shadowOf(sensorManager).listeners.size)
        assertEquals(listOf(true), nearFlips)
    }

    @Test
    fun `T4-11 the device change listener survives stop and start`() = runTest(testDispatcher) {
        shadowOf(sensorManager).addSensor(proximitySensor)

        ProximitySensorManager.start()
        ProximitySensorManager.stop()
        ProximitySensorManager.start()
        deliverProximity(near = true)
        advanceUntilIdle()

        assertEquals(listOf(true), nearFlips)
    }

    @Test
    fun `T4-12 a device without a proximity sensor degrades without touching audio state`() = runTest(testDispatcher) {
        val modeBefore = audioManager.mode

        ProximitySensorManager.start()
        advanceUntilIdle()

        assertTrue(shadowOf(sensorManager).listeners.isEmpty())
        assertTrue(nearFlips.isEmpty())
        assertEquals(modeBefore, audioManager.mode)
        assertNull(audioManager.communicationDevice)
    }

    @Test
    fun `T4-20 a FAR reading below the advertised maximum is not near`() = runTest(testDispatcher) {
        // #1252: sensors whose FAR value sits below maximumRange were permanently NEAR under `value < maximumRange`.
        shadowOf(proximitySensor).setMaximumRange(10f)
        shadowOf(sensorManager).addSensor(proximitySensor)

        ProximitySensorManager.start()
        deliverProximityValue(5f)
        advanceUntilIdle()
        // The first value still propagates (route reset contract, T4-9) — but as FAR, not NEAR.
        assertEquals(listOf(false), nearFlips)

        deliverProximityValue(0f)
        advanceUntilIdle()
        assertEquals(listOf(false, true), nearFlips)
    }

    @Test
    fun `T4-21 a start while registered cycles the registration so the current value applies again`() = runTest(testDispatcher) {
        // Auto-play: onPlayComplete stops, the next message starts, the phone never moved. The new
        // player must still receive the (unchanged) NEAR decision.
        shadowOf(sensorManager).addSensor(proximitySensor)

        ProximitySensorManager.start()
        deliverProximity(near = true)
        advanceUntilIdle()
        ProximitySensorManager.stop()
        ProximitySensorManager.start() // restartPending forces an unregister + register even if the stop's sync already ran
        advanceUntilIdle()
        deliverProximity(near = true)
        advanceUntilIdle()

        assertEquals(1, shadowOf(sensorManager).listeners.size)
        assertEquals(listOf(true, true), nearFlips)
    }

    private fun deliverProximity(near: Boolean) = deliverProximityValue(if (near) 0f else MAX_RANGE)

    private fun deliverProximityValue(value: Float) {
        val event: SensorEvent = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_PROXIMITY)
        event.sensor = proximitySensor
        event.values[0] = value
        shadowOf(sensorManager).sendSensorEventToListeners(event)
    }

}
