package com.difft.android.chat.recent

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The chat list has no fixed first row, so a conversation that moves to the top must stay
 * visible when the list was at its top, and must not yank a list the user scrolled down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatListTopAnchorTest {

    @Test
    fun `a row moved to the top stays in view when the list was at its top`() {
        val (list, adapter) = host()
        adapter.submitListKeepingTop(list, ROWS)
        settle(list)

        val moved = listOf("row 20") + ROWS.filter { it != "row 20" }
        adapter.submitListKeepingTop(list, moved)
        settle(list)

        assertEquals(0, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
        assertEquals("row 20", (list.getChildAt(0) as TextView).text)
    }

    @Test
    fun `a scrolled list keeps its position when a row moves to the top`() {
        val (list, adapter) = host()
        adapter.submitListKeepingTop(list, ROWS)
        settle(list)
        list.scrollToPosition(15)
        settle(list)
        val before = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()

        val moved = listOf("row 39") + ROWS.filter { it != "row 39" }
        adapter.submitListKeepingTop(list, moved)
        settle(list)

        // Every visible row shifted down by one, so the same rows are on screen one index later.
        assertEquals(before + 1, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
    }

    @Test
    fun `a scroll made while the diff is pending is not undone`() {
        val (list, adapter) = host()
        adapter.submitListKeepingTop(list, ROWS)
        settle(list)

        // Submitted at the top; the differ's commit is still queued on the main looper.
        val moved = listOf("row 39") + ROWS.filter { it != "row 39" }
        adapter.submitListKeepingTop(list, moved)
        list.scrollToPosition(15)
        layout(list)
        val before = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        settle(list)

        assertEquals(before + 1, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
    }

    private fun host(): Pair<RecyclerView, TextAdapter> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = null
        }
        val adapter = TextAdapter()
        list.adapter = adapter
        activity.setContentView(FrameLayout(activity).apply { addView(list, FrameLayout.LayoutParams(LIST_PX, LIST_PX)) })
        settle(list)
        return list to adapter
    }

    private fun settle(list: RecyclerView) {
        shadowOf(Looper.getMainLooper()).idle()
        layout(list)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layout(list: RecyclerView) {
        list.measure(
            View.MeasureSpec.makeMeasureSpec(LIST_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(LIST_PX, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, LIST_PX, LIST_PX)
    }

    private class TextAdapter : ListAdapter<String, RecyclerView.ViewHolder>(
        AsyncDifferConfig.Builder(SAME)
            .setBackgroundThreadExecutor { it.run() }
            .build()
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(
                TextView(parent.context).apply { layoutParams = RecyclerView.LayoutParams(LIST_PX, ROW_PX) }
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = getItem(position)
        }
    }

    private companion object {
        const val LIST_PX = 1000
        const val ROW_PX = 100
        val ROWS = (0 until 40).map { "row $it" }
        val SAME = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
            override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        }
    }
}
