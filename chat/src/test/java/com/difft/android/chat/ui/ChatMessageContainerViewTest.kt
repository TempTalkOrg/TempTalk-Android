package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode






@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w402dp-h874dp-xxhdpi")
class ChatMessageContainerViewTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val dp = activity.resources.displayMetrics.density

    private fun row(text: String, capsWidth: Boolean = true, reactions: Int = 0): View {
        val inflater = LayoutInflater.from(activity)
        val row = inflater.inflate(R.layout.chat_item_chat_message_list_text_mine, null)
        val container = row.findViewById<ChatMessageContainerView>(R.id.contentContainer)
        container.sent = true
        container.capsWidth = capsWidth
        inflater.inflate(R.layout.chat_item_content_text, row.findViewById(R.id.contentFrame), true)
        row.findViewById<TextView>(R.id.textView).text = text
        row.findViewById<View>(R.id.ll_time_wrapper).visibility = View.VISIBLE
        row.findViewById<View>(R.id.cl_message_time).visibility = View.VISIBLE
        row.findViewById<TextView>(R.id.textViewTime).text = "2:45 PM"
        val reactionsView = row.findViewById<FlowLayout>(R.id.reactions_view)
        repeat(reactions) {
            val chip = inflater.inflate(R.layout.chat_item_reaction, reactionsView, false)
            chip.findViewById<TextView>(R.id.tv_emoji).text = "\uD83D\uDC4D"
            chip.findViewById<TextView>(R.id.tv_count).text = "12"
            reactionsView.addView(chip)
        }
        if (reactions > 0) reactionsView.visibility = View.VISIBLE
        val host = FrameLayout(activity)
        host.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(host)
        val width = View.MeasureSpec.makeMeasureSpec((402 * dp).toInt(), View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        host.measure(width, height)
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        return row
    }

    
    private fun receivedRow(content: Int, timeShown: Boolean, text: String? = null, widthDp: Int = 402, avatarColumn: Boolean = false): View {
        val inflater = LayoutInflater.from(activity)
        val row = inflater.inflate(R.layout.chat_item_chat_message_list_text_others, null)
        if (avatarColumn) {
            val container = row.findViewById<ChatMessageContainerView>(R.id.contentContainer)
            container.avatarColumn = true
            placeBubbleAfterAvatarColumn(container, senderColumn = true)
        }
        inflater.inflate(content, row.findViewById(R.id.contentFrame), true)
        text?.let { row.findViewById<TextView>(R.id.textView).text = it }
        row.findViewById<View>(R.id.cl_message_time).visibility = if (timeShown) View.VISIBLE else View.GONE
        row.findViewById<TextView>(R.id.textViewTime).text = "2:45 PM"
        
        row.findViewById<View>(R.id.reactions_view).visibility = View.GONE
        val host = FrameLayout(activity)
        host.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(host)
        host.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * dp).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        return row
    }

    @Test
    fun `a card is the 280dp bubble on a phone and shrinks with a narrow pane`() {
        val phone = receivedRow(R.layout.chat_item_content_multi_forward, timeShown = true)
        assertEquals(280f, phone.findViewById<View>(R.id.contentContainer).measuredWidth / dp, 1f)

        val narrow = receivedRow(R.layout.chat_item_content_contact, timeShown = true, widthDp = 300)
        val bubble = narrow.findViewById<ViewGroup>(R.id.contentContainer)
        
        val card = bubble.findViewWithTag<View>("time_below")
        assertTrue("bubble ${bubble.measuredWidth / dp}dp fits the 300dp pane", bubble.measuredWidth / dp < 280f)
        assertEquals(bubble.measuredWidth, card.measuredWidth)
    }

    @Test
    fun `a received text without its time keeps all of its text inside the bubble`() {
        val row = receivedRow(R.layout.chat_item_content_text, timeShown = false, text = "Hi there")
        val container = row.findViewById<View>(R.id.contentContainer)
        val frame = row.findViewById<View>(R.id.contentFrame)
        assertTrue("bubble ${container.measuredHeight} vs content ${frame.bottom}", container.measuredHeight >= frame.bottom)
    }

    @Test
    fun `a card without its time ends 10dp below its block`() {
        val row = receivedRow(R.layout.chat_item_content_multi_forward, timeShown = false)
        val container = row.findViewById<View>(R.id.contentContainer)
        val frame = row.findViewById<View>(R.id.contentFrame)
        val block = row.findViewById<View>(R.id.ll_content)
        assertEquals(10f, (container.measuredHeight - (frame.top + block.bottom)) / dp, 1f)
    }

    @Test
    fun `a card with its time puts the time on its own line below the block`() {
        val row = receivedRow(R.layout.chat_item_content_contact, timeShown = true)
        val frame = row.findViewById<View>(R.id.contentFrame)
        val time = row.findViewById<View>(R.id.ll_time_wrapper)
        assertEquals(0f, row.timeTopMarginDp(), 0.5f)
        assertTrue("time ${time.top} below the card ${frame.bottom}", time.top >= frame.bottom)
        
        val container = row.findViewById<View>(R.id.contentContainer)
        assertEquals(3f, (container.measuredHeight - time.bottom) / dp, 0.5f)
    }

    @Test
    fun `a reused card row whose time is hidden drops the card's bottom spacing`() {
        val row = receivedRow(R.layout.chat_item_content_contact, timeShown = true)
        val wrapper = row.findViewById<View>(R.id.ll_time_wrapper)
        assertEquals(3f, (wrapper.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin / dp, 0.5f)

        
        row.findViewById<View>(R.id.cl_message_time).visibility = View.GONE
        val host = row.parent as View
        host.measure(
            View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals(0, (wrapper.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
    }

    @Test
    fun `a text bubble keeps its time line without the card's bottom spacing`() {
        val probe = row("x").findViewById<TextView>(R.id.textView)
        val chars = ((256 * dp) / probe.paint.measureText("x")).toInt() - 1
        val row = row("x".repeat(chars))
        assertEquals(0f, row.timeTopMarginDp(), 0.5f)
        val wrapper = row.findViewById<View>(R.id.ll_time_wrapper)
        assertEquals(0, (wrapper.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
    }

    private fun View.timeTopMarginDp(): Float =
        (findViewById<View>(R.id.ll_time_wrapper).layoutParams as ViewGroup.MarginLayoutParams).topMargin / dp

    private fun View.bubbleWidthDp(): Float = findViewById<View>(R.id.contentContainer).measuredWidth / dp

    @Test
    fun `a short line keeps the time on it`() {
        val row = row("Hi")
        assertEquals(-26f, row.timeTopMarginDp(), 0.5f)
    }

    @Test
    fun `a line too long for the time puts the time below`() {
        
        val probe = row("x").findViewById<TextView>(R.id.textView)
        val chars = ((256 * dp) / probe.paint.measureText("x")).toInt() - 1
        val row = row("x".repeat(chars))
        assertEquals(1, row.findViewById<TextView>(R.id.textView).lineCount)
        assertEquals(0f, row.timeTopMarginDp(), 0.5f)
    }

    @Test
    fun `a wrapped message whose last line is short keeps the time on it`() {
        val row = row("This message is long enough to wrap onto a second line inside the bubble.")
        assertTrue(row.findViewById<TextView>(R.id.textView).lineCount > 1)
        assertEquals(-26f, row.timeTopMarginDp(), 0.5f)
    }

    @Test
    fun `a text bubble stops at 280dp`() {
        val row = row("A long message ".repeat(20))
        assertTrue("bubble ${row.bubbleWidthDp()}dp", row.bubbleWidthDp() <= 280.5f)
    }

    @Test
    fun `an uncapped bubble may use the whole row`() {
        val row = row("A long message ".repeat(20), capsWidth = false)
        assertTrue("bubble ${row.bubbleWidthDp()}dp", row.bubbleWidthDp() > 300f)
    }

    
    private fun chipsPerRow(): Int {
        val probe = row("Hi", reactions = 1).findViewById<FlowLayout>(R.id.reactions_view).getChildAt(0)
        return ((256 * dp) / (probe.measuredWidth + 4 * dp)).toInt()
    }

    @Test
    fun `one reaction row reserves room for the time beside it`() {
        val row = row("Hi", reactions = 1)
        assertEquals(-26f, row.timeTopMarginDp(), 0.5f)
        val reactionsView = row.findViewById<FlowLayout>(R.id.reactions_view)
        val timeWidth = row.findViewById<View>(R.id.cl_message_time).measuredWidth
        assertEquals(timeWidth + (3 * dp).toInt(), reactionsView.paddingEnd)
    }

    @Test
    fun `several reaction rows with a short last row keep the time on it`() {
        val row = row("Hi", reactions = chipsPerRow() + 1)
        assertEquals(2, row.findViewById<FlowLayout>(R.id.reactions_view).getLineCount())
        assertEquals(-26f, row.timeTopMarginDp(), 0.5f)
    }

    @Test
    fun `several full reaction rows put the time below`() {
        val row = row("Hi", reactions = chipsPerRow() * 2)
        assertEquals(2, row.findViewById<FlowLayout>(R.id.reactions_view).getLineCount())
        assertEquals(0f, row.timeTopMarginDp(), 0.5f)
    }

    @Test
    fun `the group read count badge grows to fit its digits`() {
        val row = row("Hi")
        val badge = row.findViewById<TextView>(R.id.iv_read_number)
        badge.visibility = View.VISIBLE
        badge.text = "128"
        row.measure(
            View.MeasureSpec.makeMeasureSpec((402 * dp).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val textWidth = badge.paint.measureText("128")
        assertTrue("badge ${badge.measuredWidth} fits $textWidth", badge.measuredWidth - badge.paddingLeft - badge.paddingRight >= textWidth)
    }

    @Test
    fun `a group bubble measures its room past the avatar column once`() {
        
        val row = receivedRow(R.layout.chat_item_content_text, timeShown = true, text = "Hi", widthDp = 360, avatarColumn = true)
        val container = row.findViewById<ChatMessageContainerView>(R.id.contentContainer)
        assertEquals(274f, container.calculateEffectiveMaxWidth() / dp, 1f)
    }
}
