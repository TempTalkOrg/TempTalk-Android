package com.difft.android.chat.group

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

/** Full-window shell for [GroupMembersSearchFragment] (group info shell). */
@AndroidEntryPoint
class GroupMembersSearchActivity : DetailShellActivity() {

    companion object {
        fun intent(context: Context, groupId: String): Intent =
            Intent(context, GroupMembersSearchActivity::class.java).putExtras(GroupMembersSearchFragment.arguments(groupId))
    }

    override fun createPage(): Fragment? =
        intent.getStringExtra("groupId")?.takeIf { it.isNotEmpty() }?.let { GroupMembersSearchFragment.newInstance(it) }
}
