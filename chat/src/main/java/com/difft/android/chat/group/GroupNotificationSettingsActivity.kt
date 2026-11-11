package com.difft.android.chat.group

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class GroupNotificationSettingsActivity : DetailShellActivity() {

    companion object {
        private const val KEY_GROUP_ID = "group_id"

        fun start(activity: Activity, groupId: String) {
            activity.startActivity(intent(activity, groupId))
        }

        fun intent(context: Context, groupId: String): Intent =
            Intent(context, GroupNotificationSettingsActivity::class.java).putExtra(KEY_GROUP_ID, groupId)
    }

    override fun createPage(): Fragment? =
        intent.getStringExtra(KEY_GROUP_ID)?.takeIf { it.isNotEmpty() }?.let { GroupNotificationSettingsFragment.newInstance(it) }
}
