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

/**
 * M23 (issue #1127, family G cleanup): [E2eeInfoSheet.show] mounts a ComposeView wrapped in
 * [DifftTheme] directly onto the host Activity's own root content view (via
 * [com.difft.android.base.widget.ComposeActivityMount]) — not a separate dialog window — so it
 * does not own the host window. It now migrates to `DifftTheme(applyWindowBackground = false)`
 * and drops the `OnPreDrawListener` snapshot/restore patch entirely: there is no
 * longer a write to restore, so this test asserts the stronger "never touched" invariant
 * directly instead of simulating the predraw dispatch the old restore-based mechanism needed.
 */
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
