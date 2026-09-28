package com.difft.android.base.widget

import android.os.Looper
import android.view.View.MeasureSpec
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.difft.android.base.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DetailCloseButtonView] contract: the shell measures to the shared 48dp disc when given
 * wrap_content, the View owns the click and the accessibility node (button role, "Close" label), and
 * the drop shadow is View elevation so a parent can draw it outside the composition bounds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DetailCloseButtonViewTest {

    private fun host(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun mount(view: DetailCloseButtonView, activity: ComponentActivity) {
        activity.setContentView(FrameLayout(activity).apply { addView(view) })
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ComponentActivity.px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    @Test
    fun `wrap_content measures to the shared disc size`() {
        val activity = host()
        val view = DetailCloseButtonView(activity)
        mount(view, activity)

        val atMost = MeasureSpec.makeMeasureSpec(activity.px(1000), MeasureSpec.AT_MOST)
        view.measure(atMost, atMost)

        assertEquals(activity.px(48), view.measuredWidth)
        assertEquals(activity.px(48), view.measuredHeight)
    }

    @Test
    fun `the View owns the click and reports itself as a button`() {
        val activity = host()
        val view = DetailCloseButtonView(activity)
        mount(view, activity)
        var clicks = 0
        view.setOnClickListener { clicks++ }

        assertTrue(view.isClickable)
        assertTrue(view.isFocusable)
        assertTrue(view.performClick())
        assertEquals(1, clicks)
        assertEquals(Button::class.java.name, view.accessibilityClassName)
        assertEquals(activity.getString(R.string.base_close), view.contentDescription)
    }

    @Test
    fun `an xml content description is kept and the shadow is View elevation`() {
        val activity = host()
        val attrs = Robolectric.buildAttributeSet()
            .addAttribute(android.R.attr.contentDescription, "Dismiss preview")
            .build()
        val view = DetailCloseButtonView(activity, attrs)

        assertEquals("Dismiss preview", view.contentDescription)
        assertEquals(activity.resources.getDimension(R.dimen.detail_close_button_elevation), view.elevation)
    }

    @Test
    fun `the content inset clears the button plus its margin on both sides`() {
        val res = host().resources
        val size = res.getDimension(R.dimen.detail_close_button_size)
        val margin = res.getDimension(R.dimen.detail_close_button_margin)

        // XML dimens cannot express arithmetic; pin the relation the layouts rely on.
        assertEquals(size + margin * 2, res.getDimension(R.dimen.detail_close_button_content_inset), 0.5f)
    }

    @Test
    fun `android clickable and focusable false reach the View`() {
        val activity = host()
        val attrs = Robolectric.buildAttributeSet()
            .addAttribute(android.R.attr.clickable, "false")
            .addAttribute(android.R.attr.focusable, "false")
            .build()
        val view = DetailCloseButtonView(activity, attrs)

        assertFalse(view.isClickable)
        assertFalse(view.isFocusable)
    }
}
