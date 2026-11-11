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

    
    @Test
    fun `dismiss returns false before the behavior is configured`() {
        assertFalse(newController().dismiss())
    }

    
    @Test
    fun `dismiss does not touch the activity before the behavior is configured`() {
        newController().dismiss()

        verify(exactly = 0) { activity.finish() }
    }

    
    @Test
    fun `release is a no-op before the behavior is configured`() {
        newController().release()
    }
}
