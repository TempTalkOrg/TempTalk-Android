package com.difft.android.setting

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CallNotificationSettingsActivity : DetailShellActivity() {
    override fun createPage(): Fragment = CallNotificationSettingsFragment()
}

object CallNotificationSettingsDestination : DetailDestination {
    override fun newFragment(): Fragment = CallNotificationSettingsFragment()
    override fun shellIntent(context: Context): Intent = Intent(context, CallNotificationSettingsActivity::class.java)
}

@AndroidEntryPoint
class CriticalAlertSettingsActivity : DetailShellActivity() {
    override fun createPage(): Fragment = CriticalAlertSettingsFragment()
}

object CriticalAlertSettingsDestination : DetailDestination {
    override fun newFragment(): Fragment = CriticalAlertSettingsFragment()
    override fun shellIntent(context: Context): Intent = Intent(context, CriticalAlertSettingsActivity::class.java)
}
