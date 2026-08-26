package com.difft.android.chat.ui.popup

import android.app.Activity
import android.view.View
import android.view.View.MeasureSpec
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FA1, FA2 — Framework Assumption Tests against the real Material [BottomSheetBehavior], no mocks.
 *
 * The entire lift model assumes that writing the sheet child's `layoutParams.height` is SUFFICIENT
 * to move the sheet's top edge — no state change, no `setExpandedOffset`. That is a property of
 * `isFitToContents = true`, not of this project's code, so it is pinned here. FA2 pins the second
 * assumption: `peekHeight` is written on every geometry pass and must never trigger a settle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BottomSheetLiftFrameworkAssumptionTest {

    private companion object {
        const val PARENT_WIDTH = 1000
        const val PARENT_HEIGHT = 2000
    }

    private class Sheet(
        val parent: CoordinatorLayout,
        val child: View,
        val behavior: BottomSheetBehavior<View>,
    )

    private fun buildSheet(childHeight: Int): Sheet {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = CoordinatorLayout(activity)
        val child = View(activity)
        val behavior = BottomSheetBehavior<View>().apply {
            isFitToContents = true
            isHideable = true
            skipCollapsed = true
            peekHeight = childHeight
        }
        val params = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            childHeight
        ).apply { setBehavior(behavior) }
        parent.addView(child, params)
        activity.setContentView(parent)
        layout(parent)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        layout(parent)
        return Sheet(parent, child, behavior)
    }

    private fun layout(parent: CoordinatorLayout) {
        parent.measure(
            MeasureSpec.makeMeasureSpec(PARENT_WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(PARENT_HEIGHT, MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, PARENT_WIDTH, PARENT_HEIGHT)
    }

    /** FA1 — growing the child's height alone lifts the sheet's top edge by the same amount. */
    @Test
    fun `FA1 changing only the child height moves the expanded sheet top edge`() {
        val sheet = buildSheet(childHeight = 600)

        assertEquals(PARENT_HEIGHT - 600, sheet.child.top)

        sheet.child.layoutParams = sheet.child.layoutParams.apply { height = 900 }
        sheet.behavior.peekHeight = 900
        layout(sheet.parent)

        assertEquals(PARENT_HEIGHT - 900, sheet.child.top)
    }

    /** FA2 — writing peekHeight while expanded must not change the state or start a settle. */
    @Test
    fun `FA2 setting peekHeight while expanded does not change the sheet state`() {
        val sheet = buildSheet(childHeight = 600)

        sheet.behavior.peekHeight = 1400

        assertEquals(BottomSheetBehavior.STATE_EXPANDED, sheet.behavior.state)
    }
}
