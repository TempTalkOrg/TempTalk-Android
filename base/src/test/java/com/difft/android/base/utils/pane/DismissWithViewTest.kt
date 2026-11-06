package com.difft.android.base.utils.pane

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * [dismissWithView]: [ComposeDialogManager] mounts a page's dialogs on the Activity, which for a
 * detail page is the home screen, so a page that is popped or cleared must take its dialog with
 * it (its callbacks would run against a detached page), while a page that only stops (picker
 * Activity, background) keeps it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DismissWithViewTest {

    class Page : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    private lateinit var controller: ActivityController<FragmentActivity>
    private val activity: FragmentActivity get() = controller.get()
    private var dismissCalls = 0

    private fun pageOnScreen(): Page {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val page = Page()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, page, "page").commitNow()
        return page
    }

    private fun removePage(page: Page) =
        activity.supportFragmentManager.beginTransaction().remove(page).commitNow()

    private fun rootChildren(): Int = activity.findViewById<ViewGroup>(android.R.id.content).childCount

    private fun showMessage(): ComposeDialog? = ComposeDialogManager.showMessageDialog(
        context = activity,
        title = "Title",
        confirmText = "Yes",
        cancelText = "No",
        onDismiss = { dismissCalls++ },
    )

    private fun showBottom(): ComposeDialog =
        ComposeDialogManager.showBottomDialog(activity, onDismiss = { dismissCalls++ }) {}

    @Test
    fun `the dialog closes when the page's view is destroyed`() {
        val page = pageOnScreen()
        val baseline = rootChildren()
        val dialog = page.dismissWithView(showMessage())
        assertEquals("mounted", baseline + 1, rootChildren())

        removePage(page)

        assertFalse(dialog!!.isShowing)
        assertEquals("page view and dialog both gone", baseline - 1, rootChildren())
        assertEquals(1, dismissCalls)
    }

    @Test
    fun `the dialog stays up while the page is only stopped`() {
        val page = pageOnScreen()
        val baseline = rootChildren()
        val dialog = page.dismissWithView(showBottom())

        // A picker Activity on top, or the app in the background.
        controller.pause().stop()
        assertTrue(dialog.isShowing)
        controller.start().resume()

        assertTrue(dialog.isShowing)
        assertEquals(baseline + 1, rootChildren())
        assertEquals(0, dismissCalls)
    }

    @Test
    fun `a dialog the user already closed is not dismissed again with the page`() {
        val page = pageOnScreen()
        val message = page.dismissWithView(showMessage())
        val bottom = page.dismissWithView(showBottom())
        message!!.dismiss()
        bottom.dismiss()
        assertEquals(2, dismissCalls)

        removePage(page)

        assertEquals("no second onDismiss from the page closing", 2, dismissCalls)
    }

    @Test
    fun `dismissing again after the page closed it is harmless`() {
        val page = pageOnScreen()
        val baseline = rootChildren()
        val dialog = page.dismissWithView(showMessage())
        removePage(page)

        dialog!!.dismiss()

        assertEquals(1, dismissCalls)
        assertEquals(baseline - 1, rootChildren())
    }

    @Test
    fun `a dialog shown for a page that is already gone closes at once`() {
        val page = pageOnScreen()
        val baseline = rootChildren()
        removePage(page)

        // An async callback that outlived the page shows its dialog late.
        val dialog = page.dismissWithView(showBottom())

        assertFalse(dialog.isShowing)
        assertEquals(baseline - 1, rootChildren())
        assertEquals(1, dismissCalls)
    }
}
