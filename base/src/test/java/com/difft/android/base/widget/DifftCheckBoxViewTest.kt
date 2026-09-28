package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.CheckBox
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DifftCheckBoxView]-specific behaviour (issue #1203): wrap_content keeps the AppCompat 32dp
 * footprint while an explicit size wins. The shared [DifftToggleView] contract comes from
 * [DifftToggleViewContractTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftCheckBoxViewTest : DifftToggleViewContractTest() {

    override fun create(context: Context, attrs: AttributeSet?): DifftToggleView =
        DifftCheckBoxView(context, attrs)

    override val expectedAccessibilityClassName: String = CheckBox::class.java.name

    @Test
    fun `wrap_content keeps the 32dp AppCompat footprint and an explicit size wins`() {
        val activity = host()
        val density = activity.resources.displayMetrics.density
        val view = create(activity)
        mount(view, activity)

        view.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        assertEquals((32 * density).toInt(), view.measuredWidth)
        assertEquals((32 * density).toInt(), view.measuredHeight)

        val px24 = (24 * density).toInt()
        view.measure(MeasureSpec.makeMeasureSpec(px24, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(px24, MeasureSpec.EXACTLY))
        assertEquals(px24, view.measuredWidth)
        assertEquals(px24, view.measuredHeight)

        // wrap_content inside a real parent arrives as AT_MOST: must stay 32dp, never fill the parent.
        val px1000 = (1000 * density).toInt()
        view.measure(MeasureSpec.makeMeasureSpec(px1000, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(px1000, MeasureSpec.AT_MOST))
        assertEquals((32 * density).toInt(), view.measuredWidth)
        assertEquals((32 * density).toInt(), view.measuredHeight)
    }
}
