package com.difft.android.base

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import com.difft.android.base.utils.OrientationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OrientationPolicy.applyTo] — the orientation policy that
 * [BaseActivity.onCreate] (pre-super) and [BaseActivity.onConfigurationChanged] delegate to.
 *
 * The policy lives outside `BaseActivity` (which is `@AndroidEntryPoint` + abstract, so
 * exercising it directly would need Hilt test infrastructure and a test-only manifest):
 * `applyTo` takes a plain [Activity] and reads only `resources`, so the behavioural cases
 * below run against a stock Robolectric [Activity] with no Hilt, no test manifest, no MockK.
 * Robolectric `@Config(qualifiers=...)` selects which `orientation.xml` resolves, which is
 * the whole input to the policy.
 *
 * `BaseActivity.shouldApplyOrientationPolicy()`'s opt-out branch (used by `BeyondCorpActivity`)
 * is deliberately NOT covered here: it is a `BaseActivity` member, so it still needs the Hilt +
 * test-manifest setup the last case below describes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BaseActivityOrientationPolicyTest {

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
            "A compact phone window (smallestScreenWidthDp < 600) resolves values/orientation.xml " +
                "force_portrait_orientation=true, so the policy MUST lock portrait — that lock is " +
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
            "A tablet / unfolded-foldable window resolves values-sw600dp/orientation.xml " +
                "force_portrait_orientation=false, so the policy must release the lock and let the " +
                "user rotate. The qualifier names `sw600dp` explicitly because smallestScreenWidthDp — " +
                "not the available width — is what selects values-sw600dp on a device; Robolectric " +
                "4.14.1 happens to derive sw from w/h too (measured: dropping `sw600dp` here still " +
                "passes), so the explicit term keeps the test pinned to the production selector",
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
