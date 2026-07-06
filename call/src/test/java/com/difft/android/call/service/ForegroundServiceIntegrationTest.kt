package com.difft.android.call.service

import android.app.Service
import android.os.Looper
import com.difft.android.base.utils.ForegroundServiceStarter
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Integration test for [ForegroundService] FGSNAE handling.
 *
 * Per design §8.2 T-int-3 + C0/C1/C2 regions and refinement-round-1 F-R-H1:
 *  - C0: `isForegroundStarted` is `@Volatile` (visibility fix, reflection check)
 *  - C1: `onStartCommand` sets `isForegroundStarted=true` ONLY on helper success;
 *        on failure returns `START_NOT_STICKY` early
 *  - C2: `updateServiceType` resets `isForegroundStarted=false` on helper failure
 *        (pre-existing bug fix)
 *
 * ## How these tests differ from the helper unit tests
 *
 * `:base/ForegroundServiceStarterTest` validates the helper in isolation. **These
 * integration tests prove that `ForegroundService.kt` ACTUALLY DELEGATES to
 * `ForegroundServiceStarter`** by driving the service lifecycle through
 * `Robolectric.buildService(...).create().startCommand(...)` and verifying via
 * MockK that the helper was called. If a future change reverts the C1 or C2
 * region to call `startForeground(...)` directly, these tests fail on `verify`.
 *
 * ## OQ1 fallback (recorded in implement-report.md)
 *
 * `ShadowThrowingService` cannot reach `:call` test sources (`:base` testFixtures
 * Kotlin compiles to empty AAR). Design §9.1 fallback is `mockkStatic` on
 * `ForegroundServiceStarter` — applied here **on top of** Robolectric service
 * lifecycle driving so production delegation is still verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [31])
class ForegroundServiceIntegrationTest {

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

    /** Read the private `isForegroundStarted` field via reflection. */
    private fun readIsForegroundStarted(service: ForegroundService): Boolean {
        val field = ForegroundService::class.java
            .getDeclaredField("isForegroundStarted")
            .apply { isAccessible = true }
        return field.getBoolean(service)
    }

    /**
     * Invoke the private `updateServiceType` method via reflection. Production
     * route is via `serviceExecutor.execute { ... ACTION_UPDATE_SERVICE_TYPE -> updateServiceType() }`,
     * which requires `onGoingCallStateManager` Hilt entry-point resolution to
     * resolve before reaching the when-branch dispatch. To keep the test
     * focused on the C2 helper-delegation invariant (without standing up Hilt),
     * we call `updateServiceType` directly on the constructed service.
     */
    private fun invokeUpdateServiceType(service: ForegroundService) {
        val method = ForegroundService::class.java
            .getDeclaredMethod("updateServiceType")
            .apply { isAccessible = true }
        method.invoke(service)
    }

    // ---------- C1 success: onStartCommand helper returns true ----------
    @Test
    fun `C1 onStartCommand helper success — production delegates, isForegroundStarted true, returns NOT_STICKY`() {
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns true

        val controller = Robolectric.buildService(ForegroundService::class.java)
        controller.create()
        controller.startCommand(0, 0)
        idleMainLooper()

        // Production delegation verified
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
        // Production code's onStartCommand always returns START_NOT_STICKY
        // (verified at ForegroundService.kt:221). Call the method directly
        // since controller.startCommand returns the controller (not the int).
        val result = controller.get().onStartCommand(null, 0, 0)
        assertEquals(Service.START_NOT_STICKY, result)

        controller.destroy()
        idleMainLooper()
    }

    // ---------- C1 failure: onStartCommand helper returns false ----------
    @Test
    fun `C1 onStartCommand helper FGSNAE — production delegates, isForegroundStarted stays false`() {
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns false

        val controller = Robolectric.buildService(ForegroundService::class.java)
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

        controller.destroy()
        idleMainLooper()
    }

    // ---------- T-int-3 C2: updateServiceType helper FGSNAE → isForegroundStarted reset ----------
    @Test
    fun `T-int-3 updateServiceType helper FGSNAE — isForegroundStarted reset to false`() {
        // First call (onStartCommand) returns true, second call (updateServiceType) returns false
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returnsMany listOf(true, false)

        val controller = Robolectric.buildService(ForegroundService::class.java)
        controller.create()
        controller.startCommand(0, 0)
        idleMainLooper()

        val service = controller.get()
        assertTrue(
            "Precondition: isForegroundStarted = true after first helper success",
            readIsForegroundStarted(service)
        )

        // Invoke updateServiceType directly (bypassing serviceExecutor + Hilt-dependent
        // intent dispatch). updateServiceType is annotated @RequiresApi(30) and SDK_INT=31
        // via @Config — meets the runtime check at ForegroundService.kt:324.
        invokeUpdateServiceType(service)
        idleMainLooper()

        // Helper called twice: once by onStartCommand, once by updateServiceType
        verify(atLeast = 2) {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        }
        assertFalse(
            "C2 bug fix: isForegroundStarted must be reset to false when helper returns false in updateServiceType",
            readIsForegroundStarted(service)
        )

        controller.destroy()
        idleMainLooper()
    }

    // ---------- T-int-3 sanity: updateServiceType helper success → isForegroundStarted stays true ----------
    @Test
    fun `T-int-3 sanity updateServiceType helper success — isForegroundStarted stays true`() {
        every {
            ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any())
        } returns true

        val controller = Robolectric.buildService(ForegroundService::class.java)
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

        controller.destroy()
        idleMainLooper()
    }

    // ---------- C0: @Volatile annotation present on isForegroundStarted ----------
    @Test
    fun `C0 isForegroundStarted field is @Volatile for cross-thread visibility`() {
        // Verify Region C0 — the @Volatile annotation was added to fix the
        // pre-existing visibility race between Main thread (onStartCommand)
        // and serviceExecutor thread (updateServiceType).
        val field = ForegroundService::class.java.getDeclaredField("isForegroundStarted")
        val modifiers = field.modifiers
        assertTrue(
            "Region C0: isForegroundStarted must be @Volatile to fix Main/executor visibility race",
            (modifiers and java.lang.reflect.Modifier.VOLATILE) != 0
        )
    }
}
