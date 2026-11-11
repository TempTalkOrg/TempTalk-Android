package com.difft.android.chat.group

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import com.difft.android.chat.group.create.CreateGroupFragment
import com.difft.android.chat.group.create.CreateGroupViewModel
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class CreateGroupActivity : DetailShellActivity() {
    override fun createPage(): Fragment =
        CreateGroupFragment.newInstance(intent.getStringArrayListExtra(CreateGroupViewModel.ARG_PRESELECTED).orEmpty())

    companion object {
        fun intent(context: Context, preselectedIds: List<String>): Intent =
            Intent(context, CreateGroupActivity::class.java)
                .putStringArrayListExtra(CreateGroupViewModel.ARG_PRESELECTED, ArrayList(preselectedIds))

        fun startActivity(activity: Activity, selectedIds: List<String>?) {
            activity.startActivity(intent(activity, selectedIds.orEmpty()))
        }
    }
}
