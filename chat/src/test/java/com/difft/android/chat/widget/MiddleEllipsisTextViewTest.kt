package com.difft.android.chat.widget

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies the measure-time middle-ellipsize contract of [MiddleEllipsisTextView]:
 * overflowing file names keep their tail (extension) visible, fitting names pass
 * through untouched, and the result is stable across repeated measure passes
 * (the no-flicker guarantee relies on convergence within one measure).
 *
 * [GraphicsMode.Mode.NATIVE] is required for real text measurement — legacy shadows
 * report ~1px per character, so nothing would ever overflow.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiddleEllipsisTextViewTest {

    private lateinit var view: MiddleEllipsisTextView

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        view = MiddleEllipsisTextView(activity).apply {
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            // TextView.checkForRelayout dereferences layoutParams on setText; a view in a
            // real hierarchy always has one
            layoutParams = ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun measure(widthPx: Int = 400) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }

    private fun rendersFully(): Boolean {
        val layout = view.layout ?: return false
        if (layout.lineCount > 2) return false
        val last = layout.lineCount - 1
        return layout.getEllipsisCount(last) == 0 && layout.getLineEnd(last) >= view.text.length
    }

    @Test
    fun `short name is not modified`() {
        view.text = "report.pdf"
        measure()
        assertEquals("report.pdf", view.text.toString())
        assertTrue(rendersFully())
    }

    @Test
    fun `overflowing name is middle-ellipsized keeping the tail`() {
        val full = "Quarterly Financial Report 2026 Final Reviewed Version With Appendix.pdf"
        view.text = full
        measure(220)
        val shown = view.text.toString()
        assertTrue("expected middle ellipsis in: $shown", shown.contains("…"))
        assertTrue("tail (extension) must stay visible: $shown", shown.endsWith(".pdf"))
        assertTrue("result must fully render within two lines", rendersFully())

        // The ellipsis lands near the visual center of the last line
        val layout = view.layout!!
        val ellipsisX = layout.getPrimaryHorizontal(shown.indexOf("…"))
        assertTrue(
            "ellipsis at x=$ellipsisX should be near the center of the 220px line",
            ellipsisX > 220 * 0.3f && ellipsisX < 220 * 0.7f
        )
    }

    @Test
    fun `result is stable across repeated measures`() {
        view.text = "Quarterly Financial Report 2026 Final Reviewed Version With Appendix.pdf"
        measure(220)
        val first = view.text.toString()
        measure(220)
        measure(220)
        assertEquals(first, view.text.toString())
    }

    @Test
    fun `rebinding a recycled view recomputes from the new full text`() {
        view.text = "Quarterly Financial Report 2026 Final Reviewed Version With Appendix.pdf"
        measure(220)
        assertTrue(view.text.toString().contains("…"))

        view.text = "notes.txt"
        measure()
        assertEquals("notes.txt", view.text.toString())
    }

    @Test
    fun `surrogate pairs are never split at the cut points`() {
        val full = "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀 archive.zip"
        view.text = full
        measure(200)
        val shown = view.text.toString()
        // Every char must pair correctly — a split surrogate would break the sequence
        var i = 0
        while (i < shown.length) {
            val c = shown[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("dangling high surrogate at $i in: $shown", i + 1 < shown.length && Character.isLowSurrogate(shown[i + 1]))
                i += 2
            } else {
                assertTrue("dangling low surrogate at $i in: $shown", !Character.isLowSurrogate(c))
                i++
            }
        }
    }
}
