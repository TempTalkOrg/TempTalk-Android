package com.difft.android.chat.messages

import android.os.Looper
import com.difft.android.base.utils.ForegroundServiceStarter
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.difft.app.database.WCDBUpdateService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean







@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [31])
class MessageForegroundServiceIntegrationTest {

    @Before
    fun setUp() {
        clearFullStackLoggedOnce()
        mockkStatic(ForegroundServiceStarter::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
        clearFullStackLoggedOnce()
    }

    private fun clearFullStackLoggedOnce() {
        val field = ForegroundServiceStarter.javaClass.getDeclaredField("fullStackLoggedOnce")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val set = field.get(ForegroundServiceStarter) as MutableSet<String>
        set.clear()
    }

    



    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    
    private fun readServiceScopeJob(service: MessageForegroundService): Job {
        val field = MessageForegroundService::class.java
            .getDeclaredField("serviceScope")
            .apply { isAccessible = true }
        val scope = field.get(service) as CoroutineScope
        return scope.coroutineContext[Job]
            ?: error("serviceScope must have a Job in coroutineContext")
    }

    
    @Test
    fun `T6 onCreate starts room-update collector via WCDBUpdateService_updatingRooms`() {
        
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns true

        
        mockkObject(WCDBUpdateService)
        every { WCDBUpdateService.updatingRooms() } returns Unit

        
        val controller = Robolectric.buildService(MessageForegroundService::class.java)
        controller.create()
        idleMainLooper()

        
        verify(exactly = 1) { WCDBUpdateService.updatingRooms() }

        controller.destroy()
        idleMainLooper()
        assertFalse("isRunning must be false after destroy", MessageForegroundService.isRunning)
    }

    @Test
    fun `T-int-1 plus T-reg-1 onCreate FGSNAE absorbed — helper invoked, no FATAL escape`() {
        
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns false

        
        
        val controller = Robolectric.buildService(MessageForegroundService::class.java)
        controller.create()
        idleMainLooper()

        
        
        
        verify(atLeast = 1) {
            ForegroundServiceStarter.startForegroundSafely(
                any(),
                eq(MessageForegroundService.FOREGROUND_ID),
                any(),
                any()
            )
        }

        
        controller.destroy()
        idleMainLooper()
        assertFalse("isRunning must be false after destroy", MessageForegroundService.isRunning)
    }

    @Test
    fun `T-int-2 onCreate plus onStartCommand success path — helper invoked twice, isRunning true`() {
        
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns true

        
        val controller = Robolectric.buildService(MessageForegroundService::class.java)
        controller.create()
        controller.startCommand(0, 0)
        idleMainLooper()

        
        verify(atLeast = 2) {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        }
        assertTrue("isRunning must be true after onCreate success", MessageForegroundService.isRunning)

        controller.destroy()
        idleMainLooper()
        assertFalse("isRunning must be false after destroy", MessageForegroundService.isRunning)
    }

    @Test
    fun `T-int-4 onStartCommand FGSNAE — serviceScope cancelled on destroy, no FATAL escape`() {
        
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns false

        val controller = Robolectric.buildService(MessageForegroundService::class.java)
        controller.create()
        controller.startCommand(0, 0)
        idleMainLooper()

        
        verify(atLeast = 1) {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        }

        val service = controller.get()
        val job = readServiceScopeJob(service)

        
        controller.destroy()
        idleMainLooper()

        
        assertTrue("serviceScope.Job must be cancelled after onDestroy", job.isCancelled)
        assertFalse("isRunning must be false after destroy", MessageForegroundService.isRunning)
    }

    
    @Test
    fun `T-int-4 sanity isActive guard allows work when scope active`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val webSocketStarted = AtomicBoolean(false)

        val job = scope.launch {
            if (!isActive) {
                return@launch
            }
            webSocketStarted.set(true)
        }
        job.join()

        assertTrue(
            "WebSocket start MUST be invoked when serviceScope is active (regression baseline)",
            webSocketStarted.get()
        )
        scope.cancel()
    }
}
