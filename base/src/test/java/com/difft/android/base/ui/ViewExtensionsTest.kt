package com.difft.android.base.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [noSmoothScrollToBottom] — Crashlytics issue 32a90db4.
 *
 * The offset fine-tune runs inside doOnNextLayout, i.e. inside the layout pass.
 * A synchronous scrollBy there dispatches onScrolled listeners mid-layout, which
 * can mutate the view tree while an ancestor FrameLayout is iterating its children
 * (FATAL NPE in FrameLayout.layoutChildren). The scrollBy must therefore land on a
 * posted runnable, never synchronously inside layout.
 *
 * The offset > 0 branch only fires when the last item's bottom ends up past the
 * viewport after layout. scrollToPosition's pending-position layout end-aligns the
 * target (fixLayoutEndGap), so the tests reproduce the production shape instead:
 * the last item is already visible and then grows taller than the viewport (the
 * emoji-reaction case PR #782 addressed) — the keep-visible-rect anchor leaves its
 * bottom beyond the viewport and the fine-tune must scroll it back into view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewExtensionsTest {

    private val viewportHeight = 500
    private val itemHeight = 300
    private val totalItems = 3

    private fun createRecyclerView(context: Context): RecyclerView =
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val itemView = View(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, itemHeight
                        )
                    }
                    return object : RecyclerView.ViewHolder(itemView) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

                override fun getItemCount() = totalItems
            }
        }

    private fun RecyclerView.measureAndLayout() {
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1080, viewportHeight)
    }

    /** Scrolls to bottom, then grows the last item so its bottom overflows the viewport. */
    private fun RecyclerView.scrollToBottomAndGrowLastItem() {
        scrollToPosition(totalItems - 1)
        measureAndLayout()
        val lastChild = (layoutManager as LinearLayoutManager).findViewByPosition(totalItems - 1)!!
        lastChild.layoutParams = lastChild.layoutParams.apply { height = viewportHeight + 300 }
        lastChild.requestLayout()
    }

    @Test
    fun `offset fine-tune scrollBy is never dispatched synchronously inside the layout pass`() {
        val recyclerView = createRecyclerView(ApplicationProvider.getApplicationContext())
        recyclerView.measureAndLayout()
        recyclerView.scrollToBottomAndGrowLastItem()

        var layoutReturned = false
        var scrolledDuringLayout = false
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && !layoutReturned) scrolledDuringLayout = true
            }
        })

        recyclerView.noSmoothScrollToBottom()
        // Drive the layout pass that fires the doOnNextLayout fine-tune.
        recyclerView.measureAndLayout()
        layoutReturned = true

        assertFalse(
            scrolledDuringLayout,
            "scrollBy dispatched onScrolled synchronously inside the layout pass (issue 32a90db4)"
        )
    }

    @Test
    fun `offset fine-tune still scrolls the grown last item fully into view`() {
        // Attached to a real window so the posted runnable executes on looper idle.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recyclerView = createRecyclerView(activity)
        activity.setContentView(recyclerView, ViewGroup.LayoutParams(1080, viewportHeight))
        shadowOf(Looper.getMainLooper()).idle()

        recyclerView.scrollToPosition(totalItems - 1)
        shadowOf(Looper.getMainLooper()).idle()
        val lm = recyclerView.layoutManager as LinearLayoutManager
        val lastChild = lm.findViewByPosition(totalItems - 1)!!
        lastChild.layoutParams = lastChild.layoutParams.apply { height = viewportHeight + 300 }
        lastChild.requestLayout()
        shadowOf(Looper.getMainLooper()).idle()

        recyclerView.noSmoothScrollToBottom()
        shadowOf(Looper.getMainLooper()).idle()

        val bottomAfter = lm.findViewByPosition(totalItems - 1)!!.bottom
        val viewportBottom = recyclerView.height - recyclerView.paddingBottom
        assertTrue(
            bottomAfter <= viewportBottom,
            "last item bottom ($bottomAfter) must be scrolled fully into view " +
                "(viewport $viewportBottom) — PR #782 behavior preserved"
        )
    }
}
