package com.difft.android.chat.common

import android.text.Spanned
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class LinkTextUtilsWeightTest {

    private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), com.difft.android.base.R.style.Theme_Chative)

    private fun weightSpans(linkMedium: Boolean): List<MetricAffectingSpan> {
        val textView = TextView(context)
        LinkTextUtils.setMarkdownToTextview(context, TEXT, textView, linkColor = 0xFF056FFA.toInt(), linkMedium = linkMedium)
        val text = textView.text as Spanned
        return text.getSpans(0, text.length, MetricAffectingSpan::class.java).toList()
    }

    @Test
    fun `Medium links carry a metric-affecting span over the link`() {
        val spans = weightSpans(linkMedium = true)
        assertEquals(1, spans.size)
        val text = TEXT
        val start = text.indexOf("https://")
        val textView = TextView(context)
        LinkTextUtils.setMarkdownToTextview(context, text, textView, linkMedium = true)
        val spanned = textView.text as Spanned
        val span = spanned.getSpans(0, spanned.length, MetricAffectingSpan::class.java).single()
        assertEquals(start, spanned.getSpanStart(span))
        assertTrue(spanned.getSpanEnd(span) > start)
    }

    @Test
    fun `Regular links add no metric-affecting span`() {
        assertTrue(weightSpans(linkMedium = false).isEmpty())
    }

    private companion object {
        const val TEXT = "see https://example.com/page now"
    }
}
