package com.difft.android.chat.ui.popup

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.BaseActivity
import com.difft.android.base.widget.InsetAwareConstraintLayout
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * X1, X2 — the two guards that stand between the synthesised dispatch and a crash.
 *
 * X1 is the regression guard for Crashlytics issue `32a90db4`: the registered listener's body hides
 * the action panel, i.e. it mutates the view tree, and doing that from inside an
 * `onApplyWindowInsets` callback is what crashed. X2 covers the popup-specific case the full-screen
 * widget never had to handle — the sheet calls `finish()` from `STATE_HIDDEN` and hides the keyboard
 * from `onSlide`, so a pending IME-hidden dispatch can land in an Activity that is already going
 * away.
 */
@RunWith(RobolectricTestRunner::class)
class PopupKeyboardPanelDispatchGuardTest {

    private val hostActivity = mockk<BaseActivity>(relaxed = true)

    private lateinit var controllerHost: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var victim: View
    private lateinit var controller: PopupKeyboardPanelController

    @Before
    fun setUp() {
        controllerHost = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controllerHost.get()
        root = FrameLayout(activity)
        victim = View(activity)
        root.addView(victim)
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()

        controller = PopupKeyboardPanelController(hostActivity, root)
    }

    @After
    fun tearDown() {
        clearMocks(hostActivity)
    }

    private fun imeVisibleInsets() = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 900))
        .setVisible(WindowInsetsCompat.Type.ime(), true)
        .build()

    /**
     * X1 — a listener that mutates the view tree must never run inside the insets callback. The one
     * frame of deferral is mandatory, not stylistic.
     */
    @Test
    fun `X1 a listener that mutates the view tree runs after the insets callback returns`() {
        var insideInsetsCallback = false
        var mutatedInsideInsetsCallback = false

        controller.addKeyboardStateListener(object :
            InsetAwareConstraintLayout.KeyboardStateListener {
            override fun onKeyboardShown() {
                mutatedInsideInsetsCallback = mutatedInsideInsetsCallback || insideInsetsCallback
                root.removeView(victim)
            }

            override fun onKeyboardHidden() = Unit
            override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) = Unit
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            insideInsetsCallback = true
            controller.onWindowInsets(
                navigationBarPx = 60,
                imeHeightPx = 900,
                imeVisible = true,
                maxHeightPx = 0,
            )
            insideInsetsCallback = false
            insets
        }

        ViewCompat.dispatchApplyWindowInsets(root, imeVisibleInsets())

        assertEquals("the view tree must be untouched while insets are being applied", 1, root.childCount)

        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(mutatedInsideInsetsCallback)
        assertEquals(0, root.childCount)
    }

    /** X2 — a dispatch posted before the Activity starts finishing must be dropped, not delivered. */
    @Test
    fun `X2 a pending dispatch is dropped when the host activity is finishing`() {
        val events = mutableListOf<String>()
        controller.addKeyboardStateListener(object :
            InsetAwareConstraintLayout.KeyboardStateListener {
            override fun onKeyboardShown() {
                events += "shown"
            }

            override fun onKeyboardHidden() {
                events += "hidden"
            }

            override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) {
                events += "ended=$isKeyboardVisible"
            }
        })

        controller.onWindowInsets(
            navigationBarPx = 60,
            imeHeightPx = 900,
            imeVisible = true,
            maxHeightPx = 0,
        )
        // The sheet reached STATE_HIDDEN and finished the Activity before the frame ran.
        every { hostActivity.isFinishing } returns true
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<String>(), events)
    }

    /** X2's second half — the same drop when the window has gone away under the posted runnable. */
    @Test
    fun `X2 a pending dispatch is dropped when the root view is detached`() {
        val events = mutableListOf<String>()
        controller.addKeyboardStateListener(object :
            InsetAwareConstraintLayout.KeyboardStateListener {
            override fun onKeyboardShown() {
                events += "shown"
            }

            override fun onKeyboardHidden() = Unit
            override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) = Unit
        })

        controller.onWindowInsets(
            navigationBarPx = 60,
            imeHeightPx = 900,
            imeVisible = true,
            maxHeightPx = 0,
        )
        controllerHost.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<String>(), events)
    }
}
