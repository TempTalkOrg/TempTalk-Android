package com.difft.android.base.widget

import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A sheet that dismisses itself while its view is created (e.g. a restored confidential sheet
 * without its message, #1440) is detached before the posted onShow callback runs. The listener
 * must skip the configuration instead of calling `requireActivity()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaseBottomSheetDialogFragmentDetachTest {

    class SelfDismissingSheet : BaseBottomSheetDialogFragment() {
        override fun getContentLayoutResId(): Int = android.R.layout.simple_list_item_1

        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
            dismissAllowingStateLoss()
        }
    }

    @Test
    fun `sheet dismissed during view creation does not crash when onShow runs after detach`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)

        val fragment = SelfDismissingSheet()
        fragment.show(activity.supportFragmentManager, "sheet")
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(fragment.isAdded)
        assertNull(activity.supportFragmentManager.findFragmentByTag("sheet"))

        controller.destroy()
    }
}
