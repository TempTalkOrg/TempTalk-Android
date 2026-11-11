package com.difft.android.base.widget

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w860dp-h600dp-land")
class BaseBottomSheetDialogFragmentExpandTest {

    class TestSheet : BaseBottomSheetDialogFragment() {
        override fun getContentLayoutResId(): Int = android.R.layout.simple_list_item_1
    }

    @Test
    fun `content-height sheet opens expanded and follows content that grows after show`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)

        val fragment = TestSheet()
        fragment.showNow(activity.supportFragmentManager, "sheet")

        
        val dialog = fragment.dialog as BottomSheetDialog
        assertEquals(BottomSheetBehavior.STATE_EXPANDED, dialog.behavior.state)
        shadowOf(Looper.getMainLooper()).idle()

        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
        val content = dialog.findViewById<TextView>(android.R.id.text1)!!
        content.minHeight = (sheet.parent as View).height / 2
        shadowOf(Looper.getMainLooper()).idle()

        val parentHeight = (sheet.parent as View).height
        assertEquals(BottomSheetBehavior.STATE_EXPANDED, dialog.behavior.state)
        assertEquals(parentHeight - sheet.height, sheet.top)

        controller.destroy()
    }
}
