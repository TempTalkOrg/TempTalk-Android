package com.difft.android.dualpane

import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Pins how the real `layout-w673dp-h480dp/activity_index.xml` reacts to the rail being hidden
 * the way [DualPaneRailController] hides it (`visibility = GONE`, `layoutParams` untouched):
 * the list pane slides to the window edge at its old width and the detail pane absorbs the
 * rail + rail-divider width. If a constraint change ever breaks this, the "list width does not
 * change on collapse" rule of the design goes with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w859dp-h608dp")
class DualPaneRailLayoutTest {

    @Test
    fun `hiding the rail keeps the list width and widens the detail pane by the rail`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setContentView(R.layout.activity_index)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val rail = requireNotNull(activity.findViewById<View>(R.id.navigation_rail_container))
        val railDivider = requireNotNull(activity.findViewById<View>(R.id.divider_rail))
        val list = requireNotNull(activity.findViewById<View>(R.id.list_pane))
        val detail = requireNotNull(activity.findViewById<View>(R.id.detail_pane))

        val metrics = activity.resources.displayMetrics
        fun layout() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

        layout()
        val railPx = rail.width + railDivider.width
        val listExpanded = list.width
        val detailExpanded = detail.width
        assertTrue("precondition: the rail occupies width in the expanded state", railPx > 0)
        assertEquals("precondition: the list starts after the rail", railPx, list.left)

        rail.visibility = View.GONE
        railDivider.visibility = View.GONE
        layout()

        assertEquals("the list pane must slide to the window edge", 0, list.left)
        assertEquals("the list pane width must not change when the rail collapses", listExpanded, list.width)
        assertEquals("the detail pane absorbs exactly the rail + divider width", detailExpanded + railPx, detail.width)
    }
}
