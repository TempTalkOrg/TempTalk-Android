package com.difft.android.chat.group

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint





@AndroidEntryPoint
class GroupGlobalNotificationSettingsActivity : DetailShellActivity() {

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(intent(activity))
        }

        fun intent(context: Context): Intent = Intent(context, GroupGlobalNotificationSettingsActivity::class.java)
    }

    override fun createPage(): Fragment = GroupGlobalNotificationSettingsFragment.newInstance()
}
