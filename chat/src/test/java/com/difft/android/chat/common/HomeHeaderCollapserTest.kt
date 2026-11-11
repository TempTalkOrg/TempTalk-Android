package com.difft.android.chat.common

import android.app.Activity
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.difft.android.chat.R
import com.google.android.material.appbar.AppBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeHeaderCollapserTest {

    private fun activity(hinge: Boolean): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE, hinge)
        return activity
    }

    private fun assertCollapsibleStructure(layout: Int, bodyId: Int) {
        
        val context = ContextThemeWrapper(activity(hinge = true), com.difft.android.base.R.style.Theme_Chative)
        val root = LayoutInflater.from(context).inflate(layout, null)
        val appBar = root.findViewById<AppBarLayout>(R.id.home_app_bar)
        val header = root.findViewById<View>(R.id.home_header)
        assertTrue("the title row is the first (collapsible) app bar child", appBar.getChildAt(0) === header)
        val body = root.findViewById<View>(bodyId).let { if (it.layoutParams is CoordinatorLayout.LayoutParams) it else it.parent as View }
        val behavior = (body.layoutParams as CoordinatorLayout.LayoutParams).behavior
        assertTrue("the list body scrolls with the app bar", behavior is AppBarLayout.ScrollingViewBehavior)

        HomeHeaderCollapser.bind(appBar, header, enabled = true)
        assertEquals(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL, (header.layoutParams as AppBarLayout.LayoutParams).scrollFlags)
        val appBarBehavior = (appBar.layoutParams as CoordinatorLayout.LayoutParams).behavior as AppBarLayout.Behavior
        
        val canDragView = appBarBehavior.javaClass.superclass.getDeclaredMethod("canDragView", AppBarLayout::class.java)
            .apply { isAccessible = true }
        assertFalse(
            "taps on the title and its buttons are never turned into an app bar drag",
            canDragView.invoke(appBarBehavior, appBar) as Boolean,
        )

        HomeHeaderCollapser.bind(appBar, header, enabled = false)
        assertEquals("disabled keeps a fixed header", 0, (header.layoutParams as AppBarLayout.LayoutParams).scrollFlags)
        assertEquals(0, HomeHeaderCollapser.collapseRangePx(header, enabled = false))
        assertEquals(header.layoutParams.height, HomeHeaderCollapser.collapseRangePx(header, enabled = true))
    }

    @Test
    fun `chats title row collapses through the app bar`() =
        assertCollapsibleStructure(R.layout.chat_fragment_recent_chat, R.id.recycler_view_recent_message)

    @Test
    fun `Compose contacts retains the home title eligibility rule`() {
        val host = activity(hinge = true)
        assertEquals(HomeHeaderCollapser.isEligible(host),
            com.difft.android.chat.contacts.compose.contactsTitleCanCollapse(
                host.resources.configuration.screenHeightDp, host.isInMultiWindowMode))
    }

    @Test
    @Config(qualifiers = "w400dp-h620dp")
    fun `a foldable's folded cover screen collapses its title`() {
        assertTrue(HomeHeaderCollapser.isEligible(activity(hinge = true)))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `an unfolded foldable keeps its title`() {
        assertFalse(HomeHeaderCollapser.isEligible(activity(hinge = true)))
    }

    @Test
    @Config(qualifiers = "w400dp-h700dp")
    fun `a window at the threshold height keeps its title`() {
        assertFalse(HomeHeaderCollapser.isEligible(activity(hinge = true)))
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `a short phone without a hinge collapses its title too`() {
        assertTrue(HomeHeaderCollapser.isEligible(activity(hinge = false)))
    }

    @Test
    @Config(qualifiers = "w400dp-h860dp")
    fun `a tall phone keeps its title`() {
        assertFalse(HomeHeaderCollapser.isEligible(activity(hinge = false)))
    }
}
