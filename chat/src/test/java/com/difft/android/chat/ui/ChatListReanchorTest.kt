package com.difft.android.chat.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatListReanchorTest {

    private lateinit var activity: Activity
    private lateinit var list: RecyclerView
    private val jump = JumpTargetAnchor(now = { 0L }, holdMs = 1_000)

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    
    private fun open(rows: Int, rowHeight: Int, target: Int) {
        list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            clipToPadding = false
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = rows
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(View(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
        }
        list.layoutManager!!.scrollToPosition(target)
        jump.set(target)
        layout()
    }

    private fun layout() {
        list.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, WIDTH, HEIGHT)
    }

    
    private fun inputArrives(inset: Int) {
        list.setPadding(0, 0, 0, inset)
        list.reanchor(jump.reanchorForBottomChange(inset))
        layout()
    }

    private fun row(position: Int): View = list.findViewHolderForAdapterPosition(position)!!.itemView

    @Test
    fun `unread that fit end above the input with the first unread in view`() {
        open(rows = 20, rowHeight = 40, target = 16)
        inputArrives(INPUT)

        assertTrue("newest row ends above the input", row(19).bottom <= HEIGHT - INPUT)
        assertTrue("first unread stays in view", row(16).top >= 0)
    }

    @Test
    fun `unread that do not fit keep the first unread at the top`() {
        open(rows = 20, rowHeight = 100, target = 12)
        assertTrue("the rows from the target overflow the padded list", (20 - 12) * 100 > HEIGHT - INPUT)
        assertEquals(0, row(12).top)
        inputArrives(INPUT)

        assertEquals(0, row(12).top)
    }

    @Test
    fun `an open on the newest row ends above the input`() {
        open(rows = 20, rowHeight = 40, target = 19)
        inputArrives(INPUT)

        assertTrue(row(19).bottom <= HEIGHT - INPUT)
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 600
        const val INPUT = 120
    }
}
