package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VoiceChangerSettingsActivity : DetailShellActivity() {
    override fun createPage(): Fragment = VoiceChangerSettingsFragment()
    companion object {
        fun startActivity(activity: Activity) { activity.startActivity(Intent(activity, VoiceChangerSettingsActivity::class.java)) }
    }
}
