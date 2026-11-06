package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SetPasscodeTimeoutActivity : DetailShellActivity() {
    companion object {
        fun startActivity(activity: Activity) { activity.startActivity(Intent(activity, SetPasscodeTimeoutActivity::class.java)) }
    }
    override fun createPage(): Fragment = SetPasscodeTimeoutFragment()
}
