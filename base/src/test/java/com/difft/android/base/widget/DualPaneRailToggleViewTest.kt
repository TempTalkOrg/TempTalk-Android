package com.difft.android.base.widget

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.R
import com.difft.android.base.utils.DualPaneHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Host double: dual-pane on/off, a toggle counter and a controllable collapsed flow. */
class RailToggleHostActivity : FragmentActivity(), DualPaneHost {
    var dualPane = true
    var toggles = 0
    val collapsed = MutableStateFlow(false)
    override val isDualPaneMode: Boolean get() = dualPane
    override val navigationRailCollapsed: StateFlow<Boolean> get() = collapsed
    override fun toggleNavigationRail() { toggles++ }
    override fun showDetailFragment(fragment: Fragment, tag: String?) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DualPaneRailToggleViewTest {

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    /** Creates the host, attaches the toggle as its content, and drives it to RESUMED. */
    private fun attach(dualPane: Boolean): Pair<RailToggleHostActivity, DualPaneRailToggleView> {
        val controller = Robolectric.buildActivity(RailToggleHostActivity::class.java).create()
        val activity = controller.get()
        activity.dualPane = dualPane
        val view = DualPaneRailToggleView(activity).apply { visibility = View.GONE }
        activity.setContentView(view)
        controller.start().resume().visible()
        idle()
        return activity to view
    }

    @Test
    fun `dual-pane host - visible, taps toggle the rail, label follows the collapsed state`() {
        val (activity, view) = attach(dualPane = true)
        assertEquals("the toggle belongs to the dual-pane header only", View.VISIBLE, view.visibility)

        view.performClick()
        assertEquals("a tap must reach DualPaneHost.toggleNavigationRail", 1, activity.toggles)

        assertEquals(
            activity.getString(R.string.dual_pane_rail_collapse_hint),
            view.contentDescription.toString(),
        )
        activity.collapsed.value = true
        idle()
        assertEquals(
            "once the rail is collapsed the same button announces expanding it",
            activity.getString(R.string.dual_pane_rail_expand_hint),
            view.contentDescription.toString(),
        )
    }

    @Test
    fun `single-pane host - the toggle hides itself`() {
        val (_, view) = attach(dualPane = false)
        assertEquals("phones have bottom tabs, no rail: the header must not show a rail toggle", View.GONE, view.visibility)
    }
}
