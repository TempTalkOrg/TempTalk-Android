package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.dp
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * F1-F3 (#1294): outside-tap hit-test and placement of the real [FailedMessageActionPopup].
 *
 * F1 pins the hit-test contract: a tap inside the laid-out menu bounds (panel corner / arrow strip,
 * not an action) never dismisses; the outside-tap handler hit-tests against those laid-out bounds.
 *
 * Expected px values derive from the same `Int.dp` conversion the popup uses: that extension reads
 * the first Robolectric application's density in the JVM, so literal xxhdpi values would break
 * when this class runs after an mdpi test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FailedMessageActionPopupTouchTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var controller: ActivityController<FragmentActivity>? = null

    private var dismissCount = 0
    private var resendCount = 0
    private var deleteCount = 0

    private val callbacks = object : FailedMessageActionPopup.Callbacks {
        override fun onResend() { resendCount++ }
        override fun onDelete() { deleteCount++ }
        override fun onDismiss() { dismissCount++ }
    }

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
    }

    private inner class Fixture(anchorTop: Int = 200, anchorHeight: Int = 100, anchorEndInset: Int? = null) {
        val activity: FragmentActivity =
            Robolectric.buildActivity(FragmentActivity::class.java).setup().also { controller = it }.get()
        val container = FrameLayout(activity)
        val anchorView = View(activity)
        val popup = FailedMessageActionPopup(activity)
        val root: ViewGroup = activity.findViewById(android.R.id.content)

        init {
            activity.setContentView(FrameLayout(activity).apply {
                addView(container, FrameLayout.LayoutParams(MATCH, MATCH))
                val anchorWidth = if (anchorEndInset == null) 300 else MATCH
                addView(
                    anchorView,
                    FrameLayout.LayoutParams(anchorWidth, anchorHeight).apply {
                        topMargin = anchorTop
                        rightMargin = anchorEndInset ?: 0
                    },
                )
            })
            shadowOf(Looper.getMainLooper()).idle()
        }

        val contentRel: Rect
            get() {
                val rootWindow = Rect().also { root.getGlobalVisibleRect(it) }
                return Rect().also { container.getGlobalVisibleRect(it) }
                    .apply { offset(-rootWindow.left, -rootWindow.top) }
            }

        fun show() {
            popup.show(anchorView = anchorView, message = TextChatMessage(), containerView = container, callbacks = callbacks)
            composeRule.waitForIdle()
        }

        fun menuBounds(): Rect {
            val b = composeRule.onNodeWithTag(FAILED_POPUP_MENU_TAG).fetchSemanticsNode().boundsInRoot
            return Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
        }
    }

    @Test
    fun `F1 a tap inside the popup off any action does not dismiss`() {
        val f = Fixture()
        f.show()

        composeRule.onNodeWithTag(FAILED_POPUP_MENU_TAG).performTouchInput { click(Offset(2f, 2f)) }
        composeRule.waitForIdle()

        assertEquals(0, dismissCount)
        assertEquals(0, resendCount)
        assertEquals(0, deleteCount)
        assertTrue(f.popup.isShowing)
    }

    @Test
    fun `F2 a tap outside the popup dismisses`() {
        val f = Fixture()
        f.show()

        composeRule.onNodeWithTag(FAILED_POPUP_MENU_TAG).performTouchInput {
            // Above and past the right edge: the menu starts at the left padding, so a tap to its left is off-screen.
            click(Offset(width + 40f, -40f))
        }
        composeRule.waitForIdle()

        assertEquals(1, dismissCount)
        assertEquals(0, resendCount)
        assertEquals(0, deleteCount)
        assertFalse(f.popup.isShowing)
    }

    @Test
    fun `F3 a bubble taller than the content pins the popup to the bottom edge at the bubble right edge`() {
        val bubbleEndInset = 40.dp
        val f = Fixture(anchorTop = 0, anchorHeight = 10_000, anchorEndInset = bubbleEndInset)
        f.show()

        val content = f.contentRel
        val pad = 8.dp
        val bounds = f.menuBounds()
        assertTrue("bounds=$bounds content=$content", bounds.top >= content.top + pad)
        assertEquals(content.bottom - pad, bounds.bottom)
        // Failed messages are outgoing: the right edge follows the bubble, not the content padding.
        assertEquals(content.right - bubbleEndInset, bounds.right)
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
