package com.difft.android.chat.ui

import android.app.Application
import androidx.activity.ComponentActivity
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
 * The 1:1 chat settings destination: in the home stack it becomes a [SingleChatSettingFragment]
 * for the contact; outside it (chat shell, popup, contact card Activity) the push starts the
 * [SingleChatSettingActivity] shell with today's "id" extra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SingleChatSettingDestinationTest {

    @Test
    fun `the destination's page is the settings fragment for that contact`() {
        val page = SingleChatSettingDestination("u1").newFragment()

        assertTrue(page is SingleChatSettingFragment)
        assertEquals("u1", page.requireArguments().getString("id"))
    }

    @Test
    fun `outside the home stack the push starts the shell Activity`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = Fragment()
        activity.supportFragmentManager.beginTransaction().add(page, "page").commitNow()

        page.detailNavigation().push(page, SingleChatSettingDestination("u1"))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(SingleChatSettingActivity::class.java.name, started.component?.className)
        assertEquals("u1", started.getStringExtra("id"))
    }

    @Test
    fun `the shell intent is what the old startActivity built`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        SingleChatSettingActivity.startActivity(activity, "u2")

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(SingleChatSettingActivity::class.java.name, started.component?.className)
        assertEquals("u2", started.getStringExtra("id"))
    }
}
