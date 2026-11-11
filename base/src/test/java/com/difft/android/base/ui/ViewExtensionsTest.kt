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
        
        recyclerView.measureAndLayout()
        layoutReturned = true

        assertFalse(
            scrolledDuringLayout,
            "scrollBy dispatched onScrolled synchronously inside the layout pass (issue 32a90db4)"
        )
    }

    @Test
    fun `offset fine-tune still scrolls the grown last item fully into view`() {
        
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
