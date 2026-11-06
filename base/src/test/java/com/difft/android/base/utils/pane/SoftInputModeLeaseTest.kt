package com.difft.android.base.utils.pane

import android.view.WindowManager.LayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * [SoftInputModeLease] below API 30: the home screen's window resizes for the keyboard while any
 * page needs it, so a conversation view destroyed in another tab (closed, disbanded, replaced) no
 * longer writes ADJUST_NOTHING under a keyboard page that is still shown.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class SoftInputModeLeaseTest {

    private val resize = LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    private val nothingHidden = LayoutParams.SOFT_INPUT_ADJUST_NOTHING or LayoutParams.SOFT_INPUT_STATE_HIDDEN

    private fun activity(): FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private fun FragmentActivity.mode(): Int = window.attributes.softInputMode

    private fun FragmentActivity.adjust(): Int = mode() and LayoutParams.SOFT_INPUT_MASK_ADJUST

    @Test
    fun `a conversation going away leaves the keyboard page's resize in place`() {
        val activity = activity()
        val chatsRoot = Any()
        val hiddenGroupRoot = Any()
        val searchPage = Any()

        SoftInputModeLease.acquireResize(activity, chatsRoot, conversationRoot = true)
        SoftInputModeLease.acquireResize(activity, hiddenGroupRoot, conversationRoot = true)
        SoftInputModeLease.acquireResize(activity, searchPage)
        assertEquals(resize, activity.adjust())

        SoftInputModeLease.release(activity, chatsRoot) // replaced by a notification
        SoftInputModeLease.release(activity, hiddenGroupRoot) // group disbanded
        assertEquals("the search page still resizes", resize, activity.adjust())

        SoftInputModeLease.release(activity, searchPage)
        assertEquals("as a conversation view leaving wrote before", nothingHidden, activity.mode())
    }

    @Test
    fun `a conversation root keeps today's mode while it holds the lease`() {
        val activity = activity()
        val root = Any()

        SoftInputModeLease.acquireResize(activity, root, conversationRoot = true)

        assertEquals(resize or LayoutParams.SOFT_INPUT_STATE_HIDDEN, activity.mode())
        SoftInputModeLease.release(activity, root)
        assertEquals(nothingHidden, activity.mode())
    }

    @Test
    fun `without a conversation the window gets back the mode it had`() {
        val activity = activity()
        val found = LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED or LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
        activity.window.setSoftInputMode(found)
        val page = Any()

        SoftInputModeLease.acquireResize(activity, page)
        assertEquals(resize, activity.adjust())
        SoftInputModeLease.release(activity, page)
        SoftInputModeLease.release(activity, page) // a second release changes nothing

        assertEquals(found, activity.mode())
    }

    @Test
    @Config(sdk = [33])
    fun `API 30 and later write nothing`() {
        val activity = activity()
        val before = activity.mode()

        SoftInputModeLease.acquireResize(activity, Any(), conversationRoot = true)

        assertEquals(before, activity.mode())
    }
}
