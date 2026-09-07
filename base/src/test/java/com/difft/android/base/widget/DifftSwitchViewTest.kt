package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.Switch
import androidx.activity.ComponentActivity
import com.difft.android.base.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DifftSwitchView]-specific behaviour (issue #1206): the two layout forms chosen by `android:text`
 * and how each measures, including View padding, which `AbstractComposeView` subtracts in onMeasure
 * and offsets in onLayout. The shared [DifftToggleView] contract comes from
 * [DifftToggleViewContractTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftSwitchViewTest : DifftToggleViewContractTest() {

    override fun create(context: Context, attrs: AttributeSet?): DifftToggleView =
        DifftSwitchView(context, attrs)

    override val expectedAccessibilityClassName: String = Switch::class.java.name

    private fun ComponentActivity.px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun labelled(activity: ComponentActivity, vararg extras: Pair<Int, String>): DifftSwitchView {
        val builder = Robolectric.buildAttributeSet().addAttribute(android.R.attr.text, "Mute")
        extras.forEach { (attr, value) -> builder.addAttribute(attr, value) }
        return DifftSwitchView(activity, builder.build())
    }

    // ---------- Measurement ----------

    @Test
    fun `the bare control is the 51 by 31 design box and an explicit size wins`() {
        val activity = host()
        val view = create(activity)
        mount(view, activity)

        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        assertEquals(activity.px(51), view.measuredWidth)
        assertEquals(activity.px(31), view.measuredHeight)

        // wrap_content inside a real parent arrives as AT_MOST: must never fill the parent.
        val atMost = MeasureSpec.makeMeasureSpec(activity.px(1000), MeasureSpec.AT_MOST)
        view.measure(atMost, atMost)
        assertEquals(activity.px(51), view.measuredWidth)
        assertEquals(activity.px(31), view.measuredHeight)

        view.measure(
            MeasureSpec.makeMeasureSpec(activity.px(60), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(activity.px(40), MeasureSpec.EXACTLY),
        )
        assertEquals(activity.px(60), view.measuredWidth)
        assertEquals(activity.px(40), view.measuredHeight)
    }

    @Test
    fun `the label row keeps View padding out of the composition`() {
        val activity = host()
        val view = labelled(
            activity,
            android.R.attr.paddingStart to "16dp",
            android.R.attr.paddingEnd to "8dp",
        )
        mount(view, activity)

        val width = activity.px(360)
        val height = activity.px(52)
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        assertEquals(width, view.measuredWidth)
        assertEquals(height, view.measuredHeight)
        val composition = view.getChildAt(0)
        assertEquals(width - activity.px(16) - activity.px(8), composition.measuredWidth)
        assertEquals(activity.px(16), composition.left)
    }

    @Test
    fun `a wrap_content label row is at least the switch height`() {
        val activity = host()
        val view = labelled(activity)
        mount(view, activity)

        view.measure(
            MeasureSpec.makeMeasureSpec(activity.px(360), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(activity.px(52), MeasureSpec.AT_MOST),
        )

        assertTrue(view.measuredHeight >= activity.px(31))
        assertTrue(view.measuredHeight <= activity.px(52))
    }

    // ---------- Label and accessibility ----------

    @Test
    fun `android text lands as the row label`() {
        val activity = host()

        assertEquals("Mute", labelled(activity).label)
        assertNull(DifftSwitchView(activity).label)
    }

    @Test
    fun `the label is the TalkBack name`() {
        val activity = host()
        val view = labelled(activity)
        mount(view, activity)

        assertEquals("Mute", view.contentDescription)
    }

    @Test
    fun `the label row reads the state attributes alongside android text`() {
        val activity = host()

        val view = labelled(
            activity,
            android.R.attr.checked to "true",
            android.R.attr.enabled to "false",
            R.attr.difft_forceDark to "true",
        )

        assertEquals("Mute", view.label)
        assertTrue(view.isChecked)
        assertFalse(view.isEnabled)
        assertTrue(view.forceDark)
    }

    @Test
    fun `the bare form keeps an xml contentDescription`() {
        val activity = host()
        val attrs = Robolectric.buildAttributeSet()
            .addAttribute(android.R.attr.contentDescription, "Blur faces")
            .build()

        val view = DifftSwitchView(activity, attrs)

        assertNull(view.label)
        assertEquals("Blur faces", view.contentDescription)
    }
}
