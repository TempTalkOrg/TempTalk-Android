package com.difft.android.chat.ui.popup

import android.view.View
import com.difft.android.base.BaseActivity
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the two guard contracts that the sheet extraction turned from inline Activity code into a
 * controller API — the only new API shape the move introduces. Everything else in the moved body
 * is byte-identical to its pre-move form.
 *
 * Before the move, back-press read `::bottomSheetBehavior.isInitialized` inline and fell through to
 * `finish()`; now it reads [PopupChatSheetController.dismiss]'s return value. An inverted boolean
 * here would silently swap "animate the sheet away" for "finish instantly", so the uninitialised
 * branch is asserted rather than argued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopupChatSheetControllerUninitialisedTest {

    private val activity = mockk<BaseActivity>(relaxed = true)

    private fun newController(): PopupChatSheetController {
        val context = RuntimeEnvironment.getApplication()
        return PopupChatSheetController(
            activity = activity,
            views = PopupSheetViews(
                coordinatorRoot = View(context),
                bottomSheet = View(context),
                scrim = View(context),
                dragHandle = View(context),
            ),
        )
    }

    @After
    fun tearDown() {
        clearMocks(activity)
    }

    /** S5(c): back-press before the behavior is configured must fall through to `finish()`. */
    @Test
    fun `dismiss returns false before the behavior is configured`() {
        assertFalse(newController().dismiss())
    }

    /** The controller must not finish the Activity itself on the uninitialised path. */
    @Test
    fun `dismiss does not touch the activity before the behavior is configured`() {
        newController().dismiss()

        verify(exactly = 0) { activity.finish() }
    }

    /** `onDestroy` calls `release()` unconditionally; it must be safe before any setup. */
    @Test
    fun `release is a no-op before the behavior is configured`() {
        newController().release()
    }
}
