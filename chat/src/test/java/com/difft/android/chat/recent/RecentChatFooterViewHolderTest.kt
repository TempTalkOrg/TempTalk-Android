package com.difft.android.chat.recent

import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.R
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config














@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentChatFooterViewHolderTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
        runCatching { controller.destroy() }
    }

    private fun inflateHolder(): RecentChatFooterViewHolder {
        val view = LayoutInflater.from(activity).inflate(R.layout.chat_fragment_recent_chat_footer_item, null, false)
        return RecentChatFooterViewHolder(view)
    }

    




    @Test
    fun `bound hint text has color and bold spans over Learn more and no ClickableSpan`() {
        val holder = inflateHolder()
        holder.bind(onFooterClicked = {})

        val textView = holder.itemView.findViewById<TextView>(R.id.textview_e2ee_hint)
        val spanned = textView.text as Spanned

        val colorSpans = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
        val boldSpans = spanned.getSpans(0, spanned.length, StyleSpan::class.java)
        val clickableSpans = spanned.getSpans(0, spanned.length, ClickableSpan::class.java)

        assertTrue("expected a ForegroundColorSpan on \"Learn more\"", colorSpans.isNotEmpty())
        assertTrue("expected a bold StyleSpan on \"Learn more\"", boldSpans.any { it.style == android.graphics.Typeface.BOLD })
        assertEquals("must not contain any ClickableSpan (E11)", 0, clickableSpans.size)

        val learnMore = activity.getString(com.difft.android.base.R.string.e2ee_learn_more)
        assertTrue(spanned.toString().contains(learnMore))
    }

    




    @Test
    fun `null lock drawable still renders hint copy without crashing`() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.getDrawable(any(), any()) } returns null
        every { ContextCompat.getColor(any(), any()) } answers { callOriginal() }

        val holder = inflateHolder()
        holder.bind(onFooterClicked = {})

        val textView = holder.itemView.findViewById<TextView>(R.id.textview_e2ee_hint)
        val text = textView.text.toString()

        assertTrue(text.contains(activity.getString(R.string.chat_list_e2ee_hint)))
        assertTrue(text.contains(activity.getString(com.difft.android.base.R.string.e2ee_learn_more)))
    }
}
