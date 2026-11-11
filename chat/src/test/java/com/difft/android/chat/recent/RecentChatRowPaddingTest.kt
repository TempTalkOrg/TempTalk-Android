package com.difft.android.chat.recent

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentChatRowPaddingTest {

    @Test
    fun `row foreground pads 10dp above and below the 52dp avatar`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), androidx.appcompat.R.style.Theme_AppCompat_Light
        )
        val row = LayoutInflater.from(context).inflate(R.layout.chat_fragment_recent_chat_list_item, null)
        val foreground = row.findViewById<View>(R.id.swipe_foreground)
        val density = context.resources.displayMetrics.density
        val expectedPx = (10 * density).toInt()

        assertEquals("paddingTop", expectedPx, foreground.paddingTop)
        assertEquals("paddingBottom", expectedPx, foreground.paddingBottom)

        val avatar = row.findViewById<View>(R.id.frame_layout_avatar)
        assertEquals("avatar size", (52 * density).toInt(), avatar.layoutParams.height)
        assertEquals("avatar width", (52 * density).toInt(), avatar.layoutParams.width)
    }
}
