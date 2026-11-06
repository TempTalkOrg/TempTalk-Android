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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [PageOperations]: a write tracked by key is visible as running to any page (a page restored
 * after a fold included), and a page re-reads its value whenever no write for the key runs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageOperationsTest {

    class Page : Fragment() {
        var reads = 0
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            onOperationEnded(KEY) { reads++ }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun track(key: String, work: CompletableDeferred<Unit>) {
        CoroutineScope(Dispatchers.Main).launch { PageOperations.track(key) { work.await() } }
        idle()
    }

    @Test
    fun `overlapping writes keep the key running until the last ends`() {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        assertFalse(PageOperations.running("k1").value)

        track("k1", first)
        track("k1", second)
        assertTrue(PageOperations.running("k1").value)

        first.complete(Unit)
        idle()
        assertTrue(PageOperations.running("k1").value)
        second.complete(Unit)
        idle()
        assertFalse(PageOperations.running("k1").value)
    }

    @Test
    fun `a page re-reads on its first value and whenever a write ends`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = Page()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, page).commitNow()
        idle()
        assertEquals("the first not-running value reads", 1, page.reads)

        val work = CompletableDeferred<Unit>()
        track(KEY, work)
        assertEquals(1, page.reads)
        work.complete(Unit)
        idle()

        assertEquals("the outcome stored by the write is read", 2, page.reads)
    }

    private companion object {
        const val KEY = "test-operation"
    }
}
