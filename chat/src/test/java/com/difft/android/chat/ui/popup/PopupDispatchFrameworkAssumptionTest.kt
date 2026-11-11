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






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopupDispatchFrameworkAssumptionTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    
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
