package com.difft.android.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsLayoutInflateTest {

    private fun inflate(layoutId: Int): View =
        LayoutInflater.from(ApplicationProvider.getApplicationContext()).inflate(layoutId, null)

    @Test
    fun singleChatSettingLayoutKeepsEveryBoundId() {
        val root = inflate(R.layout.chat_activity_setting)

        listOf(
            R.id.ll_search_chat_history,
            R.id.rel_group_in_common,
            R.id.switch_stick,
            R.id.cl_mute,
            R.id.save_to_photos_container,
            R.id.translate_container,
            R.id.translate_text,
            R.id.disappearing_time_container,
            R.id.rel_notification_sound,
            R.id.cl_block,
            R.id.rel_remove,
        ).forEach { id -> assertNotNull("missing id $id", root.findViewById<View>(id)) }
    }

    @Test
    fun groupInfoLayoutKeepsEveryBoundId() {
        val root = inflate(R.layout.chat_activity_group_info)

        listOf(
            R.id.member_info_container,
            R.id.rel_view_all,
            R.id.ll_search_chat_history,
            R.id.rel_group_management,
            R.id.switch_stick,
            
            R.id.compose_notification_section,
            R.id.save_to_photos_container,
            R.id.translate_container,
            R.id.translate_text,
            R.id.disappearing_time_container,
            R.id.leave_container,
        ).forEach { id -> assertNotNull("missing id $id", root.findViewById<View>(id)) }
    }

    @Test
    fun groupManagementLayoutKeepsEveryBoundIdInsideTheNewContainers() {
        val root = inflate(R.layout.activity_group_management)

        listOf(
            R.id.cl_moderators,
            R.id.rv_moderators,
            R.id.cl_transfer_owner,
            R.id.switch_can_add,
            R.id.switch_can_speak,
            R.id.switch_can_critical_alert,
        ).forEach { id -> assertNotNull("missing id $id", root.findViewById<View>(id)) }

        
        assertNotNull("missing compose title bar", root.findViewById<View>(R.id.compose_title_bar))
        assertNull("ib_back should be gone", root.findViewById<View>(R.id.ib_back))
        assertNull("tv_title should be gone", root.findViewById<View>(R.id.tv_title))

        
        val moderators = root.findViewById<View>(R.id.cl_moderators)
        val group = moderators.parent as ViewGroup
        assertTrue("group container should be a LinearLayout", group is LinearLayout)
        assertEquals(
            "moderators group should hold row + grid + transfer row",
            3,
            group.childCount,
        )
        assertEquals(
            "the critical-alert switch should sit in its own group",
            1,
            (root.findViewById<View>(R.id.switch_can_critical_alert).parent as ViewGroup).childCount,
        )
    }
}
