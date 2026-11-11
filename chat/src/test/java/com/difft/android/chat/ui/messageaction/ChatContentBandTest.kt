package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w800dp-h1400dp-mdpi")
class ChatContentBandTest {

    private var controller: ActivityController<FragmentActivity>? = null

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
    }

    private fun list(): RecyclerView {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().also { controller = it }.get()
        val rv = RecyclerView(activity)
        activity.setContentView(FrameLayout(activity).apply {
            addView(rv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LIST_HEIGHT).apply { topMargin = LIST_TOP })
        })
        rv.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(LIST_HEIGHT, View.MeasureSpec.EXACTLY))
        (rv.parent as View).layout(0, 0, 800, 1400)
        rv.layout(0, LIST_TOP, 800, LIST_TOP + LIST_HEIGHT)
        return rv
    }

    private fun visibleRect(view: View) = Rect().also { view.getGlobalVisibleRect(it) }

    @Test
    fun `without floating chrome the band is the visible rect`() {
        val rv = list()
        val band = Rect()

        assertTrue(ChatContentBand.onScreen(rv, band))
        assertEquals(visibleRect(rv), band)
    }

    @Test
    fun `the floating header and input are cut from the band`() {
        val rv = list()
        val visible = visibleRect(rv)
        ChatContentBand.setInsets(rv, ChatChromeInsets(top = 150, bottom = 250))
        val band = Rect()

        assertTrue(ChatContentBand.onScreen(rv, band))
        assertEquals(Rect(visible.left, visible.top + 150, visible.right, visible.bottom - 250), band)
    }

    @Test
    fun `a list covered entirely by chrome has no band`() {
        val rv = list()
        ChatContentBand.setInsets(rv, ChatChromeInsets(top = 600, bottom = 400))
        val band = Rect()

        assertFalse(ChatContentBand.onScreen(rv, band))
        assertTrue(band.isEmpty)
    }

    @Test
    fun `the selection clip box drops the chrome though the list draws under its padding`() {
        val rv = list()
        rv.clipToPadding = false
        rv.setPadding(0, 153, 0, 253)
        ChatContentBand.setInsets(rv, ChatChromeInsets(top = 150, bottom = 250))
        val screen = IntArray(2).also(rv::getLocationOnScreen)

        val box = rv.clipBoxOnScreen(Rect())

        assertEquals(Rect(screen[0], screen[1] + 150, screen[0] + 800, screen[1] + LIST_HEIGHT - 250), box)
    }

    @Test
    fun `a list without chrome keeps its padding clip`() {
        val rv = list()
        rv.clipToPadding = true
        rv.setPadding(0, 3, 0, 3)
        val screen = IntArray(2).also(rv::getLocationOnScreen)

        val box = rv.clipBoxOnScreen(Rect())

        assertEquals(Rect(screen[0], screen[1] + 3, screen[0] + 800, screen[1] + LIST_HEIGHT - 3), box)
    }

    private companion object {
        const val LIST_TOP = 100
        const val LIST_HEIGHT = 1000
    }
}
