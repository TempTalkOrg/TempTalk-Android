package com.difft.android.base.utils.pane

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.widget.ComposeDialogManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * [PageWait]: a detail page's wait dialog is mounted on the home screen's Activity, so a page
 * closed mid-request (group disbanded, conversation replaced, fold) must not leave it blocking the
 * screen, and releasing it must never close a dialog another claim still holds.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageWaitTest {

    class Page : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    @After
    fun tearDown() = ComposeDialogManager.dismissWait()

    private fun activityWithPage(): Pair<FragmentActivity, Page> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = Page()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, page, "page").commitNow()
        return activity to page
    }

    private fun FragmentActivity.dialogCount(): Int =
        findViewById<ViewGroup>(android.R.id.content).childCount

    @Test
    fun `the claim is released when the page's view is destroyed mid-request`() {
        val (activity, page) = activityWithPage()
        val baseline = activity.dialogCount()
        val wait = PageWait(page)

        wait.show()
        assertTrue(wait.held)
        assertEquals("mounted", baseline + 1, activity.dialogCount())

        // The page is removed before its request returns (closeConversation, replaced stack).
        activity.supportFragmentManager.beginTransaction().remove(page).commitNow()

        assertFalse(wait.held)
        assertEquals("released with the view", baseline - 1, activity.dialogCount())
        wait.dismiss() // the late DismissWait is harmless
    }

    @Test
    fun `dismissing by token leaves another claim's dialog up`() {
        val (activity, page) = activityWithPage()
        val other = ComposeDialogManager.showWait(activity, "")
        val wait = PageWait(page)

        wait.show()
        wait.dismiss()

        assertTrue("the other claim still holds the dialog", ComposeDialogManager.isWaitShowing(other))
        ComposeDialogManager.dismissWait(other)
        assertFalse(ComposeDialogManager.isWaitShowing(other))
    }

    @Test
    fun `two flows on one page each release only their own wait`() {
        val (activity, page) = activityWithPage()
        val baseline = activity.dialogCount()
        val recallWait = PageWait(page)
        val addFriendWait = PageWait(page)

        recallWait.show()
        addFriendWait.show()
        addFriendWait.dismiss()

        assertTrue("the recall wait stays up", recallWait.held)
        assertEquals(baseline + 1, activity.dialogCount())
        recallWait.dismiss()
        assertEquals(baseline, activity.dialogCount())
    }

    @Test
    fun `showing twice holds one claim`() {
        val (activity, page) = activityWithPage()
        val baseline = activity.dialogCount()
        val wait = PageWait(page)

        wait.update(true)
        wait.update(true)
        assertEquals(baseline + 1, activity.dialogCount())
        wait.update(false)

        assertFalse(wait.held)
        assertEquals("one release closes it", baseline, activity.dialogCount())
    }

    @Test
    fun `a claim whose dialog is gone from the slot is taken again on the next show`() {
        val (activity, page) = activityWithPage()
        val baseline = activity.dialogCount()
        val wait = PageWait(page)
        wait.show()

        // The shared slot dropped our dialog (another screen's showWait evicts it the same way;
        // a second resumed Activity's spinner does not settle under Robolectric).
        ComposeDialogManager.dismissWait()
        assertEquals("gone", baseline, activity.dialogCount())

        wait.show()

        assertEquals("mounted again", baseline + 1, activity.dialogCount())
        wait.dismiss()
        assertEquals(baseline, activity.dialogCount())
    }
}
