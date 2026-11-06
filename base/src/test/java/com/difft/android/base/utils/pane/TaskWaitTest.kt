package com.difft.android.base.utils.pane

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.widget.ComposeDialogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [TaskWait] / [withTaskWait], the claim the wait-showing utilities (save to notes, save
 * attachment, avatar preview) take: with a page's [PageWait] the dialog goes with the page's view
 * while the task keeps running; without one, only the task's own token is released.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskWaitTest {

    class Page : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    @After
    fun tearDown() = ComposeDialogManager.dismissWait()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun activityWithPage(): Pair<FragmentActivity, Page> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = Page()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, page, "page").commitNow()
        return activity to page
    }

    private fun FragmentActivity.dialogCount(): Int = findViewById<ViewGroup>(android.R.id.content).childCount

    @Test
    fun `without a page wait the task releases only its own claim`() {
        val (activity, _) = activityWithPage()
        val other = ComposeDialogManager.showWait(activity, "")

        val claim = TaskWait.claim(activity, wait = null)
        claim.release()
        claim.release()

        assertTrue("another flow's claim stays", ComposeDialogManager.isWaitShowing(other))
    }

    @Test
    fun `a second release does not close a later claim of the same page wait`() {
        val (activity, page) = activityWithPage()
        val wait = PageWait(page)

        // Save to notes releases in its result handler and again in finally.
        val first = TaskWait.claim(activity, wait)
        first.release()
        val second = TaskWait.claim(activity, wait) // the user saved again in between
        first.release()

        assertTrue("the second save's wait stays up", wait.held)
        second.release()
        assertFalse(wait.held)
    }

    @Test
    fun `with a page wait the dialog goes with the page view while the task still runs`() {
        val (activity, page) = activityWithPage()
        val baseline = activity.dialogCount()
        val wait = PageWait(page)
        val work = CompletableDeferred<Unit>()
        var finished = false

        // Like save to notes: the work outlives the page on an application-wide scope.
        CoroutineScope(Dispatchers.Main).launch {
            withTaskWait(activity, wait) { work.await() }
            finished = true
        }
        idle()
        assertTrue(wait.held)
        assertEquals(baseline + 1, activity.dialogCount())

        activity.supportFragmentManager.beginTransaction().remove(page).commitNow()
        assertFalse("released with the view", wait.held)
        assertEquals(baseline - 1, activity.dialogCount())

        val other = ComposeDialogManager.showWait(activity, "")
        work.complete(Unit)
        idle()
        assertTrue(finished)
        assertTrue("the late release closes nothing else", ComposeDialogManager.isWaitShowing(other))
    }
}
