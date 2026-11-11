package com.difft.android.chat.recent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.difft.android.chat.R
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.setting.archive.bindCompactExpiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33], qualifiers = "en-mdpi")
class CompactExpiryRowTest {
    @Test
    fun `list and popup expiry rows have compact geometry and reset when recycled`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), androidx.appcompat.R.style.Theme_AppCompat_Light)
        for (layout in listOf(R.layout.chat_fragment_recent_chat_list_item, R.layout.chat_fragment_header_popup, R.layout.chat_fragment_header_group_popup)) {
            val root = LayoutInflater.from(context).inflate(layout, null)
            val row = root.findViewById<ViewGroup>(R.id.timer_row)
            assertNotNull("expiry icon and text must share a row", row)
            val text = root.findViewById<TextView>(R.id.textview_timer)
            val icon = row.getChildAt(0) as ImageView
            assertNotNull(icon.drawable)
            assertEquals(12, icon.layoutParams.width)
            assertEquals(12, icon.layoutParams.height)
            assertEquals(12 * context.resources.displayMetrics.scaledDensity, text.textSize, 0.01f)
            assertEquals(2, (text.layoutParams as ViewGroup.MarginLayoutParams).marginStart)

            bindCompactExpiry(row, text, 86_400L)
            assertEquals(View.VISIBLE, row.visibility)
            assertEquals("1d", text.text.toString())
            assertNotNull(row.contentDescription)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, row.importantForAccessibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, text.importantForAccessibility)
            bindCompactExpiry(row, text, 0L)
            assertEquals(View.GONE, row.visibility)
            assertEquals("", text.text.toString())
            assertNull(row.contentDescription)
            bindCompactExpiry(row, text, null)
            assertEquals(View.GONE, row.visibility)
        }
    }
}
