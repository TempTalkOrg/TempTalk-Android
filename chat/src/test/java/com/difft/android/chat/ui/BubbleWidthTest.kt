package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper





@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w402dp-h874dp-xxhdpi")
class BubbleWidthTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val dp = activity.resources.displayMetrics.density

    @Test
    fun `the cap keeps 280dp up to a 402dp column and follows the iOS ratio above`() {
        listOf(320 to 280, 360 to 280, 402 to 280, 600 to 417, 900 to 626).forEach { (column, cap) ->
            assertEquals("column $column", cap, BubbleWidth.maxBubblePx(column, 1f))
        }
        assertEquals((280 * 3f).toInt(), BubbleWidth.maxBubblePx(300 * 3, 3f))
    }

    @Test
    fun `a long text bubble grows with a wide list but stays within the ratio`() {
        val long = "word ".repeat(200)
        val phone = host(sentTextRow(long), 402)
        val phoneWidth = phone.findViewById<View>(R.id.contentContainer).measuredWidth / dp
        assertEquals(280f, phoneWidth, 1f)

        val wide = host(sentTextRow(long), 700)
        val wideWidth = wide.findViewById<View>(R.id.contentContainer).measuredWidth / dp
        assertTrue("grew past 280dp: $wideWidth", wideWidth > 400f)
        assertTrue("within 700 x 280/402: $wideWidth", wideWidth <= 488f)
    }

    @Test
    fun `cards keep 280dp however wide the list`() {
        val inflater = LayoutInflater.from(activity)
        val row = inflater.inflate(R.layout.chat_item_chat_message_list_text_others, null)
        inflater.inflate(R.layout.chat_item_content_contact, row.findViewById(R.id.contentFrame), true)
        row.findViewById<View>(R.id.reactions_view).visibility = View.GONE
        host(row, 800)
        val card = row.findViewWithTag<View>("time_below")
        assertEquals(280f, card.layoutParams.width / dp, 1f)
    }

    @Test
    fun `the quote text column follows the bubble on a re-measure`() {
        val row = sentTextRow("reply")
        row.findViewById<View>(R.id.quoteZone).visibility = View.VISIBLE
        row.findViewById<TextView>(R.id.quoteText).text = "quoted ".repeat(80)
        val quoteText = row.findViewById<TextView>(R.id.quoteText)

        host(row, 402)
        assertEquals(280f - 47f, quoteText.maxWidth / dp, 1f)

        remeasure(row.parent as FrameLayout, 800)
        val cap = 800 * 280f / 402f
        assertEquals(cap - 47f, quoteText.maxWidth / dp, 1.5f)
    }

    @Test
    fun `a quote in an uncapped media row still stops at 280dp`() {
        val row = sentTextRow("reply")
        row.findViewById<ChatMessageContainerView>(R.id.contentContainer).apply {
            capsWidth = false
            growsWithWindow = false
        }
        row.findViewById<View>(R.id.quoteZone).visibility = View.VISIBLE
        val quoteText = row.findViewById<TextView>(R.id.quoteText)
        quoteText.text = "quoted ".repeat(80)

        host(row, 700)

        assertEquals(280f - 47f, quoteText.maxWidth / dp, 1.5f)
    }

    @Test
    fun `a sent bubble's room is the row minus its own margins`() {
        val row = host(sentTextRow("hi"), 300)
        val container = row.findViewById<ChatMessageContainerView>(R.id.contentContainer)
        
        val expected = (300 - 52) * dp - container.paddingLeft - container.paddingRight
        assertEquals(expected / dp, container.calculateEffectiveMaxWidth() / dp, 1f)
    }

    @Test
    fun `a list re-truncates once per width change and not while frozen`() {
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        val adapter = Rows(20)
        list.adapter = adapter
        val payloads = mutableListOf<Any?>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                payloads += payload
            }
        })
        BubbleWidthTracker(list, adapter).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((402 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        activity.setContentView(host)
        idle()

        resize(list, 700)
        assertEquals(listOf<Any?>(ChatMessageAdapter.PAYLOAD_WIDTH), payloads)

        BubbleWidth.freeze()
        resize(list, 500)
        resize(list, 600)
        assertEquals("nothing while frozen", 1, payloads.size)

        BubbleWidth.unfreeze()
        idle()
        assertEquals(listOf<Any?>(ChatMessageAdapter.PAYLOAD_WIDTH, ChatMessageAdapter.PAYLOAD_WIDTH), payloads)

        
        BubbleWidth.freeze()
        resize(list, 400)
        resize(list, 600)
        BubbleWidth.unfreeze()
        idle()
        assertEquals(3, payloads.size)
    }

    @Test
    fun `a list at the bottom stays there when narrowing makes its rows taller`() {
        val list = RecyclerView(activity)
        val layoutManager = LinearLayoutManager(activity)
        list.layoutManager = layoutManager
        val adapter = Rows(30, text = "word ".repeat(40))
        list.adapter = adapter
        BubbleWidthTracker(list, adapter).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((700 * dp).toInt(), (600 * dp).toInt()))
        activity.setContentView(host)
        idle()
        list.scrollToPosition(adapter.itemCount - 1)
        idle()
        assertEquals(adapter.itemCount - 1, layoutManager.findLastCompletelyVisibleItemPosition())

        resize(list, 300)
        idle()

        assertEquals(adapter.itemCount - 1, layoutManager.findLastCompletelyVisibleItemPosition())
    }

    @Test
    fun `a bottom row taller than the list after narrowing keeps its bottom in view`() {
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        val adapter = Rows(5, text = "word ".repeat(40), lastText = "word ".repeat(400))
        list.adapter = adapter
        BubbleWidthTracker(list, adapter).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((800 * dp).toInt(), (400 * dp).toInt()))
        activity.setContentView(host)
        idle()
        list.scrollToPosition(adapter.itemCount - 1)
        idle()
        assertTrue("starts at the bottom", !list.canScrollVertically(1))

        resize(list, 300)
        idle()

        assertTrue("still at the bottom", !list.canScrollVertically(1))
    }

    @Test
    fun `fixed-width content keeps the 280dp cap on a wide list`() {
        val row = sentTextRow("word ".repeat(200))
        row.findViewById<ChatMessageContainerView>(R.id.contentContainer).growsWithWindow = false

        host(row, 700)

        assertEquals(280f, row.findViewById<View>(R.id.contentContainer).measuredWidth / dp, 1f)
    }

    @Test
    fun `a burst of width changes rebinds once after it settles`() {
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        val adapter = Rows(20)
        list.adapter = adapter
        var rebinds = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                rebinds++
            }
        })
        BubbleWidthTracker(list, adapter).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((402 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        activity.setContentView(host)
        idle()

        listOf(450, 500, 550, 600).forEach { width ->
            list.layoutParams = list.layoutParams.apply { this.width = (width * dp).toInt() }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
        }
        assertEquals("not while the width keeps moving", 0, rebinds)
        idle()
        assertEquals(1, rebinds)
    }

    @Test
    fun `a freeze holds a rebind that was still pending until the unfreeze`() {
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        val adapter = Rows(20)
        list.adapter = adapter
        var rebinds = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                rebinds++
            }
        })
        BubbleWidthTracker(list, adapter).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((402 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        activity.setContentView(host)
        idle()

        
        list.layoutParams = list.layoutParams.apply { width = (500 * dp).toInt() }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        assertEquals("laid out at the new width", (500 * dp).toInt(), list.width)
        BubbleWidth.freeze()
        idle()
        assertEquals("not in the middle of the frozen resize", 0, rebinds)

        BubbleWidth.unfreeze()
        idle()
        assertEquals(1, rebinds)
    }

    @Test
    fun `a row that grows right after the rebind does not push the bottom out of view`() {
        val list = RecyclerView(activity)
        val layoutManager = LinearLayoutManager(activity)
        list.layoutManager = layoutManager
        val adapter = Rows(30, text = "word ".repeat(40))
        list.adapter = adapter
        
        BubbleWidthTracker(list, adapter, holdMs = 10_000).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((700 * dp).toInt(), (600 * dp).toInt()))
        activity.setContentView(host)
        idle()
        list.scrollToPosition(adapter.itemCount - 1)
        idle()

        
        list.layoutParams = list.layoutParams.apply { width = (300 * dp).toInt() }
        frames(10)
        assertTrue("pinned by the rebind", !list.canScrollVertically(1))
        val last = layoutManager.findViewByPosition(adapter.itemCount - 1) as TextView
        last.text = "${last.text}\nRead more"
        frames(4)

        assertTrue("still at the bottom", !list.canScrollVertically(1))
    }

    @Test
    fun `a jump elsewhere during the hold is not pulled back to the bottom`() {
        val list = RecyclerView(activity)
        val layoutManager = LinearLayoutManager(activity)
        list.layoutManager = layoutManager
        val adapter = Rows(60, text = "word ".repeat(40))
        list.adapter = adapter
        BubbleWidthTracker(list, adapter, holdMs = 10_000).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((700 * dp).toInt(), (600 * dp).toInt()))
        activity.setContentView(host)
        idle()
        list.scrollToPosition(adapter.itemCount - 1)
        idle()

        list.layoutParams = list.layoutParams.apply { width = (300 * dp).toInt() }
        frames(10)
        assertTrue("pinned by the rebind", !list.canScrollVertically(1))
        
        layoutManager.scrollToPosition(0)
        frames(4)

        assertEquals(0, layoutManager.findFirstVisibleItemPosition())
    }

    @Test
    fun `a jump from outside the list while the rebind is pending is not pulled back`() {
        val list = RecyclerView(activity)
        val layoutManager = LinearLayoutManager(activity)
        list.layoutManager = layoutManager
        val adapter = Rows(30, text = "word ".repeat(40))
        list.adapter = adapter
        BubbleWidthTracker(list, adapter).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((700 * dp).toInt(), (600 * dp).toInt()))
        activity.setContentView(host)
        idle()
        list.scrollToPosition(adapter.itemCount - 1)
        idle()

        list.layoutParams = list.layoutParams.apply { width = (300 * dp).toInt() }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        
        layoutManager.scrollToPosition(0)
        BubbleWidth.navigated(list)
        idle()

        assertEquals(0, layoutManager.findFirstVisibleItemPosition())
    }

    @Test
    fun `a jump while the list is frozen is not pulled back by the unfreeze`() {
        val list = RecyclerView(activity)
        val layoutManager = LinearLayoutManager(activity)
        list.layoutManager = layoutManager
        val adapter = Rows(30, text = "word ".repeat(40))
        list.adapter = adapter
        BubbleWidthTracker(list, adapter).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((700 * dp).toInt(), (600 * dp).toInt()))
        activity.setContentView(host)
        idle()
        list.scrollToPosition(adapter.itemCount - 1)
        idle()

        
        BubbleWidth.freeze()
        resize(list, 300)
        layoutManager.scrollToPosition(0)
        BubbleWidth.navigated(list)
        idle()
        BubbleWidth.unfreeze()
        idle()

        assertEquals(0, layoutManager.findFirstVisibleItemPosition())
    }

    @Test
    fun `a touch while the rebind is pending keeps the list where the user puts it`() {
        val list = RecyclerView(activity)
        val layoutManager = LinearLayoutManager(activity)
        list.layoutManager = layoutManager
        val adapter = Rows(30, text = "word ".repeat(40))
        list.adapter = adapter
        BubbleWidthTracker(list, adapter).install()
        val host = FrameLayout(activity)
        host.addView(list, ViewGroup.LayoutParams((700 * dp).toInt(), (600 * dp).toInt()))
        activity.setContentView(host)
        idle()
        list.scrollToPosition(adapter.itemCount - 1)
        idle()

        list.layoutParams = list.layoutParams.apply { width = (300 * dp).toInt() }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        list.dispatchTouchEvent(down)
        down.recycle()
        layoutManager.scrollToPosition(0)
        idle()

        assertEquals(0, layoutManager.findFirstVisibleItemPosition())
    }

    @Test
    fun `a width change is a partial bind`() {
        assertEquals(
            setOf(ChatMessageAdapter.PAYLOAD_WIDTH),
            ChatMessageAdapter.partialPayloadsFor(listOf(ChatMessageAdapter.PAYLOAD_WIDTH), isMessageHolder = true),
        )
    }

    private fun sentTextRow(text: String): View {
        val inflater = LayoutInflater.from(activity)
        val row = inflater.inflate(R.layout.chat_item_chat_message_list_text_mine, null)
        row.findViewById<ChatMessageContainerView>(R.id.contentContainer).apply {
            sent = true
            growsWithWindow = true
        }
        inflater.inflate(R.layout.chat_item_content_text, row.findViewById(R.id.contentFrame), true)
        row.findViewById<TextView>(R.id.textView).text = text
        row.findViewById<View>(R.id.reactions_view).visibility = View.GONE
        return row
    }

    private fun host(row: View, widthDp: Int): View {
        val host = FrameLayout(activity)
        host.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(host)
        remeasure(host, widthDp)
        return row
    }

    




    private fun remeasure(host: ViewGroup, widthDp: Int) {
        repeat(2) {
            
            forceLayoutTree(host)
            host.measure(
                View.MeasureSpec.makeMeasureSpec((widthDp * dp).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        }
    }

    private fun forceLayoutTree(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (i in 0 until view.childCount) forceLayoutTree(view.getChildAt(i))
    }

    
    private fun resize(list: View, widthDp: Int) {
        list.layoutParams = list.layoutParams.apply { width = (widthDp * dp).toInt() }
        idle()
    }

    
    private fun frames(count: Int) = repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)) }

    
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))

    private class Rows(
        private val count: Int,
        private val text: String = "",
        private val lastText: String = text,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = "row $position ${if (position == count - 1) lastText else text}"
        }

        override fun getItemCount() = count
    }
}
