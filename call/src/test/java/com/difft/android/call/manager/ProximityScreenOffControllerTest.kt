package com.difft.android.call.manager

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.call.earDevice
import com.difft.android.call.spkDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) } returns true
        every { powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, any()) } returns wakeLock
        every { wakeLock.isHeld } answers { held }
        every { wakeLock.acquire() } answers { held = true }
        every { wakeLock.release(any()) } answers { held = false }
        owner.registry.currentState = Lifecycle.State.CREATED
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `acquires when resumed and policy allows`() = runTest(dispatcher) {
        val hold = MutableStateFlow(true)
        ProximityScreenOffController(context, owner.lifecycle, backgroundScope, hold, dispatcher).start()

        assertFalse(held)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(held)
        verify(exactly = 1) { wakeLock.acquire() }
    }

    @Test
    fun `releases waiting for no proximity when policy flips`() = runTest(dispatcher) {
        val hold = MutableStateFlow(true)
        ProximityScreenOffController(context, owner.lifecycle, backgroundScope, hold, dispatcher).start()
        owner.registry.currentState = Lifecycle.State.RESUMED

        hold.value = false
        assertFalse(held)
        verify(exactly = 1) { wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }

        hold.value = true
        assertTrue(held)
    }

    @Test
    fun `keeps the lock while paused but visible, such as picture-in-picture`() = runTest(dispatcher) {
        val hold = MutableStateFlow(true)
        ProximityScreenOffController(context, owner.lifecycle, backgroundScope, hold, dispatcher).start()
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(held)

        owner.registry.currentState = Lifecycle.State.STARTED
        assertTrue(held)
        verify(exactly = 0) { wakeLock.release(any()) }
        verify(exactly = 1) { wakeLock.acquire() }
    }

    @Test
    fun `releases when hidden and re-acquires when visible again`() = runTest(dispatcher) {
        val hold = MutableStateFlow(true)
        ProximityScreenOffController(context, owner.lifecycle, backgroundScope, hold, dispatcher).start()
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(held)

        owner.registry.currentState = Lifecycle.State.CREATED
        assertFalse(held)
        verify(exactly = 1) { wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }

        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(held)
        verify(exactly = 2) { wakeLock.acquire() }
    }

    @Test
    fun `release tears down and drops the lock`() = runTest(dispatcher) {
        val hold = MutableStateFlow(true)
        val controller = ProximityScreenOffController(context, owner.lifecycle, backgroundScope, hold, dispatcher)
        controller.start()
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(held)

        controller.release()
        assertFalse(held)

        hold.value = false
        hold.value = true
        assertFalse(held)
    }

    @Test
    fun `does nothing when the proximity level is unsupported`() = runTest(dispatcher) {
        every { powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) } returns false
        ProximityScreenOffController(context, owner.lifecycle, backgroundScope, MutableStateFlow(true), dispatcher).start()
        owner.registry.currentState = Lifecycle.State.RESUMED

        verify(exactly = 0) { powerManager.newWakeLock(any(), any()) }
        assertFalse(held)
    }
}
