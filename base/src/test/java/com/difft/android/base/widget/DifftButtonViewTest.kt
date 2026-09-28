package com.difft.android.base.widget

import android.os.Looper
import android.view.View.MeasureSpec
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DifftButtonDefaults
import com.difft.android.base.ui.compose.DifftButtonSize
import com.difft.android.base.ui.compose.DifftButtonVariant
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * [DifftButtonView] contract (issue #1208): the shell is a drop-in for the `Button` /
 * `AppCompatButton` tags it replaces — same click and enabled surface — it measures the way every
 * migrated layout needs (an EXACTLY `layout_height` fills the box, a wrap_content one collapses to
 * the design height), and the View, not Compose, owns the click and the accessibility node.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftButtonViewTest {

    private fun host(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun mount(view: DifftButtonView, activity: ComponentActivity) {
        activity.setContentView(FrameLayout(activity).apply { addView(view) })
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ComponentActivity.px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun labelled(activity: ComponentActivity, text: String = "Done"): DifftButtonView =
        DifftButtonView(activity, Robolectric.buildAttributeSet().addAttribute(android.R.attr.text, text).build())

    // ---------- T11 / T12 / T13: measurement ----------

    @Test
    fun `an exact layout height fills the whole View box`() {
        val activity = host()
        val view = labelled(activity)
        mount(view, activity)

        view.measure(
            MeasureSpec.makeMeasureSpec(activity.px(200), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(activity.px(48), MeasureSpec.EXACTLY),
        )

        assertEquals(activity.px(200), view.measuredWidth)
        assertEquals(activity.px(48), view.measuredHeight)
    }

    @Test
    fun `wrap_content collapses to the design height and never fills the parent`() {
        val activity = host()
        val view = labelled(activity)
        mount(view, activity)

        // wrap_content inside a real parent arrives as AT_MOST: must not expand to it.
        val atMost = MeasureSpec.makeMeasureSpec(activity.px(1000), MeasureSpec.AT_MOST)
        view.measure(atMost, atMost)
        assertEquals(activity.px(48), view.measuredHeight)
        assertTrue("wrap_content width must hug the label", view.measuredWidth < activity.px(1000))
        assertTrue("the label plus 16dp side padding must fit", view.measuredWidth > activity.px(48))

        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        assertEquals(activity.px(48), view.measuredHeight)
    }

    @Test
    fun `the medium size honours an exact row height`() {
        val activity = host()
        val view = DifftButtonView(
            activity,
            Robolectric.buildAttributeSet()
                .addAttribute(android.R.attr.text, "Accept")
                .addAttribute(R.attr.difft_buttonSize, "medium")
                .build(),
        )
        mount(view, activity)

        view.measure(
            MeasureSpec.makeMeasureSpec(activity.px(100), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(activity.px(40), MeasureSpec.EXACTLY),
        )

        assertEquals(DifftButtonSize.M, view.size)
        assertEquals(activity.px(40), view.measuredHeight)
    }

    // ---------- T14 / T15: attributes ----------

    @Test
    fun `xml attributes land on the shell`() {
        val activity = host()
        val view = DifftButtonView(
            activity,
            Robolectric.buildAttributeSet()
                .addAttribute(android.R.attr.text, "Done")
                .addAttribute(android.R.attr.enabled, "false")
                .addAttribute(R.attr.difft_buttonSize, "small")
                .addAttribute(R.attr.difft_forceDark, "true")
                .build(),
        )

        assertEquals("Done", view.text)
        assertFalse(view.isEnabled)
        assertEquals(DifftButtonSize.S, view.size)
        assertTrue(view.forceDark)
    }

    @Test
    fun `android enabled reaches both the View flag and the composition`() {
        val activity = host()
        val view = DifftButtonView(
            activity,
            Robolectric.buildAttributeSet().addAttribute(android.R.attr.enabled, "false").build(),
        )
        mount(view, activity)

        // The framework does not apply android:enabled to a plain View, so the shell must route it
        // through setEnabled — which is also what keeps the Compose state in step.
        assertFalse(view.isEnabled)
        view.isEnabled = true
        assertTrue(view.isEnabled)
        assertTrue(DifftButtonView(activity).isEnabled)
    }

    /** A row that owns the click turns the button's own click surface off from the layout. */
    @Test
    fun `android clickable and focusable false reach the View`() {
        val activity = host()
        val view = DifftButtonView(
            activity,
            Robolectric.buildAttributeSet()
                .addAttribute(android.R.attr.text, "Accept")
                .addAttribute(android.R.attr.clickable, "false")
                .addAttribute(android.R.attr.focusable, "false")
                .build(),
        )
        mount(view, activity)

        assertFalse(view.isClickable)
        assertFalse(view.isFocusable)
    }

    /** The 48dp page CTA every migrated layout hosts, so a tag without the attribute keeps it. */
    @Test
    fun `the default size is xl`() {
        assertEquals(DifftButtonSize.XL, DifftButtonView(host()).size)
    }

    @Test
    fun `the size and variant enums map onto the library scale`() {
        val activity = host()
        fun sized(value: String) = DifftButtonView(
            activity,
            Robolectric.buildAttributeSet().addAttribute(R.attr.difft_buttonSize, value).build(),
        ).size

        assertEquals(DifftButtonSize.S, sized("small"))
        assertEquals(DifftButtonSize.M, sized("medium"))
        assertEquals(DifftButtonSize.L, sized("large"))
        assertEquals(DifftButtonSize.XL, sized("xl"))

        fun variant(value: String) = DifftButtonView(
            activity,
            Robolectric.buildAttributeSet().addAttribute(R.attr.difft_buttonVariant, value).build(),
        ).variant

        assertEquals(DifftButtonVariant.Primary, variant("primary"))
        assertEquals(DifftButtonVariant.Secondary, variant("secondary"))
        assertEquals(DifftButtonVariant.Danger, variant("danger"))
        assertEquals(DifftButtonVariant.Success, variant("success"))
        assertEquals(DifftButtonVariant.Text, variant("text"))
    }

    // ---------- T16 / T17: click forwarding ----------

    @Test
    fun `a click reaches the listener exactly once and the shell is clickable by default`() {
        val activity = host()
        val view = labelled(activity)
        var clicks = 0
        view.setOnClickListener { clicks++ }
        mount(view, activity)

        assertTrue(view.isClickable)
        assertTrue(view.isFocusable)
        assertTrue(view.performClick())
        assertEquals(1, clicks)
    }

    @Test
    fun `a disabled shell swallows the click`() {
        val activity = host()
        val view = labelled(activity)
        var clicks = 0
        view.setOnClickListener { clicks++ }
        mount(view, activity)

        view.isEnabled = false
        assertFalse(view.performClick())

        assertEquals(0, clicks)
    }

    @Test
    fun `a second click inside the debounce window is dropped and later clicks are not`() {
        val activity = host()
        val view = labelled(activity)
        var clicks = 0
        view.setOnClickListener { clicks++ }
        mount(view, activity)

        view.performClick()
        view.performClick()
        assertEquals(1, clicks)

        ShadowSystemClock.advanceBy(Duration.ofMillis(DifftButtonDefaults.ClickDebounceMillis - 1))
        view.performClick()
        assertEquals(1, clicks)

        ShadowSystemClock.advanceBy(Duration.ofMillis(2))
        view.performClick()
        assertEquals(2, clicks)
    }

    // ---------- T18 / T19: accessibility and the text setter ----------

    @Test
    fun `TalkBack sees one Button node named by the label`() {
        val activity = host()
        val view = labelled(activity, "Log In")
        mount(view, activity)

        val node: AccessibilityNodeInfo = view.createAccessibilityNodeInfo()!!

        assertEquals(Button::class.java.name, node.className)
        assertEquals("Log In", node.contentDescription)
    }

    @Test
    fun `the text setter updates the label and its accessibility name`() {
        val activity = host()
        val view = labelled(activity, "Next")
        mount(view, activity)

        view.text = "Save"

        assertEquals("Save", view.text)
        assertEquals("Save", view.contentDescription)
    }

    @Test
    fun `an explicit content description survives a label change`() {
        val activity = host()
        val view = labelled(activity, "Next")
        view.contentDescription = "Continue to the next step"
        mount(view, activity)

        view.text = "Save"

        assertEquals("Save", view.text)
        assertEquals("Continue to the next step", view.contentDescription)
    }

    // ---------- T20: a GONE button costs no composition ----------

    @Test
    fun `a gone button does not compose on attach`() {
        val activity = host()
        val view = labelled(activity)
        view.visibility = android.view.View.GONE
        mount(view, activity)

        assertEquals(0, view.childCount)

        view.visibility = android.view.View.VISIBLE
        view.measure(
            MeasureSpec.makeMeasureSpec(activity.px(200), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(activity.px(48), MeasureSpec.EXACTLY),
        )
        assertEquals(1, view.childCount)
    }
}
