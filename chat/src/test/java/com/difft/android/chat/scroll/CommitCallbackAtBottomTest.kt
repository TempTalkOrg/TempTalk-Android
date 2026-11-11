package com.difft.android.chat.scroll

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.ui.noSmoothScrollToBottom
import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.scroll.testing.RecyclerViewScrollHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test














class CommitCallbackAtBottomTest : RecyclerViewScrollHarness() {

    
    @Test
    fun `the commit callback sees a new item count against the old layout`() {
        val adapter = attachListAdapter()
        adapter.submitList(ids(MAX_MESSAGE_COUNT))
        idleLooper()
        layoutRecyclerView()
        recyclerView.noSmoothScrollToBottom()
        layoutRecyclerView()
        idleLooper()
        assertTrue("precondition: parked at the bottom", lastVisible() == layoutManager().itemCount - 1)

        var atBottomInsideCallback: Boolean? = null
        adapter.submitList(ids(MAX_MESSAGE_COUNT + 1)) {
            atBottomInsideCallback = lastVisible() == layoutManager().itemCount - 1
        }
        idleLooper()

        assertFalse(
            "isAtBottom must be captured BEFORE submitList; inside the commit callback it is false",
            atBottomInsideCallback ?: error("commit callback never ran"),
        )
    }

    
    
    @Test
    fun `snapping to the bottom after an append still shows the appended row`() {
        val adapter = attachListAdapter()
        adapter.submitList(ids(MAX_MESSAGE_COUNT))
        idleLooper()
        layoutRecyclerView()
        recyclerView.noSmoothScrollToBottom()
        layoutRecyclerView()
        idleLooper()
        val wasAtBottom = lastVisible() == layoutManager().itemCount - 1

        adapter.submitList(ids(MAX_MESSAGE_COUNT + 1)) {
            if (wasAtBottom) recyclerView.noSmoothScrollToBottom()
        }
        idleLooper()
        layoutRecyclerView()
        idleLooper()

        assertTrue(wasAtBottom)
        assertEquals(MAX_MESSAGE_COUNT + 1, layoutManager().itemCount)
        assertEquals(layoutManager().itemCount - 1, lastVisible())
        assertNeverDragged()
    }

    private fun ids(count: Int): List<String> = List(count) { "row-$it" }

    private fun attachListAdapter(): ListAdapter<String, RecyclerView.ViewHolder> {
        val adapter = object : ListAdapter<String, RecyclerView.ViewHolder>(DIFFER_CONFIG) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = View(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ITEM_HEIGHT_PX)
                }
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        recyclerView.adapter = adapter
        layoutRecyclerView()
        return adapter
    }

    private companion object {
        val STRING_DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
            override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        }

        




        val DIFFER_CONFIG: AsyncDifferConfig<String> =
            AsyncDifferConfig.Builder(STRING_DIFF)
                .setBackgroundThreadExecutor { it.run() }
                .build()
    }
}
