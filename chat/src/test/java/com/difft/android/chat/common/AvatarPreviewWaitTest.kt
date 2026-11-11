package com.difft.android.chat.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.widget.ComposeDialogManager
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AvatarPreviewWaitTest {

    class Page : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    @After
    fun tearDown() = ComposeDialogManager.dismissWait()

    private fun FragmentActivity.dialogCount(): Int = findViewById<ViewGroup>(android.R.id.content).childCount

    @Test
    fun `a failed download through the page's wait releases it`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = Page().also { activity.supportFragmentManager.beginTransaction().add(android.R.id.content, it).commitNow() }
        val baseline = activity.dialogCount()
        val wait = PageWait(page)
        var shownDuringDownload = false

        runCatching {
            runBlocking {
                AvatarPreviewLauncher.withWait(activity, wait) {
                    shownDuringDownload = wait.held && activity.dialogCount() == baseline + 1
                    throw IOException("download failed")
                }
            }
        }

        assertTrue(shownDuringDownload)
        assertFalse(wait.held)
        assertEquals(baseline, activity.dialogCount())
    }

    @Test
    fun `a second tap while the first preview runs starts nothing, and the wait lasts the download`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = Page().also { activity.supportFragmentManager.beginTransaction().add(android.R.id.content, it).commitNow() }
        val baseline = activity.dialogCount()
        val flight = AvatarPreviewFlight(page)
        val cacheCheck = CompletableDeferred<Unit>()
        val download = CompletableDeferred<Unit>()
        var downloads = 0
        var viewers = 0
        
        val preview: suspend (PageWait) -> Unit = { previewWait ->
            cacheCheck.await()
            AvatarPreviewLauncher.withWait(activity, previewWait) {
                downloads++
                download.await()
            }
            viewers++
        }

        
        
        assertTrue(flight.launch(preview))
        assertFalse("still checking the cache", flight.launch(preview))
        cacheCheck.complete(Unit)
        assertEquals(1, downloads)
        assertEquals("the wait is up during the download", baseline + 1, activity.dialogCount())

        download.complete(Unit)
        assertEquals(1, viewers)
        assertEquals(baseline, activity.dialogCount())
        assertTrue("a new tap after it ended previews again", flight.launch { })
    }

    @Test
    fun `without a page the dialog is released by its token`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val baseline = activity.dialogCount()
        val other = ComposeDialogManager.showWait(activity, "")

        runBlocking { AvatarPreviewLauncher.withWait(activity, wait = null) { "file" } }

        assertTrue("another claim's dialog stays", ComposeDialogManager.isWaitShowing(other))
        ComposeDialogManager.dismissWait(other)
        assertEquals(baseline, activity.dialogCount())
    }
}
