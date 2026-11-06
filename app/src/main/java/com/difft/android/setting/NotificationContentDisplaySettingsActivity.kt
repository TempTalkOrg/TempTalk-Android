package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class NotificationContentDisplaySettingsActivity : DetailShellActivity() {
    override fun createPage(): Fragment = NotificationContentDisplaySettingsFragment()
    companion object {
        fun start(activity: Activity) { activity.startActivity(Intent(activity, NotificationContentDisplaySettingsActivity::class.java)) }
    }
}
