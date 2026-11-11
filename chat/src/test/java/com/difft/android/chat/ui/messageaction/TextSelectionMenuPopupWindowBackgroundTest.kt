package com.difft.android.chat.ui.messageaction

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals









@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34])
class TextSelectionMenuPopupWindowBackgroundTest {

    private val noopCallbacks = object : TextSelectionMenuPopup.Callbacks {
        override fun onCopy(selectedText: String) {}
        override fun onForward(selectedText: String) {}
        override fun onTranslate(selectedText: String) {}
        override fun onSelectAll() {}
        override fun onDismiss() {}
    }

    @Test
    fun `M8 show never writes the host window background`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContentView(FrameLayout(activity))
            val sentinel = ColorDrawable(Color.RED)
            activity.window.setBackgroundDrawable(sentinel)

            val anchorView = View(activity)
            val popup = TextSelectionMenuPopup(activity, translateSupported = true)
            popup.show(anchorView, "selected text", 0, 0, noopCallbacks)
            shadowOf(Looper.getMainLooper()).idle()

            val background = activity.window.decorView.background as ColorDrawable
            assertEquals(
                Color.RED,
                background.color,
                "TextSelectionMenuPopup.show() must never touch the host window background",
            )
        } finally {
            runCatching { controller.destroy() }
        }
    }
}
