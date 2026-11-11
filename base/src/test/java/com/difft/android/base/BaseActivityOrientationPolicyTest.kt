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







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BaseActivityOrientationPolicyTest {

    
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
        
        
        assertEquals(608, OrientationPolicy.resolveShortSideDp(modeDp = 608, boundsDp = 424, windowDp = 608))
        
        
        assertEquals(425, OrientationPolicy.resolveShortSideDp(modeDp = 424, boundsDp = 608, windowDp = 425))
        
        
        assertEquals(608, OrientationPolicy.resolveShortSideDp(modeDp = 608, boundsDp = 608, windowDp = 498))
        
        assertEquals(752, OrientationPolicy.resolveShortSideDp(modeDp = 0, boundsDp = 752, windowDp = 469))
        assertEquals(608, OrientationPolicy.resolveShortSideDp(modeDp = 0, boundsDp = 424, windowDp = 608))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `frozen app bounds at the outer panel — the panel mode releases the unfolded display`() {
        
        
        
        
        
        val activity = createActivity()
        
        
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
        
    }
}
