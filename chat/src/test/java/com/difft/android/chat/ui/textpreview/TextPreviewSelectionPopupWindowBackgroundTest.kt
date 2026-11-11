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

            val popup = TextPreviewSelectionPopup(activity, translateSupported = true)
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

            val popup = TextPreviewSelectionPopup(activity, translateSupported = true)
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
