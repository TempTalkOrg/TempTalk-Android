package com.difft.android.base.ui.compose.e2ee

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
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
class E2eeInfoSheetWindowBackgroundTest {

    @Test
    fun `M23 show never writes the host activity window background`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()

        val sentinel = ColorDrawable(Color.RED)
        activity.window.setBackgroundDrawable(sentinel)

        E2eeInfoSheet.show(activity, darkTheme = false, learnMoreUrl = "https://example.com")
        shadowOf(Looper.getMainLooper()).idle()

        val background = activity.window.decorView.background as ColorDrawable
        assertEquals(
            Color.RED,
            background.color,
            "E2eeInfoSheet.show() must never touch the host Activity window background — " +
                "DifftTheme(applyWindowBackground = false) makes this a pure skip, not a write-then-restore",
        )

        controller.destroy()
    }
}
