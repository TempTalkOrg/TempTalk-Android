package com.difft.android.chat.widget

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.chat.messages.TestScopeApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
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
import org.robolectric.shadows.ShadowPowerManager







@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class VoiceMessageProximityLockTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var app: TestScopeApplication
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as TestScopeApplication
        audioManager = app.getSystemService(AudioManager::class.java)
        powerManager = app.getSystemService(PowerManager::class.java)

        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { application } returns app
        every { appScope } returns testScope

        shadowOf(powerManager).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
        VoiceMessageProximityLock.resetForTest()
        setMediaPlayer(mockk<MediaPlayer>(relaxed = true).also { every { it.setPreferredDevice(any()) } returns true })
    }

    @After
    fun tearDown() {
        VoiceMessageProximityLock.resetForTest()
        setMediaPlayer(null)
        unmockkAll()
    }

    private fun builtinOutputsOnly() {
        shadowOf(audioManager).setOutputDevices(
            listOf(deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), deviceOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)),
        )
    }

    @Test
    fun `near on the earpiece holds the lock, far releases it waiting for no proximity`() = runTest(testDispatcher) {
        builtinOutputsOnly()
        VoiceMessageProximityLock.enable()

        AudioMessageManager.applyProximityRoute(isNear = true)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)

        AudioMessageManager.applyProximityRoute(isNear = false)
        assertFalse(lock.isHeld)
    }

    @Test
    fun `a rejected earpiece route never holds the lock`() = runTest(testDispatcher) {
        
        
        builtinOutputsOnly()
        setMediaPlayer(mockk<MediaPlayer>(relaxed = true).also { every { it.setPreferredDevice(any()) } returns false })
        VoiceMessageProximityLock.enable()

        AudioMessageManager.applyProximityRoute(isNear = true)

        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun `a headset attached never holds the lock even when near`() = runTest(testDispatcher) {
        shadowOf(audioManager).setOutputDevices(
            listOf(deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), deviceOf(AudioDeviceInfo.TYPE_WIRED_HEADSET)),
        )
        VoiceMessageProximityLock.enable()

        AudioMessageManager.applyProximityRoute(isNear = true)

        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun `a late near after playback stopped is ignored`() = runTest(testDispatcher) {
        builtinOutputsOnly()
        VoiceMessageProximityLock.enable()
        VoiceMessageProximityLock.disable() 

        AudioMessageManager.applyProximityRoute(isNear = true)

        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun `stopping playback releases a held lock`() = runTest(testDispatcher) {
        builtinOutputsOnly()
        VoiceMessageProximityLock.enable()
        AudioMessageManager.applyProximityRoute(isNear = true)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)

        VoiceMessageProximityLock.disable()

        assertFalse(lock.isHeld)
    }

    @Test
    fun `sync converges on the live desired state, not on a captured argument`() = runTest(testDispatcher) {
        
        
        
        
        builtinOutputsOnly()
        VoiceMessageProximityLock.enable()
        AudioMessageManager.applyProximityRoute(isNear = true)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)

        VoiceMessageProximityLock.disable() 
        VoiceMessageProximityLock.enable() 
        AudioMessageManager.applyProximityRoute(isNear = true) 
        VoiceMessageProximityLock.sync() 

        assertTrue(lock.isHeld)
    }

    @Test
    fun `unsupported proximity level creates no lock`() = runTest(testDispatcher) {
        shadowOf(powerManager).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, false)
        builtinOutputsOnly()
        VoiceMessageProximityLock.enable()

        AudioMessageManager.applyProximityRoute(isNear = true)

        assertNull(ShadowPowerManager.getLatestWakeLock())
    }
}
