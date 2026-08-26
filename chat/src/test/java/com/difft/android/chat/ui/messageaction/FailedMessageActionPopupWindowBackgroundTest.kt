package com.difft.android.chat.ui.messageaction

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * M24 (issue #1127, family G cleanup): [FailedMessageActionPopup] is a temporary full-screen
 * overlay that adds a `ComposeView` directly onto the host Activity's `android.R.id.content`
 * root — it never owns the host window. It migrates to
 * `DifftTheme(applyWindowBackground = false)` and drops the `OnPreDrawListener` snapshot/restore
 * patch entirely — no prior regression test existed for this popup, so this is a new
 * test, not a rewrite.
 *
 * `TestScopeApplication` initializes `ApplicationHelper.instance`, required by the popup's
 * `dpToPx` extension (same requirement as `TextSelectionMenuPopupWindowBackgroundTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34])
class FailedMessageActionPopupWindowBackgroundTest {

    private val noopCallbacks = object : FailedMessageActionPopup.Callbacks {
        override fun onResend() {}
        override fun onDelete() {}
        override fun onDismiss() {}
    }

    @Test
    fun `M24 show and dismiss never write the host window background`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContentView(FrameLayout(activity))
            val sentinel = ColorDrawable(Color.RED)
            activity.window.setBackgroundDrawable(sentinel)

            val anchorView = FrameLayout(activity)
            activity.addContentView(anchorView, FrameLayout.LayoutParams(100, 100))

            val popup = FailedMessageActionPopup(activity)
            popup.show(anchorView = anchorView, message = TextChatMessage(), callbacks = noopCallbacks)
            shadowOf(Looper.getMainLooper()).idle()

            var background = activity.window.decorView.background as ColorDrawable
            assertEquals(
                Color.RED,
                background.color,
                "FailedMessageActionPopup.show() must never touch the host window background",
            )

            popup.dismiss()
            shadowOf(Looper.getMainLooper()).idle()

            background = activity.window.decorView.background as ColorDrawable
            assertEquals(
                Color.RED,
                background.color,
                "FailedMessageActionPopup.dismiss() must still leave the host window background untouched",
            )
        } finally {
            runCatching { controller.destroy() }
        }
    }
}
