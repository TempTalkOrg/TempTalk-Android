package com.difft.android.base.widget

import android.content.pm.ApplicationInfo
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
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






@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FloatingTopBarTest {

    private val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private fun px(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    private fun row(child: View): FrameLayout = FrameLayout(activity).apply {
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(this, ViewGroup.LayoutParams(px(400), px(56)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `text button becomes a 44dp capsule with 16dp padding at the trailing edge`() {
        val button = TextView(activity).apply { text = "Done" }
        val parent = row(button)

        button.floatAsTopBarTextButton(leading = false)
        parent.measure(
            MeasureSpec.makeMeasureSpec(px(400), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(px(56), MeasureSpec.EXACTLY),
        )

        val params = button.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(px(44), button.measuredHeight)
        assertTrue(button.measuredWidth >= px(44))
        assertEquals(px(16), button.paddingStart)
        assertEquals(px(16), button.paddingEnd)
        assertEquals(px(16), params.marginEnd)
        assertTrue(button.background != null)
        assertTrue(button.elevation > 0f)
        
        assertFalse(parent.clipChildren)
    }

    @Test
    fun `leading icon button is a 44dp circle at its start with a 12dp gap before the title`() {
        val button = ImageView(activity)
        val parent = row(button)
        button.layoutParams = button.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }

        button.floatAsTopBarButton(leading = true)
        parent.measure(
            MeasureSpec.makeMeasureSpec(px(400), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(px(56), MeasureSpec.EXACTLY),
        )

        val params = button.layoutParams as ViewGroup.MarginLayoutParams
        
        assertEquals(px(56), button.measuredWidth)
        assertEquals(px(56), button.measuredHeight)
        assertEquals(px(16), params.marginStart)
        assertTrue(button.background is android.graphics.drawable.InsetDrawable)
        
        assertEquals(px(10), button.paddingStart)
        assertEquals(px(22), button.paddingEnd)
        
        assertFalse(parent.clipChildren)
    }

    @Test
    fun `a leading button styled before it is attached keeps its title gap on the RTL end`() {
        
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val button = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(56))
        }
        button.floatAsTopBarButton(leading = true)

        val parent = FrameLayout(activity).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(button)
        }
        activity.setContentView(parent, ViewGroup.LayoutParams(px(400), px(56)))
        shadowOf(Looper.getMainLooper()).idle()

        val inset = android.graphics.Rect().also { assertTrue(button.background.getPadding(it)) }
        
        assertEquals(px(12), inset.left)
        assertEquals(0, inset.right)
        
        assertEquals(px(10), button.paddingRight)
        assertEquals(px(22), button.paddingLeft)
    }
}
