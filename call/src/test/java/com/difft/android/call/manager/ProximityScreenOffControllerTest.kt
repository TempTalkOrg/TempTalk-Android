package com.difft.android.call.manager

import android.content.Context
import android.os.PowerManager
import com.difft.android.call.earDevice
import com.difft.android.call.spkDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProximityScreenOffPolicyTest {

    @Test
    fun `earpiece audio call holds the lock`() {
        val route = AudioRouteSnapshot(confirmed = earDevice())
        assertTrue(shouldHoldProximityLock(route, cameraEnabled = false, screenSharing = false))
    }

    @Test
    fun `speaker route never holds the lock`() {
        val route = AudioRouteSnapshot(confirmed = spkDevice())
        assertFalse(shouldHoldProximityLock(route, cameraEnabled = false, screenSharing = false))
    }

    @Test
    fun `local camera or screen share disables the lock even on earpiece`() {
        val route = AudioRouteSnapshot(confirmed = earDevice())
        assertFalse(shouldHoldProximityLock(route, cameraEnabled = true, screenSharing = false))
        assertFalse(shouldHoldProximityLock(route, cameraEnabled = false, screenSharing = true))
    }

    @Test
    fun `unknown route counts as not earpiece`() {
        assertFalse(shouldHoldProximityLock(AudioRouteSnapshot(), cameraEnabled = false, screenSharing = false))
    }

    @Test
    fun `library pick is used before any confirmation`() {
        val route = AudioRouteSnapshot(availableDevices = listOf(earDevice(), spkDevice()))
        assertTrue(shouldHoldProximityLock(route, cameraEnabled = false, screenSharing = false))
    }
}

/**
 * The session-owned lock: held whenever the policy allows, with no page lifecycle involved, and
 * dropped (waiting for no proximity) on [ProximityScreenOffController.release] or when the
 * session's `Job` is cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProximityScreenOffControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val powerManager = mockk<PowerManager>()
    private val wakeLock = mockk<PowerManager.WakeLock>(relaxed = true)
    private var held = false

    private val context = mockk<Context> {
        every { applicationContext } returns this@mockk
        every { getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { getSystemService(Context.SENSOR_SERVICE) } returns null
    }

    @Before
    fun setUp() {
        every { powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) } returns true
        every { powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, any()) } returns wakeLock
        every { wakeLock.isHeld } answers { held }
        every { wakeLock.acquire() } answers { held = true }
        every { wakeLock.release(any()) } answers { held = false }
    }

    @Test
    fun `acquires as soon as the policy allows, with no page lifecycle`() = runTest(dispatcher) {
        ProximityScreenOffController(context, backgroundScope, MutableStateFlow(true), dispatcher).start()

        assertTrue(held)
        verify(exactly = 1) { wakeLock.acquire() }
    }

    @Test
    fun `releases waiting for no proximity when the policy flips, and re-acquires`() = runTest(dispatcher) {
        val hold = MutableStateFlow(true)
        ProximityScreenOffController(context, backgroundScope, hold, dispatcher).start()

        hold.value = false
        assertFalse(held)
        verify(exactly = 1) { wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }

        hold.value = true
        assertTrue(held)
    }

    @Test
    fun `release drops the lock and stops following the policy`() = runTest(dispatcher) {
        val hold = MutableStateFlow(true)
        val controller = ProximityScreenOffController(context, backgroundScope, hold, dispatcher)
        controller.start()
        assertTrue(held)

        controller.release()
        assertFalse(held)
        verify(exactly = 1) { wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }

        hold.value = false
        hold.value = true
        assertFalse(held)
    }

    @Test
    fun `cancelling the session's job drops the lock`() = runTest(dispatcher) {
        val sessionScope = CoroutineScope(Job() + dispatcher)
        ProximityScreenOffController(context, sessionScope, MutableStateFlow(true), dispatcher).start()
        assertTrue(held)

        sessionScope.cancel()

        assertFalse(held)
        verify(exactly = 1) { wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
    }

    @Test
    fun `does nothing when the proximity level is unsupported`() = runTest(dispatcher) {
        every { powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) } returns false
        ProximityScreenOffController(context, backgroundScope, MutableStateFlow(true), dispatcher).start()

        verify(exactly = 0) { powerManager.newWakeLock(any(), any()) }
        assertFalse(held)
    }
}
