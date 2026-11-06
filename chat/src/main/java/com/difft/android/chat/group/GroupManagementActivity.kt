package com.difft.android.chat.group

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

/** Compatibility shell; the retained page owns all group state and writes. */
@AndroidEntryPoint
class GroupManagementActivity : DetailShellActivity() {
    override fun createPage(): Fragment? = intent.getStringExtra(KEY_GID)?.takeIf { it.isNotBlank() }
        ?.let { GroupManagementFragment.newInstance(it) }

    companion object {
        private const val KEY_GID = "groupId"
        fun intent(context: Context, groupId: String): Intent = Intent(context, GroupManagementActivity::class.java)
            .putExtra(KEY_GID, groupId)
        fun startActivity(activity: Activity, groupId: String) = activity.startActivity(intent(activity, groupId))
    }
}
