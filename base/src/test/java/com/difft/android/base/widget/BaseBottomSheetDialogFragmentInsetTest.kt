package com.difft.android.base.widget

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaseBottomSheetDialogFragmentInsetTest {

    class TestSheet : BaseBottomSheetDialogFragment() {
        override fun getContentLayoutResId(): Int = android.R.layout.simple_list_item_1
    }

    @Test
    fun `the default container is a floating card inset from the sheet edges`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        val fragment = TestSheet()
        fragment.show(activity.supportFragmentManager, "sheet")
        shadowOf(Looper.getMainLooper()).idle()

        val card = fragment.requireView()
        val inset = activity.resources.getDimensionPixelSize(R.dimen.bottom_sheet_inset)
        val margins = card.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(inset, margins.leftMargin)
        assertEquals(inset, margins.rightMargin)
        assertEquals(inset, margins.bottomMargin)
        assertTrue(card.background is FloatingSurfaceDrawable)
        assertTrue(card.clipToOutline)
        val handle = card.findViewById<View>(R.id.drag_handle)
        assertEquals((8 * activity.resources.displayMetrics.density).toInt(), (handle.layoutParams as ViewGroup.MarginLayoutParams).topMargin)

        controller.destroy()
    }
}
