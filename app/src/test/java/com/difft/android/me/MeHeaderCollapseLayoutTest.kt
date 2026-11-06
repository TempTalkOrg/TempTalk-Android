package com.difft.android.me

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.difft.android.R
import com.difft.android.chat.common.HomeHeaderCollapser
import com.google.android.material.appbar.AppBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Me page's title row collapses like the Chats and Contacts titles: the real layout carries it
 * as the first AppBarLayout child above a nested-scrolling body that scrolls with the app bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MeHeaderCollapseLayoutTest {

    @Test
    fun `me title row collapses through the app bar with a nested-scrolling body`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, com.difft.android.base.R.style.Theme_Chative)
        val root = LayoutInflater.from(context).inflate(R.layout.me_fragment, null)
        val appBar = root.findViewById<AppBarLayout>(R.id.home_app_bar)
        val header = root.findViewById<View>(R.id.home_header)
        assertTrue("the title row is the first (collapsible) app bar child", appBar.getChildAt(0) === header)

        val body = root.findViewById<View>(R.id.scrollView2)
        assertTrue("a plain ScrollView never dispatches nested scroll to the coordinator", body is NestedScrollView)
        assertTrue(
            "the body scrolls with the app bar",
            (body.layoutParams as CoordinatorLayout.LayoutParams).behavior is AppBarLayout.ScrollingViewBehavior,
        )

        HomeHeaderCollapser.bind(appBar, header, enabled = true)
        assertEquals(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL, (header.layoutParams as AppBarLayout.LayoutParams).scrollFlags)
        HomeHeaderCollapser.bind(appBar, header, enabled = false)
        assertEquals("disabled keeps a fixed header", 0, (header.layoutParams as AppBarLayout.LayoutParams).scrollFlags)
    }
}
