package com.difft.android.base.utils

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L

/**
 * Single source of truth for screen orientation across all activities:
 *   - Phones (sw < 600dp): lock SCREEN_ORIENTATION_PORTRAIT.
 *   - Tablets / unfolded foldables (sw ≥ 600dp): SCREEN_ORIENTATION_UNSPECIFIED
 *     so the user can rotate freely (drives the dual-pane layout on rotation).
 *
 * Done at runtime instead of `android:screenOrientation` in the manifest so
 * that large-screen devices which don't honor AOSP 16+'s sw≥600dp exemption
 * (HarmonyOS NEXT on Huawei foldables, older AOSP, some OEM ROMs) still rotate.
 * Tradeoff: with no manifest orientation the splash window follows the device,
 * so a phone launched while held landscape briefly rotates to portrait — rare
 * (auto-rotate is off by default and launchers stay portrait), and worth it to
 * keep foldables flicker-free in their common unfolded-landscape posture.
 *
 * `R.bool.force_portrait_orientation` resolves to true in values/orientation.xml (default,
 * compact-width) and false in values-sw600dp/orientation.xml (tablets, unfolded foldables).
 *
 * Lives outside BaseActivity so activities that do NOT extend it —
 * plain `ComponentActivity` screens — can apply the same policy instead of falling
 * back to a manifest lock that no runtime policy can override, and so the policy is
 * unit-testable without Hilt / a test-only activity manifest entry.
 */
object OrientationPolicy {

    /**
     * Resolve the orientation target from resources and apply it to [activity].
     *
     * CALL PRE-SUPER from `onCreate`: `setRequestedOrientation` post-super on API ≥ 26 can
     * throw IllegalStateException for translucent activities. Safe there because this reads
     * only `resources` (available after `attachBaseContext`) and touches no injected state.
     *
     * Idempotent: when `requestedOrientation` already equals the target it returns without
     * logging or writing. That is what makes re-applying on every configuration change (fold /
     * unfold) silent and loop-free — the target is a pure function of the resolved resource,
     * so the re-application converges on the first pass.
     *
     * Returns the resolved target so callers can track OWNERSHIP: a caller must re-apply on
     * later configuration changes only while `requestedOrientation` still equals the value
     * this policy last wrote — a screen that set its own orientation (a media picker's
     * unlock, a screen-share landscape lock) must never be clobbered by the policy.
     */
    fun applyTo(activity: Activity): Int {
        val target = if (activity.resources.getBoolean(R.bool.force_portrait_orientation)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (activity.requestedOrientation == target) return target
        // Telemetry for OEM rotation-quirk triage (HarmonyOS NEXT, MagicOS, ColorOS).
        // Logged BEFORE the set so the line is present even if the OS rejects the change.
        // `sw` names which orientation.xml configuration resolved, so a device that ends up in
        // the wrong bucket is diagnosable from the log alone.
        L.i {
            "[BaseActivity] orientation set cls=${activity.javaClass.simpleName} " +
                    "mfr=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT} target=$target " +
                    "sw=${activity.resources.configuration.smallestScreenWidthDp}"
        }
        try {
            activity.requestedOrientation = target
        } catch (e: IllegalStateException) {
            // API 26 (Android 8.0) throws "Only fullscreen activities can request
            // orientation" for translucent activities. Harmless — they inherit the
            // underlying activity's orientation. Fixed by AOSP in API 27.
            L.w { "[BaseActivity] orient set skipped cls=${activity.javaClass.simpleName}: ${e.message}" }
        }
        return target
    }
}
