package com.difft.android.test.shadows

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/**
 * Test shadow that simulates the API 31+ BG-FGS-not-allowed throw.
 *
 * Apply via `@Config(shadows = [ShadowThrowingService::class], sdk = [31])`.
 * Toggle [throwOnNext] from the test body to schedule the throw on the next
 * `startForeground` call (allows asserting success then failure within one
 * test, used by T-int-3 and T-int-4).
 *
 * Per design §8.3 / F-R3-L1: if the FGSNAE public ctor is not available at
 * test compile time in some Robolectric Android JAR variants, fall back to
 * reflective instantiation. We attempt the direct `throw` first; on any
 * NoSuchMethodError-style failure at construction, callers should switch to
 * MockK (`mockkObject(ForegroundServiceStarter)`) per design §9.1 risk matrix.
 */
@Implements(Service::class)
class ShadowThrowingService : ShadowService() {

    companion object {
        /** Schedule the NEXT startForeground call to throw FGSNAE. */
        @Volatile
        var throwOnNext: Boolean = false

        /**
         * Optional non-FGSNAE [IllegalStateException] to throw on the next call.
         * Takes precedence over [throwOnNext]. Used by T3/T4 (re-throw branch).
         */
        @Volatile
        var throwOnNextPlainIse: IllegalStateException? = null

        @Volatile
        var startForegroundCallCount: Int = 0

        fun reset() {
            throwOnNext = false
            throwOnNextPlainIse = null
            startForegroundCallCount = 0
        }

        /**
         * Construct an FGSNAE. Public ctor on AOSP API 31+; fall back to
         * reflection if compile-time visibility is restricted (per F-R3-L1).
         */
        // Test fixture exercised via @Config(sdk = [31]); never ships in APK.
        @SuppressLint("NewApi")
        internal fun newFgsnae(message: String): ForegroundServiceStartNotAllowedException {
            return try {
                ForegroundServiceStartNotAllowedException(message)
            } catch (t: Throwable) {
                val ctor = ForegroundServiceStartNotAllowedException::class.java
                    .getDeclaredConstructor(String::class.java)
                    .apply { isAccessible = true }
                ctor.newInstance(message) as ForegroundServiceStartNotAllowedException
            }
        }
    }

    @Implementation
    override fun startForeground(id: Int, notification: Notification?) {
        startForegroundCallCount++
        throwOnNextPlainIse?.let {
            throwOnNextPlainIse = null
            throw it
        }
        if (throwOnNext) {
            throwOnNext = false
            throw newFgsnae("simulated mAllowStartForeground false")
        }
        // Otherwise delegate to default no-op behavior.
    }

    @Implementation
    override fun startForeground(id: Int, notification: Notification?, foregroundServiceType: Int) {
        startForegroundCallCount++
        throwOnNextPlainIse?.let {
            throwOnNextPlainIse = null
            throw it
        }
        if (throwOnNext) {
            throwOnNext = false
            throw newFgsnae("simulated mAllowStartForeground false")
        }
    }
}
