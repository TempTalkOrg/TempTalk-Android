package com.difft.android.base.widget

import android.os.Looper
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Wide-screen contract for [BaseBottomSheetDialogFragment] (issue #1197): the 640dp cap must live
 * on [com.google.android.material.bottomsheet.BottomSheetBehavior.setMaxWidth] so the very first
 * layout pass is already capped. The previous implementation resized the sheet in `onShow`, which
 * flashed one full-width frame before snapping to 640dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class BaseBottomSheetDialogFragmentMaxWidthTest {

    class TestSheet : BaseBottomSheetDialogFragment() {
        override fun getContentLayoutResId(): Int = android.R.layout.simple_list_item_1
    }

    @Test
    fun `behavior max width equals the shared dimen and the first layout is already capped`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        val expectedPx = activity.resources.getDimensionPixelSize(R.dimen.bottom_sheet_max_width)

        val fragment = TestSheet()
        fragment.show(activity.supportFragmentManager, "sheet")
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = fragment.dialog as BottomSheetDialog
        assertEquals(expectedPx, dialog.behavior.maxWidth)

        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
        assertEquals(expectedPx, sheet.width)

        controller.destroy()
    }
}
