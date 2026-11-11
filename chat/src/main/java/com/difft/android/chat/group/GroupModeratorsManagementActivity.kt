package com.difft.android.chat.group

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import com.difft.android.chat.group.mvi.GroupPageContract.ModeratorAction
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GroupModeratorsManagementActivity : DetailShellActivity() {
    override fun createPage(): Fragment? {
        val gid = intent.getStringExtra(KEY_GID)?.takeIf { it.isNotBlank() } ?: return null
        val action = ActionType.entries.firstOrNull { it.action == intent.getStringExtra(KEY_ACTION) } ?: return null
        return GroupModeratorsFragment.newInstance(gid, ModeratorAction.valueOf(action.name))
    }

    companion object {
        private const val KEY_GID = "groupId"
        private const val KEY_ACTION = "actionType"
        fun intent(context: Context, gid: String, action: ModeratorAction): Intent =
            Intent(context, GroupModeratorsManagementActivity::class.java).putExtra(KEY_GID, gid)
                .putExtra(KEY_ACTION, ActionType.valueOf(action.name).action)
        fun startActivity(activity: Activity, groupId: String, actionType: String) =
            activity.startActivity(intent(activity, groupId, ModeratorAction.valueOf(ActionType.fromAction(actionType).name)))
    }
}


enum class ActionType(val action: String) {
    ADD("add"), REMOVE("remove"), TRANSFER("transfer");
    companion object {
        fun fromAction(action: String): ActionType = entries.firstOrNull { it.action == action }
            ?: throw IllegalArgumentException("Unknown action type: $action")
    }
    fun isAdd() = this == ADD
    fun isRemove() = this == REMOVE
    fun isTransfer() = this == TRANSFER
}
