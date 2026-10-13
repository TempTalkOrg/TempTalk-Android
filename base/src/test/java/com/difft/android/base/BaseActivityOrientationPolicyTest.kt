package com.difft.android.base

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import com.difft.android.base.utils.OrientationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * Unit tests for [OrientationPolicy.applyTo] — the orientation policy that
 * [BaseActivity.onCreate] (pre-super), [BaseActivity.onConfigurationChanged],
 * [BaseActivity.onPictureInPictureModeChanged], the
 * [com.difft.android.base.utils.OrientationDisplayWatcher] replay and the closed-fold branch of
 * the fold posture callback delegate to.
 *
 * The policy lives outside `BaseActivity` (which is `@AndroidEntryPoint` + abstract, so
 * exercising it directly would need Hilt test infrastructure and a test-only manifest):
 * `applyTo` takes a plain [Activity] and reads only display metrics and the activity's
 * `resources`, so the behavioural cases below run against a stock Robolectric [Activity] with
 * no Hilt, no test manifest, no MockK. The policy resolves between three readings (panel mode,
 * app-level bounds, the activity's window — see `OrientationPolicy.resolveShortSideDp`):
 * Robolectric `@Config(qualifiers=...)` sizes the default display and the activity's window,
 * `ShadowDisplayManager.addDisplay` stands in for a display the activity is NOT configured
 * against (the letterbox case), and `ShadowDisplayManager.setSupportedModes` drives the panel
 * mode independently of the bounds (the frozen-configuration cases).
 *
 * `BaseActivity.shouldApplyOrientationPolicy()`'s opt-out branch (used by `BeyondCorpActivity`)
 * is deliberately NOT covered here: it is a `BaseActivity` member, so it still needs the Hilt +
 * test-manifest setup the last case below describes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BaseActivityOrientationPolicyTest {

    // A pending unfold release is process-wide state in OrientationPolicy; start clean.
    @Before
    fun clearPendingUnfold() = OrientationPolicy.clearUnfoldPending()

    private fun createActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).create().get()

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `phone qualifier — applyOrientationPolicy sets SCREEN_ORIENTATION_PORTRAIT`() {
        val activity = createActivity()
        assertNotEquals(
            "Precondition: the activity must not already be portrait, otherwise applyTo's " +
                "idempotent early return would make the assertion below vacuous",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT.toLong(),
            activity.requestedOrientation.toLong()
        )

        OrientationPolicy.applyTo(activity)

        assertEquals(
            "A phone-sized display (short side < 600dp) MUST lock portrait — that lock is " +
                "the primary guard keeping phones out of the dual-pane width gate",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation
        )
    }

    @Test
    @Config(qualifiers = "sw600dp-w600dp-h800dp")
    fun `sw600dp qualifier — applyOrientationPolicy sets SCREEN_ORIENTATION_UNSPECIFIED`() {
        val activity = createActivity()
        // Start from a portrait lock, mirroring the manifest `android:screenOrientation="portrait"`
        // that most activities still declare: the policy must OVERRIDE it, not inherit it. Without
        // this seed the target (UNSPECIFIED) is already the initial value and the test would pass
        // vacuously through applyTo's early return.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        OrientationPolicy.applyTo(activity)

        assertEquals(
            "A tablet / unfolded-foldable display (short side >= 600dp) must release the lock and " +
                "let the user rotate",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            activity.requestedOrientation
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `applyOrientationPolicy is idempotent — a second call is a no-op`() {
        val activity = createActivity()
        OrientationPolicy.applyTo(activity)
        val afterFirstCall = activity.requestedOrientation

        OrientationPolicy.applyTo(activity)

        assertEquals(
            "Re-applying the policy must converge on the same target instead of toggling. This is " +
                "what makes BaseActivity.onConfigurationChanged re-applying it on every fold/unfold " +
                "safe: setRequestedOrientation can itself trigger a configuration change, and the " +
                "early return in OrientationPolicy.applyTo skips the write once the target already " +
                "matches",
            afterFirstCall,
            activity.requestedOrientation
        )
        assertEquals(
            "…and the converged value is still the phone portrait lock, not a released one",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation
        )
    }

    @Test
    fun `threshold — 599dp locks portrait, 600dp releases`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationPolicy.targetForDisplayShortSide(599))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, OrientationPolicy.targetForDisplayShortSide(600))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `letterboxed window — the display decides, not the activity's own configuration`() {
        // The activity is configured phone-sized (a 498dp letterbox window, or a folded-posture
        // configuration the OS never refreshed), but the display it sits on is an unfolded inner
        // panel. Xiaomi 18 Fold: window sw=498dp, display 859x608dp.
        val activity = createActivity()
        val unfoldedDisplayId = ShadowDisplayManager.addDisplay("w859dp-h608dp")
        val unfoldedDisplay = activity.getSystemService(DisplayManager::class.java).getDisplay(unfoldedDisplayId)
        assertEquals(
            "Precondition: the activity's own configuration is phone-bucket",
            true,
            activity.resources.configuration.smallestScreenWidthDp < 600
        )

        OrientationPolicy.applyTo(activity, unfoldedDisplay)

        assertEquals(
            "Deciding from the activity's window would answer 'phone' and re-request portrait, which " +
                "is what the OS answered with the letterbox in the first place — a self-fulfilling lock " +
                "(44 relaunches in 4 s on a Xiaomi 18 Fold). The display is 608dp on its short side, so " +
                "the policy must release",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            activity.requestedOrientation
        )
    }

    @Test
    fun `resolve — the panel mode decides, bounds only stand in without a mode, the window is a floor`() {
        // HyperOS, process started folded then unfolded: app-level bounds frozen at the outer
        // panel, the panel mode and the window already report the inner one → release.
        assertEquals(608, OrientationPolicy.resolveShortSideDp(modeDp = 608, boundsDp = 424, windowDp = 608))
        // HyperOS, process started unfolded then folded: bounds frozen at the inner panel, the mode
        // and the window say outer → lock. The stale bounds must NOT win.
        assertEquals(425, OrientationPolicy.resolveShortSideDp(modeDp = 424, boundsDp = 608, windowDp = 425))
        // Letterbox (the original Xiaomi 18 Fold case): every display-level reading says inner,
        // only the window is narrow → release; the narrow window never drags the result down.
        assertEquals(608, OrientationPolicy.resolveShortSideDp(modeDp = 608, boundsDp = 608, windowDp = 498))
        // No mode reported: bounds decide, window still a floor.
        assertEquals(752, OrientationPolicy.resolveShortSideDp(modeDp = 0, boundsDp = 752, windowDp = 469))
        assertEquals(608, OrientationPolicy.resolveShortSideDp(modeDp = 0, boundsDp = 424, windowDp = 608))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `frozen app bounds at the outer panel — the panel mode releases the unfolded display`() {
        // The app-level configuration (what getRealMetrics answers) still says the 424dp outer
        // panel because the process started folded, but the display's current mode is the
        // 608x859dp inner panel. On the device the relaunched activity's window is unfolded too;
        // here it is deliberately kept phone-bucket (w400dp) so the window floor cannot carry
        // the release and the mode alone has to.
        val activity = createActivity()
        // Seed the lock the folded launch left behind; without it UNSPECIFIED is already the
        // initial value and the assertion below would pass through applyTo's early return.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val displayId = ShadowDisplayManager.addDisplay("w424dp-h622dp")
        val density = activity.resources.displayMetrics.density
        ShadowDisplayManager.setSupportedModes(
            displayId,
            ShadowDisplayManager.ModeBuilder.modeBuilder(0)
                .setWidth((608 * density).toInt()).setHeight((859 * density).toInt()).setRefreshRate(60f).build(),
        )
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        val sides = OrientationPolicy.readShortSides(activity, display)
        assertEquals("Precondition: the bounds reading is the stale outer panel", 424, sides.boundsDp)
        assertEquals("Precondition: the mode reading is the inner panel", 608, sides.modeDp)

        OrientationPolicy.applyTo(activity, display)

        assertEquals(
            "Deciding from the frozen app-level bounds locks the unfolded display to portrait, and a " +
                "608dp-wide portrait window can never reach the dual-pane gate. The panel mode is the " +
                "fresh reading and must release",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            activity.requestedOrientation
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `frozen app bounds at the inner panel — the panel mode keeps the folded display locked`() {
        // Mirror image: process started unfolded (bounds frozen at 608), then folded — the mode
        // and the activity's window are the outer panel. The stale large bounds must not release.
        val activity = createActivity()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val displayId = ShadowDisplayManager.addDisplay("w859dp-h608dp")
        val density = activity.resources.displayMetrics.density
        ShadowDisplayManager.setSupportedModes(
            displayId,
            ShadowDisplayManager.ModeBuilder.modeBuilder(0)
                .setWidth((424 * density).toInt()).setHeight((622 * density).toInt()).setRefreshRate(60f).build(),
        )
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        assertEquals("Precondition: the bounds reading is the stale inner panel", 608, OrientationPolicy.readShortSides(activity, display).boundsDp)

        OrientationPolicy.applyTo(activity, display)

        assertEquals(
            "The outer panel is phone-sized; stale inner-panel bounds must not leave it free to rotate",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation
        )
    }

    @Test
    @Ignore(
        "Requires Hilt test infrastructure + a test-only :base AndroidManifest registering a " +
            "BaseActivity subclass. The three cases above run without it because they target " +
            "OrientationPolicy.applyTo directly; only the pre-super CALL ORDER inside " +
            "BaseActivity.onCreate still needs a real subclass."
    )
    fun `requestedOrientation is set BEFORE super_onCreate (pre-super invariant)`() {
        // Would do:
        // val activity = Robolectric.buildActivity(TestOrderProbeActivity::class.java).create().get()
        // assertEquals(
        //   "TestOrderProbeActivity records requestedOrientation at super.onCreate entry; " +
        //     "the recorded value must equal SCREEN_ORIENTATION_PORTRAIT, proving the policy " +
        //     "ran BEFORE super.onCreate (required because setRequestedOrientation post-super " +
        //     "throws on API 26 for translucent activities)",
        //   ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        //   activity.recordedOrientationAtSuperOnCreate
        // )
    }
}
