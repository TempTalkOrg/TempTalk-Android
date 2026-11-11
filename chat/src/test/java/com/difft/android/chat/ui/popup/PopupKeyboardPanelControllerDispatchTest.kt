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

    
    @Test
    fun `the root view is attached so dispatch is not dropped`() {
        assertTrue(root.isAttachedToWindow)
    }

    



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

    




    @Test
    fun `D2 interpolated IME heights collapse to a single animation-ended callback`() {
        insets(imeVisible = true, imeHeight = 300)
        insets(imeVisible = true, imeHeight = 600)
        insets(imeVisible = true, imeHeight = 900)
        idle()

        assertEquals(1, recorder.events.count { it == "ended=true" })
    }

    
    @Test
    fun `D3 keyboard shown is dispatched before animation ended`() {
        insets(imeVisible = true, imeHeight = 900)
        idle()

        assertEquals(listOf("shown", "ended=true"), recorder.events)
    }

    



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

    
    @Test
    fun `a removed listener is not dispatched to`() {
        controller.removeKeyboardStateListener(recorder)

        insets(imeVisible = true, imeHeight = 900)
        idle()

        assertEquals(emptyList<String>(), recorder.events)
    }

    
    @Test
    fun `release drops listeners and the pending animation-end runnable`() {
        insets(imeVisible = true, imeHeight = 900)
        controller.release()
        idle()

        assertEquals(emptyList<String>(), recorder.events)
    }
}
