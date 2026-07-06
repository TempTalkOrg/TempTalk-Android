package com.difft.android.base.utils

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.difft.android.test.shadows.ShadowThrowingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [ForegroundServiceStarter] — T1..T7 per design §8.1.
 *
 * A custom `ShadowThrowingService` (shipped in :base/src/testFixtures/) lets us
 * simulate `Service.startForeground()` throwing FGSNAE — or a plain
 * IllegalStateException — without touching production code. Tests run on
 * API 30 and API 31 via `@Config(sdk=[..])`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowThrowingService::class])
class ForegroundServiceStarterTest {

    /** Concrete test service used by Robolectric.buildService(...). */
    open class TestForegroundService : Service() {
        override fun onBind(intent: android.content.Intent?) = null
    }

    private fun makeNotification(service: Service): Notification {
        return NotificationCompat.Builder(service, "test-channel")
            .setContentTitle("test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    @Before
    fun setUp() {
        ShadowThrowingService.reset()
        clearFullStackLoggedOnce()
    }

    @After
    fun tearDown() {
        ShadowThrowingService.reset()
        clearFullStackLoggedOnce()
    }

    /**
     * Reset helper's per-process first-occurrence throttle so each test gets
     * a clean state. The set is `private` — reset via reflection.
     */
    private fun clearFullStackLoggedOnce() {
        val field = ForegroundServiceStarter.javaClass.getDeclaredField("fullStackLoggedOnce")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val set = field.get(ForegroundServiceStarter) as MutableSet<String>
        set.clear()
    }

    // ---------- T1 ----------
    @Test
    @Config(sdk = [31])
    fun `T1 API 31 default type success returns true and does not stopSelf`() {
        val controller = Robolectric.buildService(TestForegroundService::class.java).create()
        val service = controller.get()
        val n = makeNotification(service)

        val result = ForegroundServiceStarter.startForegroundSafely(service, 313399, n)

        assertTrue("helper should return true on success", result)
        assertFalse(
            "service should not be self-stopped on success",
            shadowOf(service).isStoppedBySelf
        )
        assertEquals(1, ShadowThrowingService.startForegroundCallCount)
    }

    // ---------- T2 ----------
    @Test
    @Config(sdk = [31])
    fun `T2 API 31 default type FGSNAE returns false and calls stopSelf`() {
        val controller = Robolectric.buildService(TestForegroundService::class.java).create()
        val service = controller.get()
        val n = makeNotification(service)
        ShadowThrowingService.throwOnNext = true

        val result = ForegroundServiceStarter.startForegroundSafely(service, 313399, n)

        assertFalse("helper should return false when FGSNAE caught", result)
        assertTrue(
            "service should have been self-stopped",
            shadowOf(service).isStoppedBySelf
        )
    }

    // ---------- T3 ----------
    @Test
    @Config(sdk = [31])
    fun `T3 API 31 non-FGSNAE IllegalStateException is re-thrown without stopSelf`() {
        val controller = Robolectric.buildService(TestForegroundService::class.java).create()
        val service = controller.get()
        val n = makeNotification(service)
        val sentinel = IllegalStateException("NotificationChannel BACKGROUND missing")
        ShadowThrowingService.throwOnNextPlainIse = sentinel

        val thrown = try {
            ForegroundServiceStarter.startForegroundSafely(service, 313399, n)
            null
        } catch (t: IllegalStateException) {
            t
        }
        assertNotNull("non-FGSNAE IllegalStateException should propagate", thrown)
        assertTrue("re-thrown instance should be identical", thrown === sentinel)
        assertFalse(
            "stopSelf must NOT be called on non-FGSNAE re-throw",
            shadowOf(service).isStoppedBySelf
        )
    }

    // ---------- T4 ----------
    @Test
    @Config(sdk = [30])
    fun `T4 API 30 plain IllegalStateException is re-thrown FGSNAE class not yet present`() {
        val controller = Robolectric.buildService(TestForegroundService::class.java).create()
        val service = controller.get()
        val n = makeNotification(service)
        val sentinel = IllegalStateException("plain ise on api 30")
        ShadowThrowingService.throwOnNextPlainIse = sentinel

        val thrown = try {
            ForegroundServiceStarter.startForegroundSafely(service, 313399, n)
            null
        } catch (t: IllegalStateException) {
            t
        }
        assertNotNull("ISE should propagate on API 30", thrown)
        assertTrue(thrown === sentinel)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    // ---------- T5 ----------
    @Test
    @Config(sdk = [30])
    fun `T5 API 30 non-zero foregroundServiceType success uses 3-arg overload`() {
        val controller = Robolectric.buildService(TestForegroundService::class.java).create()
        val service = controller.get()
        val n = makeNotification(service)

        val result = ForegroundServiceStarter.startForegroundSafely(
            service, 313399, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        assertTrue("3-arg overload success should return true", result)
        assertEquals(1, ShadowThrowingService.startForegroundCallCount)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    // ---------- T6 ----------
    @Test
    @Config(sdk = [31])
    fun `T6 API 31 non-zero type FGSNAE returns false and calls stopSelf`() {
        val controller = Robolectric.buildService(TestForegroundService::class.java).create()
        val service = controller.get()
        val n = makeNotification(service)
        ShadowThrowingService.throwOnNext = true

        val result = ForegroundServiceStarter.startForegroundSafely(
            service, 313399, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        assertFalse(result)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    // ---------- T7 ----------
    @Test
    @Config(sdk = [31])
    fun `T7 FGSNAE class reachable on Robolectric API 31 and is subtype of IllegalStateException`() {
        val clazz = Class.forName("android.app.ForegroundServiceStartNotAllowedException")
        assertNotNull(clazz)
        assertTrue(
            "FGSNAE must be a subtype of IllegalStateException",
            IllegalStateException::class.java.isAssignableFrom(clazz)
        )
    }
}
