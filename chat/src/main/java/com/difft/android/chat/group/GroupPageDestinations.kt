package com.difft.android.chat.group

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.chat.group.mvi.GroupPageContract.MemberAction
import com.difft.android.chat.group.mvi.GroupPageContract.ModeratorAction
import com.difft.android.chat.group.mvi.GroupPageContract.Page
import com.difft.android.chat.group.mvi.GroupPageViewModel

class GroupManagementDestination(private val gid: String) : DetailDestination {
    override val subjectId: String get() = gid
    override fun newFragment(): Fragment = GroupManagementFragment.newInstance(gid)
    override fun shellIntent(context: Context): Intent = GroupManagementActivity.intent(context, gid)
}

class GroupModeratorsDestination(private val gid: String, private val action: ModeratorAction) : DetailDestination {
    override val subjectId: String get() = gid
    override val pageKey: String get() = "group-moderators:$gid:$action"
    override fun newFragment(): Fragment = GroupModeratorsFragment.newInstance(gid, action)
    override fun shellIntent(context: Context): Intent = GroupModeratorsManagementActivity.intent(context, gid, action)
}

class GroupSelectMemberDestination(private val gid: String, private val type: MemberAction) : DetailDestination {
    override val subjectId: String get() = gid
    override val pageKey: String get() = "group-members:$gid:$type"
    override fun newFragment(): Fragment = GroupSelectMemberFragment.newInstance(gid, type)
    override fun shellIntent(context: Context): Intent = GroupSelectMemberActivity.intent(context, gid, type)
}

internal fun groupPageArguments(gid: String, page: Page, action: ModeratorAction = ModeratorAction.ADD,
    type: MemberAction = MemberAction.ADD) = Bundle().apply {
    putString(GroupPageViewModel.ARG_GID, gid)
    putString(GroupPageViewModel.ARG_PAGE, page.name)
    putString(GroupPageViewModel.ARG_ACTION, action.name)
    putString(GroupPageViewModel.ARG_TYPE, type.name)
}
