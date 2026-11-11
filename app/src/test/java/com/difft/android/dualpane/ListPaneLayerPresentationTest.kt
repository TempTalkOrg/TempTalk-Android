package com.difft.android.dualpane

import android.os.Bundle
import android.os.Looper
import android.view.View
import com.difft.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp")
class ListPaneLayerPresentationTest {
    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host get() = controller!!.get()
    private val layer get() = host.listLayer
    private val container get() = host.findViewById<View>(R.id.list_layer)

    @After
    fun tearDown() { controller?.pause()?.stop()?.destroy() }

    private fun launch(saved: Bundle? = null) {
        val activity = Robolectric.buildActivity(PaneNavigatorTestHost::class.java)
        controller = if (saved == null) activity.setup() else activity.setup(saved)
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    @Test
    fun `cover starts before attach and lasts through every visible close frame`() {
        launch()
        layer.open(ListPaneLayerRoot.SEARCH)
        assertTrue("the request protects chrome before its queued fragment attach", layer.coversList.value)
        settle()
        assertEquals(View.VISIBLE, container.visibility)
        layer.close()
        assertFalse(layer.active)
        assertTrue(layer.coversList.value)
        val looper = shadowOf(Looper.getMainLooper())
        var steps = 0
        while (container.visibility == View.VISIBLE && steps < 200) {
            assertTrue("a visible outgoing page still owns the list's chrome", layer.coversList.value)
            looper.runOneTask()
            steps++
        }
        assertTrue("the close must actually finish", container.visibility != View.VISIBLE)
        assertFalse(layer.coversList.value)
        settle()
        assertEquals(View.GONE, container.visibility)
    }

    @Test
    fun `a saved-state close retains cover until its visible layer can be removed`() {
        launch()
        layer.open(ListPaneLayerRoot.SEARCH)
        settle()
        controller!!.pause().saveInstanceState(Bundle()).stop()
        layer.close()
        settle()
        assertFalse(layer.active)
        assertEquals(View.VISIBLE, container.visibility)
        assertTrue(layer.coversList.value)
        controller!!.start().resume()
        settle()
        assertEquals(View.GONE, container.visibility)
        assertFalse(layer.coversList.value)
    }

    @Test
    fun `an active restore flag without a layer host cannot retain cover`() {
        launch(Bundle().apply { putBoolean("list_layer_active", true) })
        assertFalse(layer.active)
        assertFalse(layer.coversList.value)
        assertEquals(View.GONE, container.visibility)
    }

    @Test
    fun `one-pane detail retains its existing cover signal until its closing stack clears`() {
        launch()
        val nav = host.navigator
        nav.replaceRoot(PaneNavigator.TAB_CHATS, StackPageFragment.newInstance("conv:a", handlesBack = false), selectionId = "a")
        settle()
        assertTrue(nav.detailCoversList.value)
        nav.clear(PaneNavigator.TAB_CHATS)
        assertTrue("the detail signal already includes close presentation", nav.detailCoversList.value)
        settle()
        assertEquals(View.GONE, host.findViewById<View>(R.id.detail_pane).visibility)
        assertFalse(nav.detailCoversList.value)
    }
}
