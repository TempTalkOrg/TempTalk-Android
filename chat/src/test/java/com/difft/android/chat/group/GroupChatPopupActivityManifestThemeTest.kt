package com.difft.android.chat.group

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue









@RunWith(RobolectricTestRunner::class)
class GroupChatPopupActivityManifestThemeTest {

    @Test
    fun `M14 manifest declares GroupChatPopupActivity's theme as transparent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, GroupChatPopupActivity::class.java), 0
        )
        val themedContext = ContextThemeWrapper(context, activityInfo.theme)
        val attrs = themedContext.obtainStyledAttributes(
            intArrayOf(android.R.attr.windowIsTranslucent, android.R.attr.windowBackground)
        )
        try {
            assertTrue(attrs.getBoolean(0, false))
            val bg = attrs.getDrawable(1)
            assertTrue(bg == null || (bg as? ColorDrawable)?.color == Color.TRANSPARENT)
        } finally {
            attrs.recycle()
        }
    }
}
