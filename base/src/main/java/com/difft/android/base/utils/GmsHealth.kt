package com.difft.android.base.utils

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L

/**
 * Detects a GMS install on which nothing serves the `com.google.android.gsf.gservices` authority.
 *
 * On a stock device that authority is hosted by GMS itself (or by the legacy Google Services
 * Framework package). Sideloaded GMS bundles on HMS-only devices sometimes lack both. GMS then
 * fails while serving our binder calls and the failure comes back into the app as
 * `SecurityException("Failed to find provider com.google.android.gsf.gservices ...")` on whatever
 * thread the SDK's own Handler runs, where no app code can catch it. GMS IPC that the app itself
 * initiates (FCM token fetch, SmsRetriever) consults this first and skips; Analytics binds GMS on
 * its own at init and cannot be gated here, which is what `CrashFilter`'s main-looper resume covers.
 *
 * `GoogleApiAvailability` cannot tell: it validates the GMS package only and reports SUCCESS on
 * exactly these devices. The lookup needs `<queries><package android:name="com.google.android.gsf"/>`
 * in the app manifest so a GSF-hosted provider stays visible under API 30+ package filtering.
 *
 * Evaluated once per process (two PackageManager lookups) and cached. `TempTalkApplication` warms
 * it in an off-main startup task so main-thread callers read the cached verdict; a mid-session
 * package change takes effect on the next launch.
 */
object GmsHealth {
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val GSERVICES_AUTHORITY = "com.google.android.gsf.gservices"

    @Volatile
    private var broken: Boolean? = null

    /** True when GMS is installed but the gservices provider is not resolvable for this user. */
    fun isGmsBroken(context: Context): Boolean =
        broken ?: detect(context.packageManager).also { broken = it }

    private fun detect(pm: PackageManager): Boolean {
        // Fail open: any PackageManager failure keeps today's behavior (GMS treated as usable).
        val gmsInstalled = runCatching { pm.getPackageInfo(GMS_PACKAGE, 0) }.isSuccess
        if (!gmsInstalled) return false
        val gservicesMissing = runCatching { pm.resolveContentProvider(GSERVICES_AUTHORITY, 0) == null }
            .getOrDefault(false)
        if (gservicesMissing) {
            L.w { "[GmsHealth] GMS installed but $GSERVICES_AUTHORITY is unresolvable; skipping optional GMS IPC" }
        }
        return gservicesMissing
    }

    @VisibleForTesting
    fun resetForTest() {
        broken = null
    }
}
