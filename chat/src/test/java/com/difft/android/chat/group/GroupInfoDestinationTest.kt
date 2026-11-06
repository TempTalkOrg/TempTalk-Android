package com.difft.android.chat.group

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.pane.detailNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Group info as a destination: in the home stack it is a [GroupInfoFragment] for the group;
 * outside it (group chat shell, popup) the push starts the [GroupInfoActivity] shell with
 * [KEY_GROUP_ID], as the Activity launch did before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GroupInfoDestinationTest {

    @Test
    fun `the destination's page is group info for that group`() {
        val page = GroupInfoDestination("g1").newFragment()

        assertTrue(page is GroupInfoFragment)
        assertEquals("g1", page.requireArguments().getString(KEY_GROUP_ID))
    }

    @Test
    fun `outside the home stack the push starts the shell Activity`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = Fragment()
        activity.supportFragmentManager.beginTransaction().add(page, "page").commitNow()

        page.detailNavigation().push(page, GroupInfoDestination("g1"))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GroupInfoActivity::class.java.name, started.component?.className)
        assertEquals("g1", started.getStringExtra(KEY_GROUP_ID))
    }
}
