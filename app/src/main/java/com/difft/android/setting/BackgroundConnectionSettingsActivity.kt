package com.difft.android.setting

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BackgroundConnectionSettingsActivity : DetailShellActivity() {
    override fun createPage(): Fragment = BackgroundConnectionSettingsFragment()

    companion object {
        fun startActivity(activity: Activity) {
            activity.startActivity(Intent(activity, BackgroundConnectionSettingsActivity::class.java))
        }
    }
}

object BackgroundConnectionSettingsDestination : DetailDestination {
    override fun newFragment(): Fragment = BackgroundConnectionSettingsFragment()
    override fun shellIntent(context: Context): Intent = Intent(context, BackgroundConnectionSettingsActivity::class.java)
}
