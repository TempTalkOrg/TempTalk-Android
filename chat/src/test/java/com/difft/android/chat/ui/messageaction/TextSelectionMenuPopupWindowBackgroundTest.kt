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

/**
 * M8 (issue #1127, family F dead code twin): [TextSelectionMenuPopup] has zero production
 * call sites today, but shares [TextPreviewSelectionPopup]'s exact structural shape (single
 * `show()` entry point, `ComposeView` added directly onto the host Activity's content root, no
 * popup/non-popup duality) — migrated in the same commit so it is already correct the moment it
 * gets wired up. Proves `DifftTheme(applyWindowBackground = false)` never touches the host
 * window background.
 *
 * Uses `Robolectric.buildActivity(...).setup()` (not `ActivityScenario.launch`, which requires
 * a manifest-resolvable Activity) — same pattern as `TextPreviewSelectionPopupWindowBackgroundTest`.
 * The activity must call `setContentView` once before `show()` — a real host Activity always has
 * by the time a popup is shown, and `ComponentActivity` only installs the ViewTree owners on the
 * decor once a content view has actually been set (see `ComposeActivityMount`'s KDoc).
 * `TestScopeApplication` initializes `ApplicationHelper.instance`, required by
 * `calculatePosition`'s `dp` extension.
 */
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
            val popup = TextSelectionMenuPopup(activity)
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
