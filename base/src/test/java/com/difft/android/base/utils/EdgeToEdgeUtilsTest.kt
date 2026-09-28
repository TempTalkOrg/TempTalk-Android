package com.difft.android.base.utils

import android.content.Context
import android.view.View
import androidx.core.graphics.Insets
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
        const val LEFT_PADDING = 4
        const val TOP_PADDING = 12
        const val RIGHT_PADDING = 6
    }
}
