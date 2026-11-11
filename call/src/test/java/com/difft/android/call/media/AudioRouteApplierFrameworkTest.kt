package com.difft.android.call.media

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.difft.android.call.btDevice
import com.difft.android.call.spkDevice
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteApplierFrameworkTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val realAudioManager: AudioManager
        get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioHandler = mockk<AudioSwitchHandler>(relaxed = true)
    private val mockAudioManager = mockk<AudioManager>(relaxed = true)
    private val host = FakeAudioRouteHost()
    private val selectCalls = mutableListOf<AudioDevice?>()

    private var scoOn = false

    private val bt = btDevice()
    private val spk = spkDevice()

    @Before
    @Suppress("DEPRECATION") 
    fun setUp() {
        every { mockAudioManager.isBluetoothScoOn } answers { scoOn }
        every { mockAudioManager.isSpeakerphoneOn } returns false
        every { mockAudioManager.mode } returns AudioManager.MODE_IN_COMMUNICATION
        every { mockAudioManager.mode = any() } just Runs
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            every { mockAudioManager.communicationDevice } returns null
        }
        every { audioHandler.selectDevice(captureNullable(selectCalls)) } just Runs
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun TestScope.startApplier(audioManager: AudioManager): AudioRouteApplier {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AudioRouteApplier(
            appContext = appContext,
            host = host,
            audioHandler = audioHandler,
            scope = backgroundScope,
            audioManager = audioManager,
            workDispatcher = dispatcher,
        ).also { it.start() }
    }

    private fun commDevice(type: Int): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    



    private fun grantDynamicReceiverPermission() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions("${appContext.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    
    private fun onBackgroundThread(block: () -> Unit) {
        var failure: Throwable? = null
        val thread = Thread({
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
        }, "audio-route-offthread-probe")
        thread.start()
        thread.join(2_000)
        failure?.let { throw it }
    }

    @Test
    fun `a real SCO CONNECTED broadcast reaches the receiver and shortens the wait`() = runTest {
        
        
        
        
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions("${appContext.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

        startApplier(mockAudioManager)
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()
        assertTrue("the first round ran", selectCalls.isNotEmpty())

        scoOn = true
        
        
        appContext.sendBroadcast(
            Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_CONNECTED)
        )
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        assertEquals(listOf(bt), host.confirmed)
        assertTrue("confirmed before the polling interval elapsed", currentTime < 500)
    }

    @Test
    @Suppress("DEPRECATION") 
    fun `the framework reports isBluetoothScoOn back and that alone confirms bluetooth`() = runTest {
        val audioManager = realAudioManager
        audioManager.isBluetoothScoOn = true

        startApplier(audioManager)
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()

        assertEquals(listOf(bt), host.confirmed)
        assertTrue("an already-active route needs no routing action", selectCalls.isEmpty())
    }

    @Test
    fun `posts from another thread run on the handler in submission order`() {
        val thread = HandlerThread("audio-route-fifo-probe").apply { start() }
        try {
            val handler = Handler(thread.looper)
            
            
            assertNotEquals(handler.looper, Looper.myLooper())

            val order = mutableListOf<String>()
            handler.post { order += "null" }
            handler.post { order += "target" }
            shadowOf(thread.looper).idle()

            assertEquals(listOf("null", "target"), order)
        } finally {
            thread.quit()
        }
    }

    @Test
    @Config(sdk = [33])
    fun `an API 31+ communication device confirms bluetooth without the legacy flag`() = runTest {
        val audioManager = realAudioManager
        val scoDevice = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            .build()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(scoDevice))
        assertTrue(audioManager.setCommunicationDevice(scoDevice))
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, audioManager.communicationDevice?.type)

        startApplier(audioManager)
        host.emit(applyingSnapshot(bt, listOf(bt, spk)))
        runCurrent()

        assertEquals(listOf(bt), host.confirmed)
        assertEquals(
            "commDevice",
            observedVia(
                ObservedRoute.BLUETOOTH,
                scoOn = false,
                speakerOn = false,
                commType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            ),
        )
    }

    



    @Test
    @Suppress("DEPRECATION") 
    fun `a comm-device callback for the target wakes the loop before the poll`() = runTest {
        val am = realAudioManager
        startApplier(am)
        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        runCurrent()
        assertTrue("the first round ran", selectCalls.isNotEmpty())

        am.isSpeakerphoneOn = true
        shadowOf(am).callOnCommunicationDeviceChangedListeners(
            commDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        )
        runCurrent()

        assertEquals(listOf(spk), host.confirmed)
        assertTrue("confirmed without waiting out the interval", currentTime < 500)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a cleared comm device never wakes the loop`() = runTest {
        val am = realAudioManager
        startApplier(am)
        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        runCurrent()

        am.isSpeakerphoneOn = true
        
        shadowOf(am).callOnCommunicationDeviceChangedListeners(null)
        runCurrent()

        assertTrue("the echo of our own clear must not wake anything", host.confirmed.isEmpty())
        assertEquals(0, currentTime)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(spk), host.confirmed)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a comm-device callback for another endpoint never wakes the loop`() = runTest {
        val am = realAudioManager
        startApplier(am)
        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        runCurrent()
        val callsBefore = selectCalls.size

        am.isSpeakerphoneOn = true
        shadowOf(am).callOnCommunicationDeviceChangedListeners(
            commDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        )
        runCurrent()

        assertTrue(host.confirmed.isEmpty())
        assertEquals(0, currentTime)
        assertEquals("no round before the interval elapses", callsBefore, selectCalls.size)
    }

    



    @Test
    fun `starting and stopping the wake signals repeatedly leaves the registration exact`() = runTest {
        val am = realAudioManager
        grantDynamicReceiverPermission()
        val signals = AudioRouteWakeSignals(appContext, host, am)
        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        val speaker = commDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        signals.start()
        signals.start()
        signals.stop()
        signals.stop()

        shadowOf(am).callOnCommunicationDeviceChangedListeners(speaker)
        assertNull("no listener left registered", withTimeoutOrNull(1) { signals.await() })

        signals.start()
        assertTrue(signals.summary().contains("wakeComm=on"))
        shadowOf(am).callOnCommunicationDeviceChangedListeners(speaker)
        assertNotNull("re-registration really took", withTimeoutOrNull(1) { signals.await() })
        signals.stop()
    }

    @Test
    @Config(sdk = [26])
    @Suppress("DEPRECATION")
    fun `below API 31 no comm waker is registered and polling still confirms`() = runTest {
        val am = realAudioManager
        val applier = startApplier(am)
        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        runCurrent()

        am.isSpeakerphoneOn = true
        advanceTimeBy(500)
        runCurrent()

        assertEquals(listOf(spk), host.confirmed)
        assertTrue(applier.wakeSignals.summary().contains("wakeComm=n/a"))
        applier.stop()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a failing comm-waker registration degrades to polling`() = runTest {
        every {
            mockAudioManager.addOnCommunicationDeviceChangedListener(any(), any())
        } throws RuntimeException("denied")

        val applier = startApplier(mockAudioManager)
        assertTrue(applier.wakeSignals.summary().contains("wakeComm=failed"))

        host.emit(applyingSnapshot(spk, listOf(bt, spk)))
        runCurrent()
        every { mockAudioManager.isSpeakerphoneOn } returns true
        advanceTimeBy(500)
        runCurrent()

        assertEquals(listOf(spk), host.confirmed)
        applier.stop()
    }

    




    @Test
    fun `AudioSwitchHandler selectDevice is callable from a non-handler thread`() {
        val handler = AudioSwitchHandler(appContext)

        onBackgroundThread { handler.selectDevice(spk) }

        handler.start()
        try {
            onBackgroundThread { handler.selectDevice(spk) }
        } finally {
            handler.stop()
        }
    }

    




    @Test
    fun `both routing calls of a round run on the drive loop's own thread`() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, WORK_THREAD) }
        val scope = CoroutineScope(SupervisorJob())
        val threads = mutableListOf<String>()
        val latch = CountDownLatch(2)
        every { audioHandler.selectDevice(any()) } answers {
            synchronized(threads) { threads += Thread.currentThread().name }
            latch.countDown()
        }

        val applier = AudioRouteApplier(
            appContext = appContext,
            host = host,
            audioHandler = audioHandler,
            scope = scope,
            audioManager = mockAudioManager,
            workDispatcher = executor.asCoroutineDispatcher(),
        ).also { it.start() }
        try {
            host.emit(applyingSnapshot(bt, listOf(bt, spk)))
            assertTrue("the round trip ran", latch.await(2, TimeUnit.SECONDS))
        } finally {
            applier.stop()
            scope.cancel()
            executor.shutdownNow()
        }

        
        val recorded = synchronized(threads) { threads.take(2) }
        assertEquals(2, recorded.size)
        recorded.forEach { assertTrue("ran on $it", it.startsWith(WORK_THREAD)) }
    }

    private companion object {
        const val WORK_THREAD = "audio-route-work-probe"
    }
}
