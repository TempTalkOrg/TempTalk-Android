package com.difft.android.base.utils

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OrientationPolicyStaleWindowTest {

    @Before
    fun clearPendingUnfold() = OrientationPolicy.clearUnfoldPending()

    @Test
    fun `a window larger than the panel mode is ignored, a plausible one stays a floor`() {
        assertEquals("incident: outer panel 424dp, withheld inner window 608dp", 424, OrientationPolicy.resolveShortSideDp(424, 424, 608))
        assertEquals("healthy outer panel: the 425dp window is rounding, still a floor", 425, OrientationPolicy.resolveShortSideDp(424, 424, 425))
        assertEquals("one dp under the margin keeps the floor", 455, OrientationPolicy.resolveShortSideDp(424, 424, 455))
        assertEquals("at the margin the mode decides", 424, OrientationPolicy.resolveShortSideDp(424, 424, 456))
        assertEquals("no mode: the window cannot be checked against the panel", 608, OrientationPolicy.resolveShortSideDp(0, 424, 608))
        assertEquals("unfold letterbox: a narrow window never drags the mode down", 608, OrientationPolicy.resolveShortSideDp(608, 424, 498))
        assertEquals("a window cannot exceed its panel; the mode decides", 608, OrientationPolicy.resolveShortSideDp(608, 608, 859))
    }

    @Test
    @Config(qualifiers = "w860dp-h608dp")
    fun `stale inner configuration on the outer panel - the policy locks portrait`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val displayId = ShadowDisplayManager.addDisplay("w424dp-h622dp")
        ShadowDisplayManager.setSupportedModes(
            displayId,
            ShadowDisplayManager.ModeBuilder.modeBuilder(0).setWidth(424).setHeight(622).setRefreshRate(60f).build(),
        )
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        val sides = OrientationPolicy.readShortSides(activity, display)
        assertEquals("Precondition: the window is the withheld inner configuration", 608, sides.windowDp)
        assertEquals("Precondition: the mode is the outer panel", 424, sides.modeDp)

        OrientationPolicy.applyTo(activity, display)

        assertEquals(
            "A 608dp window on a 424dp panel is a configuration the OS withheld; the outer panel is " +
                "phone-sized and must get the portrait lock a healthy fold ends up with",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation,
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-mdpi")
    fun `the panel reader uses the display density, not the activity's`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val displayId = ShadowDisplayManager.addDisplay("w424dp-h622dp-440dpi")
        ShadowDisplayManager.setSupportedModes(
            displayId,
            ShadowDisplayManager.ModeBuilder.modeBuilder(0).setWidth(1168).setHeight(1712).setRefreshRate(60f).build(),
        )
        assertNotEquals("Precondition: the activity density differs from the display's", 2.75f, activity.resources.displayMetrics.density)

        val panel = checkNotNull(OrientationPolicy.readHostPanel(activity, displayId)) { "the display reports a mode" }

        assertEquals("1168x1712", panel.key)
        assertEquals(1168, panel.shortPx)
        assertEquals(1712, panel.longPx)
        assertEquals(2.75f, panel.density, 0.001f)
        assertEquals("1168 px at the display's 2.75 is 424 dp", 424, panel.shortDp)
        assertEquals(622, panel.longDp)
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        val sides = OrientationPolicy.readShortSides(activity, display)
        assertEquals("readShortSides shares the reader", 424, sides.modeDp)
        assertEquals(panel.key, sides.panelKey)
    }
}
