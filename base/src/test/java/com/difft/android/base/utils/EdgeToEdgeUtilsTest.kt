package com.difft.android.base.utils

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Pins the additive contract of [EdgeToEdgeUtils.applyBottomInsetAsPadding]: the home tab bar
 * carries its own bottom padding (`activity_index.xml`), so the navigation-bar inset must be
 * added to it, and repeated inset dispatches must not stack on top of each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EdgeToEdgeUtilsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `bottom inset is added to the view's own bottom padding`() {
        val view = viewWithPadding(bottom = BASE_PADDING)

        EdgeToEdgeUtils.applyBottomInsetAsPadding(view)
        dispatchBottomInset(view, INSET)

        assertEquals(BASE_PADDING + INSET, view.paddingBottom)
    }

    @Test
    fun `repeated dispatches do not accumulate`() {
        val view = viewWithPadding(bottom = BASE_PADDING)

        EdgeToEdgeUtils.applyBottomInsetAsPadding(view)
        dispatchBottomInset(view, INSET)
        dispatchBottomInset(view, INSET)

        assertEquals(BASE_PADDING + INSET, view.paddingBottom)
    }

    @Test
    fun `other edges stay untouched`() {
        val view = viewWithPadding(
            left = LEFT_PADDING,
            top = TOP_PADDING,
            right = RIGHT_PADDING,
            bottom = BASE_PADDING,
        )

        EdgeToEdgeUtils.applyBottomInsetAsPadding(view)
        dispatchBottomInset(view, INSET)

        assertEquals(LEFT_PADDING, view.paddingLeft)
        assertEquals(TOP_PADDING, view.paddingTop)
        assertEquals(RIGHT_PADDING, view.paddingRight)
        assertEquals(BASE_PADDING + INSET, view.paddingBottom)
    }

    @Test
    fun `a side cutout pads the content only when its hole reaches below the status strip`() {
        // Unfolded foldable in landscape: the hole sits in the top-left corner, inside the strip.
        val cornerHole = viewWithPadding()
        EdgeToEdgeUtils.applySystemBarsPadding(cornerHole, applyHorizontal = true)
        dispatchSideInsets(cornerHole, holeTop = TOP_PADDING / 2, barRight = INSET)
        assertEquals(0, cornerHole.paddingLeft, "a hole inside the status strip must not push the content column")
        assertEquals(INSET, cornerHole.paddingRight, "a side navigation bar is still respected")
        assertEquals(TOP_PADDING, cornerHole.paddingTop, "top keeps the full status strip")

        // Bar phone in landscape: the hole sits mid-height on the long edge, over the content.
        val edgeHole = viewWithPadding()
        EdgeToEdgeUtils.applySystemBarsPadding(edgeHole, applyHorizontal = true)
        dispatchSideInsets(edgeHole, holeTop = TOP_PADDING * 4, barRight = INSET)
        assertEquals(CUTOUT, edgeHole.paddingLeft, "a hole over the content keeps the cutout padding")
        assertEquals(INSET, edgeHole.paddingRight)
    }

    private fun dispatchSideInsets(view: View, holeTop: Int, barRight: Int) {
        val hole = Rect(0, holeTop, CUTOUT, holeTop + HOLE_SIZE)
        val cutout = DisplayCutoutCompat(
            Insets.of(CUTOUT, 0, 0, 0),
            hole,
            null,
            null,
            null,
            Insets.NONE,
        )
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, TOP_PADDING, barRight, 0))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT, 0, 0, 0))
            .setDisplayCutout(cutout)
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun viewWithPadding(
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): View = View(context).apply { setPadding(left, top, right, bottom) }

    private fun dispatchBottomInset(view: View, bottom: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, bottom))
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private companion object {
        const val BASE_PADDING = 24
        const val INSET = 48
        const val CUTOUT = 36
        const val HOLE_SIZE = 20
        const val LEFT_PADDING = 4
        const val TOP_PADDING = 12
        const val RIGHT_PADDING = 6
    }
}
