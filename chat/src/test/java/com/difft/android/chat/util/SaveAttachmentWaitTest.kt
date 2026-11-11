package com.difft.android.chat.util

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.widget.ComposeDialogManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveAttachmentWaitTest {

    class Page : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    @After
    fun tearDown() = ComposeDialogManager.dismissWait()

    private val missing = SaveAttachmentUtil.Attachment(
        uri = Uri.parse("file:///nonexistent/attachment.jpg"),
        contentType = "image/jpeg",
        date = 0L,
        shouldShowToast = false,
    )

    private fun FragmentActivity.dialogCount(): Int = findViewById<ViewGroup>(android.R.id.content).childCount

    @Test
    fun `a save without a page wait leaves another claim's wait up`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val other = ComposeDialogManager.showWait(activity, "")

        
        runCatching { runBlocking { SaveAttachmentUtil.saveWithUI(activity, missing) } }

        assertTrue("another flow's wait stays", ComposeDialogManager.isWaitShowing(other))
    }

    @Test
    fun `a save through the page's wait releases it`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = Page().also { activity.supportFragmentManager.beginTransaction().add(android.R.id.content, it).commitNow() }
        val baseline = activity.dialogCount()
        val wait = PageWait(page)

        runCatching { runBlocking { SaveAttachmentUtil.saveWithUI(activity, missing, wait = wait) } }

        assertFalse(wait.held)
        assertEquals(baseline, activity.dialogCount())
    }
}
