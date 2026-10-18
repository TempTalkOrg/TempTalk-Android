package com.difft.android.call.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.call.data.CallStatus
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import dagger.hilt.EntryPoints
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * [ForegroundService] promotion and ownership, driven through the real service lifecycle
 * (`Robolectric.buildService`) with the shared promotion helper mocked, so production delegation to
 * `ForegroundServiceStarter` is verified.
 *
 * - Promotion: `isForegroundStarted` follows the helper's result at each promotion site, and
 *   `isServiceRunning` turns true only after a successful promotion.
 * - A denied typed promotion ([SecurityException]) is retried as DATA_SYNC; a double denial tells the
 *   owning session, stops the service up to its start id and does not crash. A promotion the
 *   platform refuses outright (the helper returns false and has stopped the service) tells the
 *   owning session too.
 * - Ownership: a start that reaches the service with no owning session promotes and then stops the
 *   service up to its own start id; a type-update start decrements the pending-update count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [31])
class ForegroundServiceIntegrationTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ogsm = OnGoingCallStateManager()
    private val controllerMock = mockk<OngoingCallServiceController>(relaxed = true)
    private var service: ServiceController<ForegroundService>? = null

    @Before
    fun setUp() {
        clearFullStackLoggedOnce()
        mockkStatic(ForegroundServiceStarter::class)
        mockkStatic(EntryPoints::class)
        val entryPoint = mockk<ForegroundService.ServiceEntryPoint>(relaxed = true) {
            every { onGoingCallStateManager() } returns ogsm
            every { ongoingCallServiceController() } returns controllerMock
        }
        every { EntryPoints.get(any(), ForegroundService.ServiceEntryPoint::class.java) } returns entryPoint
        ForegroundService.ownerSessionId = OWNER
    }

    @After
    fun tearDown() {
        service?.destroy()
        idleMainLooper()
        ForegroundService.isServiceRunning = false
        ForegroundService.ownerSessionId = ForegroundService.NO_OWNER
        ForegroundService.pendingTypeUpdates = 0
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

    private fun build(intent: Intent? = null): ServiceController<ForegroundService> =
        (if (intent == null) Robolectric.buildService(ForegroundService::class.java)
        else Robolectric.buildService(ForegroundService::class.java, intent)).also { service = it }

    /** Read the private `isForegroundStarted` field via reflection. */
    private fun readIsForegroundStarted(service: ForegroundService): Boolean {
        val field = ForegroundService::class.java
            .getDeclaredField("isForegroundStarted")
            .apply { isAccessible = true }
        return field.getBoolean(service)
    }

    /**
     * Invoke the private `updateServiceType` method via reflection. Production route is
     * `onStartCommand(ACTION_UPDATE_SERVICE_TYPE) -> updateServiceType()` on the main thread, after
     * the foreground re-promotion that every start performs.
     */
    private fun invokeUpdateServiceType(service: ForegroundService) {
        val method = ForegroundService::class.java
            .getDeclaredMethod("updateServiceType")
            .apply { isAccessible = true }
        method.invoke(service)
    }

    private fun grantMicrophone() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    // ---------- C1 success: onStartCommand helper returns true ----------
    @Test
    fun `C1 onStartCommand helper success — production delegates, isForegroundStarted true, returns NOT_STICKY`() {
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns true

        val controller = build()
        controller.create()
        controller.startCommand(0, 0)
        idleMainLooper()

        verify(atLeast = 1) {
            ForegroundServiceStarter.startForegroundSafely(
                any(),
                eq(ForegroundService.DEFAULT_NOTIFICATION_ID),
                any(),
                any()
            )
        }

        assertTrue(
            "C1 fix: isForegroundStarted = true after helper returns true",
            readIsForegroundStarted(controller.get())
        )
        // controller.startCommand returns the controller, not the int: call the method directly.
        val result = controller.get().onStartCommand(null, 0, 0)
        assertEquals(Service.START_NOT_STICKY, result)
    }

    // ---------- C1 failure: onStartCommand helper returns false ----------
    @Test
    fun `C1 onStartCommand helper FGSNAE — production delegates, isForegroundStarted stays false`() {
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns false

        val controller = build()
        controller.create()
        controller.startCommand(0, 0)
        idleMainLooper()

        verify(atLeast = 1) {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        }
        assertFalse(
            "C1 fix: isForegroundStarted stays false when helper returns false",
            readIsForegroundStarted(controller.get())
        )
        assertFalse(ForegroundService.isServiceRunning)
    }

    // ---------- T-int-3 C2: updateServiceType helper FGSNAE → isForegroundStarted reset ----------
    @Test
    fun `T-int-3 updateServiceType helper FGSNAE — isForegroundStarted reset to false`() {
        // First call (onStartCommand) returns true, second call (updateServiceType) returns false
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returnsMany listOf(true, false)

        val controller = build()
        controller.create()
        controller.startCommand(0, 0)
        idleMainLooper()

        val service = controller.get()
        assertTrue(
            "Precondition: isForegroundStarted = true after first helper success",
            readIsForegroundStarted(service)
        )

        invokeUpdateServiceType(service)
        idleMainLooper()

        verify(atLeast = 2) {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        }
        assertFalse(
            "C2 bug fix: isForegroundStarted must be reset to false when helper returns false in updateServiceType",
            readIsForegroundStarted(service)
        )
    }

    // ---------- T-int-3 sanity: updateServiceType helper success → isForegroundStarted stays true ----------
    @Test
    fun `T-int-3 sanity updateServiceType helper success — isForegroundStarted stays true`() {
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns true

        val controller = build()
        controller.create()
        controller.startCommand(0, 0)
        idleMainLooper()

        val service = controller.get()
        assertTrue(readIsForegroundStarted(service))

        invokeUpdateServiceType(service)
        idleMainLooper()

        verify(atLeast = 2) {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        }
        assertTrue(
            "Regression baseline: isForegroundStarted stays true on helper success",
            readIsForegroundStarted(service)
        )
    }

    // ---------- C0: @Volatile annotation present on isForegroundStarted ----------
    @Test
    fun `C0 isForegroundStarted field is @Volatile for cross-thread visibility`() {
        val field = ForegroundService::class.java.getDeclaredField("isForegroundStarted")
        assertTrue(
            "Region C0: isForegroundStarted must be @Volatile",
            (field.modifiers and java.lang.reflect.Modifier.VOLATILE) != 0
        )
    }

    @Test
    fun `isServiceRunning turns true only after a successful promotion and false on destroy`() {
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } returns true
        val controller = build()

        controller.create()
        assertFalse("created is not running", ForegroundService.isServiceRunning)

        controller.startCommand(0, 1)
        assertTrue(ForegroundService.isServiceRunning)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)

        controller.destroy()
        service = null
        assertFalse(ForegroundService.isServiceRunning)
    }

    @Test
    fun `a start with no owning session promotes, then stops itself up to its own start id`() {
        ForegroundService.ownerSessionId = ForegroundService.NO_OWNER
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } returns true
        val controller = build().create()

        controller.startCommand(0, 7)

        verify(exactly = 1) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) }
        val shadow = shadowOf(controller.get())
        assertEquals("stopSelfResult with its own start id", 7, shadow.stopSelfResultId)
        assertTrue(shadow.isForegroundStopped)
        assertFalse(readIsForegroundStarted(controller.get()))
        assertFalse(ForegroundService.isServiceRunning)
    }

    @Test
    fun `a late start of another session is promoted and the owner's service keeps running`() {
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } returns true
        val intent = Intent(app, ForegroundService::class.java).putExtra(ForegroundService.EXTRA_SESSION_ID, OWNER + 1)
        val controller = build(intent).create()

        controller.startCommand(0, 3)

        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(ForegroundService.isServiceRunning)
    }

    @Test
    fun `a type-update start decrements the pending count, then self-stops when its owner is gone`() {
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } returns true
        val update = Intent(app, ForegroundService::class.java).setAction(ForegroundService.ACTION_UPDATE_SERVICE_TYPE)
        val controller = build(update).create()
        ForegroundService.pendingTypeUpdates = 2

        controller.startCommand(0, 1)
        assertEquals(1, ForegroundService.pendingTypeUpdates)
        assertFalse("owned: keeps running", shadowOf(controller.get()).isStoppedBySelf)

        // The owner's stop was left to this start: it promotes first, then stops.
        ForegroundService.ownerSessionId = ForegroundService.NO_OWNER
        controller.startCommand(0, 2)

        assertEquals(0, ForegroundService.pendingTypeUpdates)
        assertEquals(2, shadowOf(controller.get()).stopSelfResultId)
        assertFalse(ForegroundService.isServiceRunning)

        controller.startCommand(0, 3)
        assertEquals("never below zero", 0, ForegroundService.pendingTypeUpdates)
    }

    @Test
    fun `a denied typed promotion is retried as DATA_SYNC alone and the service runs`() {
        grantMicrophone()
        val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val typed = dataSync or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), typed) } throws SecurityException("denied")
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), dataSync) } returns true
        val controller = build().create()

        controller.startCommand(0, 1)

        verify(exactly = 1) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), typed) }
        verify(exactly = 1) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), dataSync) }
        assertTrue(readIsForegroundStarted(controller.get()))
        assertTrue(ForegroundService.isServiceRunning)
        idleMainLooper()
        verify(exactly = 0) { controllerMock.onPromoteFailed(any()) }
    }

    @Test
    fun `a double promotion denial tells the owner on main, stops up to its start id and does not crash`() {
        grantMicrophone()
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } throws SecurityException("denied")
        val controller = build().create()
        idleMainLooper()

        // Directly, not through the controller, which idles the main looper after the call.
        controller.get().onStartCommand(null, 0, 5)

        verify(exactly = 2) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) }
        assertEquals(5, shadowOf(controller.get()).stopSelfResultId)
        assertFalse(ForegroundService.isServiceRunning)
        assertFalse(readIsForegroundStarted(controller.get()))
        verify(exactly = 0) { controllerMock.onPromoteFailed(any()) }

        idleMainLooper()
        verify(exactly = 1) { controllerMock.onPromoteFailed(OWNER) }
    }

    @Test
    fun `a promotion the platform refuses tells the owning session on main, without a DATA_SYNC retry`() {
        grantMicrophone()
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } returns false
        val controller = build().create()
        idleMainLooper()

        controller.get().onStartCommand(null, 0, 4)

        verify(exactly = 1) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) }
        assertFalse(ForegroundService.isServiceRunning)
        assertFalse(readIsForegroundStarted(controller.get()))
        verify(exactly = 0) { controllerMock.onPromoteFailed(any()) }

        idleMainLooper()
        verify(exactly = 1) { controllerMock.onPromoteFailed(OWNER) }
    }

    @Test
    fun `a refused promotion with no owning session tells nobody`() {
        ForegroundService.ownerSessionId = ForegroundService.NO_OWNER
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } returns false
        val controller = build().create()

        controller.startCommand(0, 2)
        idleMainLooper()

        verify(exactly = 0) { controllerMock.onPromoteFailed(any()) }
        assertFalse(ForegroundService.isServiceRunning)
    }

    @Test
    fun `the notification collector's re-promotion goes through the same denial guard`() {
        grantMicrophone()
        val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), not(dataSync)) } throws SecurityException("denied")
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), dataSync) } returns true
        OgsmTestState.setCallStatus(ogsm, CallStatus.CONNECTED)
        OgsmTestState.enterCall(ogsm, "r1")
        val controller = build().create()

        controller.startCommand(0, 1)
        idleMainLooper()

        // The start's promotion and the collector's ongoing-call notification, each retried as DATA_SYNC.
        verify(exactly = 2) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), dataSync) }
        assertTrue(ForegroundService.isServiceRunning)
        verify(exactly = 0) { controllerMock.onPromoteFailed(any()) }
    }

    @Test
    fun `onDestroy cancels the ongoing-call notification`() {
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } returns true
        val controller = build().create()
        controller.startCommand(0, 1)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            ForegroundService.DEFAULT_NOTIFICATION_ID,
            NotificationCompat.Builder(app, ForegroundService.CHANNEL_CONFIG_NAME_ONGOING_CALL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build(),
        )

        controller.destroy()
        service = null

        assertNull(shadowOf(nm).getNotification(ForegroundService.DEFAULT_NOTIFICATION_ID))
    }

    private companion object {
        const val OWNER = 41L
    }
}
