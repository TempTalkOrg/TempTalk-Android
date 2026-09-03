package com.difft.android.chat.widget

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.util.ServiceUtil
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T4-13 … T4-18 — player-scoped route application in `AudioMessageManager`.
 *
 * Every assertion targets `MediaPlayer.setPreferredDevice`; no test may observe a write to
 * `AudioManager`, which is a relaxed mock here precisely so an unexpected write would be visible.
 * T4-18 pins the sensor lifecycle symmetry: pausing releases the sensor, resuming re-arms it.
 *
 * Verify: :chat:testDebugUnitTest
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class VoiceMessageRouteApplyTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var audioManager: AudioManager
    private lateinit var player: MediaPlayer

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as TestScopeApplication
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { application } returns app
        every { appScope } returns testScope

        audioManager = mockk(relaxed = true)
        // ServiceUtil exposes @JvmStatic members, so Kotlin call sites bind to the static bridge:
        // mockkObject would leave them running the real body.
        mockkStatic(ServiceUtil::class)
        every { ServiceUtil.getAudioManager(any()) } returns audioManager

        player = mockk(relaxed = true)
        ProximitySensorManager.stop()
        setMediaPlayer(player)
    }

    @After
    fun tearDown() {
        setMediaPlayer(null)
        AudioMessageManager.currentPlayingMessage = null
        AudioMessageManager.isPaused = false
        unmockkAll()
    }

    @Test
    fun `T4-13 near with builtin outputs only pins the player to the earpiece`() {
        val earpiece = deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val speaker = deviceOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(earpiece, speaker)
        every { player.setPreferredDevice(any()) } returns true

        AudioMessageManager.applyProximityRoute(isNear = true)

        verify(exactly = 1) { player.setPreferredDevice(earpiece) }
    }

    @Test
    fun `T4-14 far reverts the player to default routing instead of forcing the speaker`() {
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns
            arrayOf(deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), deviceOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        every { player.setPreferredDevice(any()) } returns true

        AudioMessageManager.applyProximityRoute(isNear = false)

        verify(exactly = 1) { player.setPreferredDevice(null) }
    }

    @Test
    fun `T4-15 no player means no device enumeration at all`() {
        setMediaPlayer(null)

        AudioMessageManager.applyProximityRoute(isNear = true)

        verify(exactly = 0) { audioManager.getDevices(any()) }
    }

    @Test
    fun `T4-16 a released player throwing is swallowed`() {
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns
            arrayOf(deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        every { player.setPreferredDevice(any()) } throws IllegalStateException("player released")

        AudioMessageManager.applyProximityRoute(isNear = true)

        verify(exactly = 1) { player.setPreferredDevice(any()) }
    }

    @Test
    fun `T4-17 a rejected preferred device degrades without touching global state`() {
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns
            arrayOf(deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        every { player.setPreferredDevice(any()) } returns false

        AudioMessageManager.applyProximityRoute(isNear = true)

        verify(exactly = 1) { player.setPreferredDevice(any()) }
        verify(exactly = 0) { audioManager.mode = any() }
        verify(exactly = 0) { audioManager.setCommunicationDevice(any()) }
        verify(exactly = 0) { audioManager.isSpeakerphoneOn = any() }
    }

    @Test
    fun `T4-18 pauseAudio stops and resumeAudio restarts the proximity sensor`() {
        mockkObject(ProximitySensorManager)
        every { ProximitySensorManager.stop() } just Runs
        every { ProximitySensorManager.start() } just Runs

        AudioMessageManager.currentPlayingMessage = mockk<TextChatMessage>(relaxed = true)
        every { player.isPlaying } returns true

        invokeNoArg("pauseAudio")
        invokeNoArg("resumeAudio")

        verifyOrder {
            ProximitySensorManager.stop()
            ProximitySensorManager.start()
        }
        verify(exactly = 1) { ProximitySensorManager.stop() }
        verify(exactly = 1) { ProximitySensorManager.start() }
    }

    @Config(application = TestScopeApplication::class, sdk = [27])
    @Test
    fun `T4-19 on API 27 applyProximityRoute no-ops with zero global writes`() {
        // `MediaPlayer#setPreferredDevice`/`AudioManager#setCommunicationDevice` are API 28/31 methods
        // that don't exist at all on the API 27 android-all stub Robolectric loads for this test, so
        // they can't even be referenced here (mockk verify would throw NoSuchMethodError resolving a
        // symbol the real SDK 27 classfile doesn't declare) -- that absence IS the API surface this
        // no-op path avoids touching. getDevices()/mode/isSpeakerphoneOn are available since API 1/23
        // and stand in as the observable proxy for "no route enumeration or global write happened".
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns
            arrayOf(deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), deviceOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))

        AudioMessageManager.applyProximityRoute(isNear = true)

        verify(exactly = 0) { audioManager.getDevices(any()) }
        verify(exactly = 0) { audioManager.mode = any() }
        verify(exactly = 0) { audioManager.isSpeakerphoneOn = any() }
    }

    private fun invokeNoArg(name: String) {
        AudioMessageManager::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(AudioMessageManager)
    }
}
