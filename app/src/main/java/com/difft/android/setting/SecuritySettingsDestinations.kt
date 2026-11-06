package com.difft.android.setting

import android.content.Context
import android.content.Intent
import com.difft.android.base.utils.pane.DetailDestination

object ScreenLockSettingDestination : DetailDestination {
    override fun newFragment() = ScreenLockSettingFragment()
    override fun shellIntent(context: Context) = Intent(context, ScreenLockSettingActivity::class.java)
}
object SetPasscodeTimeoutDestination : DetailDestination {
    override fun newFragment() = SetPasscodeTimeoutFragment()
    override fun shellIntent(context: Context) = Intent(context, SetPasscodeTimeoutActivity::class.java)
}
class SetCustomIdDestination(private val initialId: String) : DetailDestination {
    override fun newFragment() = SetCustomIdFragment.newInstance(initialId)
    override fun shellIntent(context: Context) = Intent(context, SetCustomIdActivity::class.java)
        .putExtra(SetCustomIdActivity.EXTRA_CONTACT_ID, initialId)
}
object DeleteAccountDestination : DetailDestination {
    override fun newFragment() = DeleteAccountFragment()
    override fun shellIntent(context: Context) = Intent(context, DeleteAccountActivity::class.java)
}
