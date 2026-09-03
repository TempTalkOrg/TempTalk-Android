package com.difft.android.chat.ui.popup

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.difft.android.base.BaseActivity
import com.difft.android.base.widget.InsetAwareConstraintLayout
import io.mockk.clearMocks
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * D1-D4 — the IME edge state machine of [PopupKeyboardPanelController].
 *
 * The popup synthesises the keyboard-state callbacks from its ordinary window-insets listener
 * instead of a `WindowInsetsAnimationCompat.Callback`, so the edge detection and the coalesced
 * one-frame dispatch ARE the mechanism that dismisses the action panel when the keyboard appears.
 * These rows pin it against a real `View` and a real main `Looper`; the geometry half is left
 * unattached (no sheet), so only dispatch is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopupKeyboardPanelControllerDispatchTest {

    private class Recorder : InsetAwareConstraintLayout.KeyboardStateListener {
        val events = mutableListOf<String>()
        override fun onKeyboardShown() {
            events += "shown"
        }

        override fun onKeyboardHidden() {
            events += "hidden"
        }

        override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) {
            events += "ended=$isKeyboardVisible"
        }
    }

    private val hostActivity = mockk<BaseActivity>(relaxed = true)

    private lateinit var controllerHost: ActivityController<Activity>
    private lateinit var root: View
    private lateinit var controller: PopupKeyboardPanelController
    private val recorder = Recorder()

    @Before
    fun setUp() {
        controllerHost = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controllerHost.get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        idle()

        controller = PopupKeyboardPanelController(hostActivity, root)
        controller.addKeyboardStateListener(recorder)
    }

    @After
    fun tearDown() {
        clearMocks(hostActivity)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun insets(imeVisible: Boolean, imeHeight: Int, navBar: Int = 60) =
        controller.onWindowInsets(
            navigationBarPx = navBar,
            imeHeightPx = imeHeight,
            imeVisible = imeVisible,
            maxHeightPx = 0,
        )

    /** The dispatch guard requires a live window; without it every row below would silently pass. */
    @Test
    fun `the root view is attached so dispatch is not dropped`() {
        assertTrue(root.isAttachedToWindow)
    }

    /**
     * D1 — visibility edges only. A repeated identical IME-visible pass is not an edge, so the
     * fragment's panel-dismissal listener runs once per real transition, not once per inset pass.
     */
    @Test
    fun `D1 each keyboard visibility transition dispatches exactly once`() {
        insets(imeVisible = false, imeHeight = 0)
        insets(imeVisible = true, imeHeight = 900)
        insets(imeVisible = true, imeHeight = 900)
        insets(imeVisible = false, imeHeight = 0)
        idle()

        assertEquals(1, recorder.events.count { it == "shown" })
        assertEquals(1, recorder.events.count { it == "hidden" })
    }

    /**
     * D2 — coalescing. A device that delivers interpolated IME insets produces several height-only
     * changes inside one transition; "animation ended" must fire once, one frame after the last of
     * them, with no hard-coded duration.
     */
    @Test
    fun `D2 interpolated IME heights collapse to a single animation-ended callback`() {
        insets(imeVisible = true, imeHeight = 300)
        insets(imeVisible = true, imeHeight = 600)
        insets(imeVisible = true, imeHeight = 900)
        idle()

        assertEquals(1, recorder.events.count { it == "ended=true" })
    }

    /** D3 — ordering: the fragment's listener body relies on "shown" preceding "ended". */
    @Test
    fun `D3 keyboard shown is dispatched before animation ended`() {
        insets(imeVisible = true, imeHeight = 900)
        idle()

        assertEquals(listOf("shown", "ended=true"), recorder.events)
    }

    /**
     * D4 — a nav-bar-only inset pass is not an IME edge. Without the early return, every
     * system-bar change would post a dispatch and the panel would churn.
     */
    @Test
    fun `D4 insets passes that do not move the IME dispatch nothing`() {
        insets(imeVisible = false, imeHeight = 0, navBar = 60)
        insets(imeVisible = false, imeHeight = 0, navBar = 120)

        assertTrue(
            "no runnable may be posted for a non-IME insets pass",
            shadowOf(Looper.getMainLooper()).isIdle
        )

        idle()
        assertEquals(emptyList<String>(), recorder.events)
    }

    /** Listeners removed by the fragment's onDestroyView must stop receiving callbacks. */
    @Test
    fun `a removed listener is not dispatched to`() {
        controller.removeKeyboardStateListener(recorder)

        insets(imeVisible = true, imeHeight = 900)
        idle()

        assertEquals(emptyList<String>(), recorder.events)
    }

    /** release() clears the listener list and cancels the pending animation-end runnable. */
    @Test
    fun `release drops listeners and the pending animation-end runnable`() {
        insets(imeVisible = true, imeHeight = 900)
        controller.release()
        idle()

        assertEquals(emptyList<String>(), recorder.events)
    }
}
