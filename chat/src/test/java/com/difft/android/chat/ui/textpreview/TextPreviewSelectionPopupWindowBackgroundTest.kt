package com.difft.android.chat.ui.textpreview

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * M6/M7 (issue #1127, family F): [TextPreviewSelectionPopup] is a temporary overlay that adds
 * a `ComposeView` directly onto the host Activity's `android.R.id.content` root — it never owns
 * the host window, so its `DifftTheme(applyWindowBackground = false)` migration must leave the
 * window background completely untouched, both while shown (M6) and after dismiss (M7).
 *
 * Uses `Robolectric.buildActivity(...).setup()` (not `createAndroidComposeRule`) — the popup
 * builds its own `ComposeView` and adds it directly to `android.R.id.content` outside of any
 * compose test rule's own composition, so the rule's `ViewTreeLifecycleOwner` wiring (only
 * established via the rule's own `setContent`) does not apply here. The activity must call
 * `setContentView` once before `show()` — a real host Activity always has by the time a popup is
 * shown, and `ComponentActivity` only installs the ViewTree owners on the decor once a content
 * view has actually been set (see `ComposeActivityMount`'s KDoc for the same fact, for the
 * `DifftTheme`-mounted-without-`setContentView` case).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TextPreviewSelectionPopupWindowBackgroundTest {

    private val noopCallbacks = object : TextPreviewSelectionPopup.Callbacks {
        override fun onCopy() {}
        override fun onForward() {}
        override fun onTranslate() {}
        override fun onSelectAll() {}
        override fun onDismiss() {}
    }

    @Test
    fun `M6 show never writes the host window background`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContentView(FrameLayout(activity))
            val sentinel = ColorDrawable(Color.RED)
            activity.window.setBackgroundDrawable(sentinel)

            val popup = TextPreviewSelectionPopup(activity)
            popup.show(Rect(0, 0, 100, 100), isFullSelection = false, callbacks = noopCallbacks)
            shadowOf(Looper.getMainLooper()).idle()

            val background = activity.window.decorView.background as ColorDrawable
            assertEquals(
                Color.RED,
                background.color,
                "TextPreviewSelectionPopup.show() must never touch the host window background",
            )
        } finally {
            runCatching { controller.destroy() }
        }
    }

    @Test
    fun `M7 dismiss still leaves the host window background untouched`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContentView(FrameLayout(activity))
            val sentinel = ColorDrawable(Color.RED)
            activity.window.setBackgroundDrawable(sentinel)

            val popup = TextPreviewSelectionPopup(activity)
            popup.show(Rect(0, 0, 100, 100), isFullSelection = false, callbacks = noopCallbacks)
            shadowOf(Looper.getMainLooper()).idle()

            popup.dismiss()
            shadowOf(Looper.getMainLooper()).idle()

            val background = activity.window.decorView.background as ColorDrawable
            assertEquals(
                Color.RED,
                background.color,
                "TextPreviewSelectionPopup.dismiss() must still leave the host window background untouched",
            )
        } finally {
            runCatching { controller.destroy() }
        }
    }
}
