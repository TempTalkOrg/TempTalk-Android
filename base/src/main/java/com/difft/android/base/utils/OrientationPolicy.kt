package com.difft.android.base.utils

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.difft.android.base.log.lumberjack.L

/**
 * Single source of truth for screen orientation across all activities:
 *   - Phone-sized DISPLAYS (short side < 600dp): lock SCREEN_ORIENTATION_PORTRAIT.
 *   - Tablet / unfolded-foldable DISPLAYS (short side ≥ 600dp): SCREEN_ORIENTATION_UNSPECIFIED
 *     so the user can rotate freely (drives the dual-pane layout on rotation).
 *
 * The decision is made from the DISPLAY the activity is on, never from the activity's own
 * window. The two differ whenever the OS shapes our window without asking us: a portrait-only
 * activity on a landscape inner display is letterboxed into a narrow centered window
 * (Xiaomi 18 Fold: 859x608dp display -> 498x608dp window), split-screen and freeform windows
 * are narrower than the display, and none of those change what the user can physically rotate.
 * A window-based decision is self-fulfilling there: requesting portrait produces a narrow
 * window, the narrow window resolves "phone", so the policy keeps requesting portrait — and on
 * an activity that is relaunched for the size change this becomes a relaunch storm (44
 * recreations in 4 s were logged on a Xiaomi 18 Fold before this was display-based).
 *
 * Done at runtime instead of `android:screenOrientation` in the manifest so that large-screen
 * devices which don't honor AOSP 16+'s sw≥600dp exemption (HarmonyOS NEXT, older AOSP, some OEM
 * ROMs) still rotate. Tradeoff: with no manifest orientation the splash window follows the
 * device, so a phone launched while held landscape briefly rotates to portrait — rare and worth
 * it to keep foldables flicker-free in their common unfolded-landscape posture.
 *
 * Lives outside BaseActivity so activities that do NOT extend it can apply the same policy, and
 * so [targetForDisplayShortSide] is unit-testable without an Activity.
 */
object OrientationPolicy {

    /** Display short side at or above which the user may rotate freely (Material large-screen bucket). */
    const val LARGE_DISPLAY_MIN_SHORT_SIDE_DP = 600

    /**
     * Resolve the orientation target for the display [activity] is on (or [display] when the
     * caller already knows which display changed) and apply it.
     *
     * CALL PRE-SUPER from `onCreate`: `setRequestedOrientation` post-super on API ≥ 26 can
     * throw IllegalStateException for translucent activities. Safe there because this reads
     * only display metrics and `resources` (available after `attachBaseContext`).
     *
     * Idempotent: when `requestedOrientation` already equals the target it returns without
     * logging or writing, so re-applying on every configuration or display change is silent.
     * Loop-free by construction: the target depends only on the display, and nothing this
     * policy requests can change the display.
     *
     * Returns the resolved target so callers can track OWNERSHIP: a caller must re-apply later
     * only while `requestedOrientation` still equals the value this policy last wrote — a screen
     * that set its own orientation (a media picker's unlock, a screen-share landscape lock) must
     * never be clobbered by the policy.
     */
    fun applyTo(activity: Activity, display: Display? = null): Int {
        val hostDisplay = display ?: ContextCompat.getDisplayOrDefault(activity)
        val shortSideDp = displayShortSideDp(activity, hostDisplay)
        val target = targetForDisplayShortSide(shortSideDp)
        if (activity.requestedOrientation == target) return target
        // Telemetry for OEM rotation-quirk triage (HyperOS letterbox, HarmonyOS NEXT, MagicOS,
        // ColorOS). Logged BEFORE the set so the line is present even if the OS rejects the
        // change. `displaySwDp` is the policy input; `windowSwDp` is the activity's own
        // configuration, so a letterboxed / split window is diagnosable from the log alone.
        L.i {
            "[BaseActivity] orientation set cls=${activity.javaClass.simpleName} " +
                "mfr=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT} target=$target " +
                "displaySwDp=$shortSideDp windowSwDp=${activity.resources.configuration.smallestScreenWidthDp}"
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

    /** Pure decision: the only input is the display's short side in dp. */
    fun targetForDisplayShortSide(shortSideDp: Int): Int =
        if (shortSideDp >= LARGE_DISPLAY_MIN_SHORT_SIDE_DP) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    /**
     * Short side of the display [hostDisplay] identifies, in dp, read from real display metrics.
     *
     * Deliberately NOT `WindowMetricsCalculator.computeMaximumWindowMetrics` / the activity's
     * `Configuration`: both are derived from the configuration the OS last DELIVERED to this
     * activity, and a letterboxed activity whose relaunch the OS is withholding still holds the
     * folded-posture configuration.
     *
     * The [Display] is re-fetched through the APPLICATION context's DisplayManager on purpose.
     * A Display obtained from an Activity context carries that activity's Resources, and since
     * Android 12 `getRealMetrics` on such a Display reports the activity's
     * `WindowConfiguration.maxBounds` — the split-screen half, the pop-up window, or the
     * letterbox (Samsung split-screen logged displaySwDp=469 for a 752dp display) — which is
     * exactly the window-level number this policy must not depend on. The application context's
     * Display answers for the whole display regardless of what this activity has been told.
     * Falls back to the configuration's smallestScreenWidthDp only when the display reports no
     * size (never on real hardware).
     */
    @Suppress("DEPRECATION") // getRealMetrics is the only display-level (not window-level) source.
    fun displayShortSideDp(activity: Activity, hostDisplay: Display): Int {
        val display = activity.applicationContext.getSystemService<DisplayManager>()
            ?.getDisplay(hostDisplay.displayId) ?: hostDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val shortPx = minOf(metrics.widthPixels, metrics.heightPixels)
        if (shortPx <= 0 || metrics.density <= 0f) {
            return activity.resources.configuration.smallestScreenWidthDp
        }
        return (shortPx / metrics.density).toInt()
    }
}
