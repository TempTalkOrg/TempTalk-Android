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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [launchOutlivingPage]: a group leave, key rotation or friend removal started from a detail page
 * finishes its server write and local persist even when a fold recreation destroys the page
 * mid-way; its UI follow-ups run only while the page is still showing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PageOperationTest {

    class Page : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    private val server = CompletableDeferred<Int>()
    private var persisted = 0
    private var uiFollowUps = 0

    private fun start(page: Fragment) {
        page.launchOutlivingPage {
            server.await() // the server call is still in flight
            persisted++ // the local persist / key distribution
            onPage { uiFollowUps++ } // spinner, toast, close
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the persist step runs after the page is destroyed mid-operation, its UI does not`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val page = Page()
        controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, page).commitNow()
        start(page)
        idle()

        controller.pause().stop().destroy() // fold recreation tears the page down
        server.complete(0)
        idle()

        assertEquals(1, persisted)
        assertEquals(0, uiFollowUps)
    }

    @Test
    fun `with the page still showing the UI follow-up runs`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val page = Page()
        controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, page).commitNow()
        start(page)

        server.complete(0)
        idle()

        assertEquals(1, persisted)
        assertEquals(1, uiFollowUps)
        assertTrue(page.isAdded)
    }
}
