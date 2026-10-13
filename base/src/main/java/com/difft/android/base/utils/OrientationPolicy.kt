package com.difft.android.base.utils

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single source of truth for screen orientation across all activities:
 *   - Phone-sized DISPLAYS (short side < 600dp): lock SCREEN_ORIENTATION_PORTRAIT.
 *   - Tablet / unfolded-foldable DISPLAYS (short side ≥ 600dp): SCREEN_ORIENTATION_UNSPECIFIED
 *     so the user can rotate freely (drives the dual-pane layout on rotation).
 *
 * The decision is made from the DISPLAY the activity is on, with the activity's own window only
 * as a floor. The two differ whenever the OS shapes our window without asking us: a portrait-only
 * activity on a landscape inner display is letterboxed into a narrow centered window
 * (Xiaomi 18 Fold: 859x608dp display -> 498x608dp window), split-screen and freeform windows
 * are narrower than the display, and none of those change what the user can physically rotate.
 * A window-based decision is self-fulfilling there: requesting portrait produces a narrow
 * window, the narrow window resolves "phone", so the policy keeps requesting portrait — and on
 * an activity that is relaunched for the size change this becomes a relaunch storm (44
 * recreations in 4 s were logged on a Xiaomi 18 Fold before this was display-based). Which
 * display-level reading is trusted, and why the window still enters as a floor, is in
 * [resolveShortSideDp].
 *
 * Decided at runtime rather than by a fixed `android:screenOrientation` so that large-screen
 * devices which don't honor AOSP 16+'s sw≥600dp exemption (HarmonyOS NEXT, older AOSP, some OEM
 * ROMs) still rotate. The manifest carries `screenOrientation="locked"` on every opaque policy
 * activity for the gap BEFORE this runs: `locked` keeps whatever rotation is on screen and
 * never consults the sensor, where an unspecified orientation is answered with the sensor —
 * so a cold start from a portrait launcher on a phone held landscape no longer opens in
 * landscape and snaps to portrait once the policy writes PORTRAIT in onCreate. Launched over a
 * landscape screen the first frame still follows that screen; the write is pre-super so it
 * lands before the first frame. On a large display the policy's UNSPECIFIED frees the sensor
 * again (and Android 16's large-screen exemption ignores `locked` outright). Translucent
 * activities keep no manifest value: a fixed value (`locked` counts as one) throws on API 26
 * for them.
 *
 * One posture-driven exception: [preReleaseForUnfold] drops a portrait lock on a phone-sized
 * panel when the fold posture says a closed device is opening, so the request is already free
 * when the OS switches to the inner display; [applyTo] honors that release on the same panel
 * for a short time box instead of re-locking.
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
     * logging or writing, so re-applying on every configuration or display change is silent —
     * with one exception, a pending [preReleaseForUnfold] on the same panel, where it returns
     * UNSPECIFIED without writing although the display alone would say PORTRAIT.
     * Loop-free by construction: the target depends on the display plus a window floor that can
     * only release a lock, and nothing this policy requests can change the display. Loop-free
     * is the only guarantee here; which reading is trusted when they disagree, and why, is
     * [resolveShortSideDp]'s contract.
     *
     * Returns the resolved target so callers can track OWNERSHIP: a caller must re-apply later
     * only while `requestedOrientation` still equals the value this policy last wrote — a screen
     * that set its own orientation (a media picker's unlock, a screen-share landscape lock) must
     * never be clobbered by the policy.
     */
    fun applyTo(activity: Activity, display: Display? = null): Int {
        val hostDisplay = display ?: ContextCompat.getDisplayOrDefault(activity)
        val sides = readShortSides(activity, hostDisplay)
        val shortSideDp = sides.resolvedDp
        val target = targetForDisplayShortSide(shortSideDp)
        // Evaluated before the idempotent return: the relaunch on the inner panel arrives with
        // UNSPECIFIED already in force (the relaunch keeps the record's request) and writes
        // nothing, yet it is the display switch the release was made for and must end the
        // pending state — or a fold-back inside the time box finds the outer panel still
        // pending and leaves it unlocked until the next posture event.
        val pendingHere = isUnfoldPending(sides.panelKey)
        if (activity.requestedOrientation == target) return target
        // Between preReleaseForUnfold and the display switch the panel still reads phone-sized;
        // a replay in that window (any display event on the outer panel, or a relaunch there)
        // must not put the lock back, and a screen created there (manifest `locked`) must be
        // held open too — the request in force at the switch is what the OS rotates by, so a
        // PORTRAIT written here would bring back the portrait-then-landscape turn. Returns
        // UNSPECIFIED so ownership stays with the policy.
        if (target == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && pendingHere) {
            if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                L.i { "[BaseActivity] orientation held for unfold cls=${activity.javaClass.simpleName}" }
                try {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } catch (e: IllegalStateException) {
                    L.w { "[BaseActivity] orient hold skipped cls=${activity.javaClass.simpleName}: ${e.message}" }
                }
            }
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // Telemetry for OEM rotation-quirk triage (HyperOS letterbox / frozen app configuration,
        // HarmonyOS NEXT, MagicOS, ColorOS). Logged BEFORE the set so the line is present even
        // if the OS rejects the change. `displaySwDp` is the policy input; the three sources it
        // is resolved from follow, so whichever one an OEM misreports is diagnosable from the
        // log alone (see [readShortSides]).
        L.i {
            "[BaseActivity] orientation set cls=${activity.javaClass.simpleName} " +
                "mfr=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT} target=$target " +
                "displaySwDp=$shortSideDp modeSwDp=${sides.modeDp} boundsSwDp=${sides.boundsDp} " +
                "windowSwDp=${sides.windowDp}"
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

    /**
     * Release a portrait lock ahead of an unfold — the one posture-driven exception to the
     * display-only rule above. Call when the fold posture reports a closed device opening while
     * [activity] is still on the phone-sized outer panel. Working hypothesis under device test:
     * the OS decides the inner display's rotation from the request in force at the moment it
     * switches displays, so a lock still in force there puts the unfolded display up in portrait
     * first and rotates it to the sensor only after [applyTo] releases on the switch — the
     * portrait-then-landscape turn seen on every unfold on OEMs without the large-screen
     * exemption (HyperOS). Released before the switch, the display should come up in the sensor
     * rotation directly.
     *
     * No-op unless the activity currently holds SCREEN_ORIENTATION_PORTRAIT on a panel below
     * [LARGE_DISPLAY_MIN_SHORT_SIDE_DP]; whether that PORTRAIT is the policy's own is the
     * caller's ownership check. Returns the value written, or null when nothing was written.
     *
     * The release is remembered for this panel for [UNFOLD_PENDING_MS] so that replays before
     * the switch do not re-lock (see [applyTo]); it ends when the panel changes (the switch
     * happened: [applyTo] decides normally there), on [clearUnfoldPending] (the fold closed
     * again), or when the time box runs out (the switch never came).
     */
    fun preReleaseForUnfold(activity: Activity): Int? {
        if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) return null
        val sides = readShortSides(activity, ContextCompat.getDisplayOrDefault(activity))
        val panelDp = if (sides.modeDp > 0) sides.modeDp else sides.boundsDp
        if (panelDp >= LARGE_DISPLAY_MIN_SHORT_SIDE_DP) return null
        L.i {
            "[BaseActivity] orientation pre-release cls=${activity.javaClass.simpleName} " +
                "modeSwDp=${sides.modeDp} boundsSwDp=${sides.boundsDp} windowSwDp=${sides.windowDp}"
        }
        try {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (e: IllegalStateException) {
            // API 26 translucent-activity restriction, same as in applyTo; nothing was written.
            L.w { "[BaseActivity] orient pre-release skipped cls=${activity.javaClass.simpleName}: ${e.message}" }
            return null
        }
        // Without a panel identity the release cannot be told apart from another panel's, so
        // it is not remembered; the next replay simply decides from the display again.
        if (sides.panelKey.isNotEmpty()) {
            pendingUnfoldPanel = sides.panelKey
            pendingUnfoldSince = SystemClock.uptimeMillis()
        }
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** The fold closed again without a display switch: the next [applyTo] re-locks normally. */
    fun clearUnfoldPending() {
        pendingUnfoldPanel = null
    }

    /**
     * A pre-release is live: made within [UNFOLD_PENDING_MS] and not yet ended by a display
     * switch or a closed fold. A screen created while this is true was held open by [applyTo]
     * and needs the same delayed re-lock as the screen that released.
     */
    fun isUnfoldPending(): Boolean =
        pendingUnfoldPanel != null && SystemClock.uptimeMillis() - pendingUnfoldSince <= UNFOLD_PENDING_MS

    /** How long a pre-release keeps replays from re-locking the same panel while the switch is awaited. */
    const val UNFOLD_PENDING_MS = 1500L

    /** Slack after [UNFOLD_PENDING_MS] so the delayed re-lock finds the time box already over. */
    const val UNFOLD_RELOCK_SLACK_MS = 100L

    /**
     * After a release — a pre-release this screen made, or the hold [applyTo] gives a screen
     * created inside a live release — run [reapply] once the time box is over, so a fold held
     * without a display switch converges on the normal policy. Lifecycle-scoped: a relaunch
     * drops it, and the relaunched instance decides from its own display. Shared by
     * `BaseActivity` and the call activity, which is not one.
     */
    fun scheduleUnfoldRelock(owner: LifecycleOwner, reapply: () -> Unit) {
        owner.lifecycleScope.launch {
            delay(UNFOLD_PENDING_MS + UNFOLD_RELOCK_SLACK_MS)
            reapply()
        }
    }

    @Volatile
    private var pendingUnfoldPanel: String? = null

    @Volatile
    private var pendingUnfoldSince = 0L

    private fun isUnfoldPending(panelKey: String): Boolean {
        val panel = pendingUnfoldPanel ?: return false
        if (panel != panelKey || SystemClock.uptimeMillis() - pendingUnfoldSince > UNFOLD_PENDING_MS) {
            pendingUnfoldPanel = null
            return false
        }
        return true
    }

    /** Pure decision: the only input is the display's short side in dp. */
    fun targetForDisplayShortSide(shortSideDp: Int): Int =
        if (shortSideDp >= LARGE_DISPLAY_MIN_SHORT_SIDE_DP) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    /**
     * The three short-side readings the policy resolves between, all in dp.
     *
     * @property modeDp   the panel's physical resolution ([Display.getMode]) over the display's
     *   logical density. `DisplayInfo` is re-fetched from `DisplayManagerGlobal` on every call, so
     *   this follows a fold/unfold panel switch immediately and never passes through any
     *   `Resources`. 0 when the display reports no mode (exotic
     *   displays).
     * @property boundsDp what `Display.getRealMetrics` answers. Since Android 12 that is NOT the
     *   display: for any Display obtained through a Context it is that Context's
     *   `WindowConfiguration.maxBounds` (AOSP `Display.shouldReportMaxBounds`). Read through the
     *   APPLICATION context so it is the app-level configuration rather than this activity's
     *   window (an activity-context Display would report the split-screen half, the pop-up
     *   window, or the letterbox — Samsung split-screen logged 469dp for a 752dp display).
     * @property windowDp the activity's own `smallestScreenWidthDp`.
     * @property panelKey the panel's physical mode as `WxH` (empty when no mode is reported);
     *   identifies WHICH panel a reading came from, so a pre-release can be tied to one.
     */
    data class ShortSides(val modeDp: Int, val boundsDp: Int, val windowDp: Int, val panelKey: String) {
        val resolvedDp: Int get() = resolveShortSideDp(modeDp, boundsDp, windowDp)
    }

    /**
     * Pure resolution rule. The panel mode decides; the app-level bounds stand in only when no
     * mode is reported; the activity's window is a floor under both.
     *
     * Why not the bounds alone: HyperOS updates the app-level configuration only when the process
     * starts, so its maxBounds stay at the launch posture. A process started folded and then
     * unfolded keeps answering the outer panel (Xiaomi 18 Fold: boundsSwDp=424 while the window
     * already measured 608dp) and would lock the unfolded display to portrait — which at 608dp
     * wide can never reach the dual-pane gate; a process started unfolded and then folded keeps
     * answering 608 and would leave the outer panel free to rotate.
     *
     * Why the window floor: a window can never be larger than the display it is on, so a window
     * short side at or above the threshold proves a large display even if every display-level
     * reading is stale. It can only widen the result, and widening only ever releases a lock,
     * so it cannot re-create the request-portrait → letterbox → "phone" loop this policy exists
     * to break. The window can be stale only in the SMALL direction: a letterbox keeps a narrow
     * window on a bigger display, so no configuration is delivered; when the display shrinks
     * below the window the window manager has to resize it, so the activity is relaunched or
     * gets `onConfigurationChanged` (every fold on the Xiaomi 18 Fold relaunched IndexActivity
     * with the 425dp window), and each of those re-applies the policy.
     */
    fun resolveShortSideDp(modeDp: Int, boundsDp: Int, windowDp: Int): Int =
        maxOf(if (modeDp > 0) modeDp else boundsDp, windowDp)

    /**
     * Read the three sources for the display [hostDisplay] identifies. Deliberately NOT
     * `WindowMetricsCalculator.computeMaximumWindowMetrics` / the activity's `Configuration`
     * alone: both are derived from the configuration the OS last DELIVERED to this activity, and
     * a letterboxed activity whose relaunch the OS is withholding still holds the folded-posture
     * configuration — they enter only as the window floor.
     */
    @Suppress("DEPRECATION") // getRealMetrics: only public source of the display's logical density.
    fun readShortSides(activity: Activity, hostDisplay: Display): ShortSides {
        val display = activity.applicationContext.getSystemService<DisplayManager>()
            ?.getDisplay(hostDisplay.displayId) ?: hostDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val density = metrics.density
        val mode = runCatching { display.mode }.getOrNull()
        return ShortSides(
            modeDp = mode?.let { dpOf(minOf(it.physicalWidth, it.physicalHeight), density) } ?: 0,
            boundsDp = dpOf(minOf(metrics.widthPixels, metrics.heightPixels), density),
            windowDp = activity.resources.configuration.smallestScreenWidthDp,
            panelKey = mode?.let { "${it.physicalWidth}x${it.physicalHeight}" } ?: "",
        )
    }

    private fun dpOf(px: Int, density: Float): Int = if (px > 0 && density > 0f) (px / density).toInt() else 0
}
