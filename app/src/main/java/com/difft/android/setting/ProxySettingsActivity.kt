package com.difft.android.setting

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ProxySettingsActivity : DetailShellActivity() {
    override fun createPage(): Fragment = ProxySettingsFragment()
    companion object {
        fun startActivity(activity: Context) { activity.startActivity(Intent(activity, ProxySettingsActivity::class.java)) }
    }
}
