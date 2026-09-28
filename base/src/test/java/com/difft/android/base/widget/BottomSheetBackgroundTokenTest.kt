package com.difft.android.base.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the View-side bottom-sheet container to the design token `bg.popup` in both themes
 * (issue #1201). The container once used `bg`, which is one step darker than every Compose
 * sheet in dark mode; keep both sides on the same token.
 */
@RunWith(RobolectricTestRunner::class)
class BottomSheetBackgroundTokenTest {

    @Test
    @Config(qualifiers = "notnight")
    fun `light sheet background is bg_popup`() = assertSheetBackgroundIsPopupToken()

    @Test
    @Config(qualifiers = "night")
    fun `dark sheet background is bg_popup`() = assertSheetBackgroundIsPopupToken()

    private fun assertSheetBackgroundIsPopupToken() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheet = ContextCompat.getDrawable(context, R.drawable.base_bg_bottom_sheet) as GradientDrawable
        val expected = ContextCompat.getColor(context, R.color.bg_popup)

        assertEquals(expected, sheet.color!!.defaultColor)
    }
}
