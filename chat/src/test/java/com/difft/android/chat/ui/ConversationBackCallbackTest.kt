package com.difft.android.chat.ui

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationBackCallbackTest {

    private var menuShowing = false
    private var selecting = false
    private var menuDismissed = 0
    private var selectionExited = 0
    private var pageClosed = 0
    private var hostBacks = 0

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val dispatcher = activity.onBackPressedDispatcher

    private fun register(hosted: Boolean): ConversationBackCallback {
        dispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                hostBacks++
            }
        })
        return ConversationBackCallback(
            hosted = hosted,
            isMenuShowing = { menuShowing },
            isSelecting = { selecting },
            dismissMenu = {
                menuDismissed++
                menuShowing = false
            },
            exitSelection = { selectionExited++ },
            closePage = { pageClosed++ },
            passOn = { dispatcher.onBackPressed() },
        ).also {
            it.refreshEnabled()
            dispatcher.addCallback(activity, it)
        }
    }

    @Test
    fun `hosted without menu or selection is disabled, so Back reaches the host`() {
        val callback = register(hosted = true)
        assertFalse(callback.isEnabled)

        dispatcher.onBackPressed()

        assertEquals(1, hostBacks)
        assertEquals("no page-closing fallback under a host", 0, pageClosed)
    }

    @Test
    fun `hosted with the action menu showing closes the menu first, then lets Back through`() {
        val callback = register(hosted = true)
        menuShowing = true
        callback.refreshEnabled()
        assertTrue(callback.isEnabled)

        dispatcher.onBackPressed()
        assertEquals(1, menuDismissed)
        assertEquals(0, hostBacks)
        assertFalse(callback.isEnabled)

        dispatcher.onBackPressed()
        assertEquals(1, hostBacks)
    }

    @Test
    fun `hosted in selection mode leaves selection mode`() {
        val callback = register(hosted = true)
        selecting = true
        callback.refreshEnabled()

        dispatcher.onBackPressed()

        assertEquals(1, selectionExited)
        assertEquals(0, hostBacks)
    }

    @Test
    fun `a stale hosted callback passes Back on instead of swallowing it`() {
        val callback = register(hosted = true)
        menuShowing = true
        callback.refreshEnabled()
        menuShowing = false 

        dispatcher.onBackPressed()

        assertEquals(1, hostBacks)
        assertFalse(callback.isEnabled)
    }

    @Test
    fun `in a shell it stays enabled and closes the page`() {
        val callback = register(hosted = false)
        callback.refreshEnabled()
        assertTrue(callback.isEnabled)

        dispatcher.onBackPressed()

        assertEquals(1, pageClosed)
        assertEquals(0, hostBacks)
    }
}
