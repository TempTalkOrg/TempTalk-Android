package com.difft.android.chat.ui.popup

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FA4, FA5 — Framework Assumption Tests against real framework classes, no mocks.
 *
 * [PopupKeyboardPanelController]'s dispatch design rests on two `View` behaviours rather than on
 * anything this project owns, so they are pinned rather than argued:
 *
 *  - FA4: `View.post` is FIFO and `removeCallbacks` genuinely cancels, which is what makes
 *    "onKeyboardShown before onKeyboardAnimationEnded" and "at most one animation-end per
 *    transition" true.
 *  - FA5: a posted runnable can still run after the host Activity is destroyed, and observes
 *    `isAttachedToWindow == false` — the guard that keeps a listener body from mutating a dead view
 *    tree (Crashlytics issue 32a90db4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopupDispatchFrameworkAssumptionTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** FA4 — posting order survives a cancel-and-re-post of the second runnable. */
    @Test
    fun `FA4 view post is FIFO and removeCallbacks cancels exactly one pending runnable`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = FrameLayout(activity)
        activity.setContentView(view)
        idle()

        val order = mutableListOf<String>()
        val a = Runnable { order += "A" }
        val b = Runnable { order += "B" }

        view.post(a)
        view.post(b)
        view.removeCallbacks(b)
        view.post(b)
        idle()

        assertEquals(listOf("A", "B"), order)
    }

    /** FA5 — the posted dispatch outlives the Activity, and the attachment check catches it. */
    @Test
    fun `FA5 a runnable posted before destroy observes an unattached view`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val view: View = FrameLayout(activity)
        activity.setContentView(view)
        idle()

        var attachedWhenRun: Boolean? = null
        view.post { attachedWhenRun = view.isAttachedToWindow }

        controller.pause().stop().destroy()
        idle()

        assertFalse(
            "a runnable that survives destroy must see the view detached",
            attachedWhenRun ?: true
        )
    }
}
