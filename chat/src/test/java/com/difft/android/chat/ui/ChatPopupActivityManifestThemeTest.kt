package com.difft.android.chat.ui

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

/**
 * M13 (issue #1127, family J): manifest fact underpinning `GifInlinePanelWindowBackgroundTest`
 * (M12) -- pins that `AndroidManifest.xml` really does declare
 * `android:theme="@style/Theme.TT.TransparentActivity"` for [ChatPopupActivity], and that this
 * style resolves to a translucent, transparent window background. Does not construct
 * `ChatPopupActivity` itself: it is `@AndroidEntryPoint`, and this repo has no
 * Hilt-Robolectric harness for constructing Hilt activities directly in unit tests.
 */
@RunWith(RobolectricTestRunner::class)
class ChatPopupActivityManifestThemeTest {

    @Test
    fun `M13 manifest declares ChatPopupActivity's theme as transparent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, ChatPopupActivity::class.java), 0
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
