package com.difft.android.chat.group

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import com.difft.android.chat.group.mvi.GroupPageContract.MemberAction
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GroupSelectMemberActivity : DetailShellActivity() {
    override fun createPage(): Fragment? {
        val gid = intent.getStringExtra(EXTRA_GID)?.takeIf { it.isNotBlank() } ?: return null
        val type = when (intent.getIntExtra(EXTRA_TYPE, TYPE_REMOVE_MEMBER)) {
            TYPE_ADD_MEMBER -> MemberAction.ADD
            TYPE_REMOVE_MEMBER -> MemberAction.REMOVE
            else -> return null
        }
        
        return GroupSelectMemberFragment.newInstance(gid, type)
    }

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_GID = "extra_gid"
        const val EXTRA_SELECTED_MEMBER_IDS = "extra_selected_member_ids"
        const val TYPE_REMOVE_MEMBER = 0
        const val TYPE_ADD_MEMBER = 1
        fun intent(context: Context, gid: String, type: MemberAction): Intent =
            Intent(context, GroupSelectMemberActivity::class.java).putExtra(EXTRA_GID, gid)
                .putExtra(EXTRA_TYPE, if (type == MemberAction.ADD) TYPE_ADD_MEMBER else TYPE_REMOVE_MEMBER)
    }
}
