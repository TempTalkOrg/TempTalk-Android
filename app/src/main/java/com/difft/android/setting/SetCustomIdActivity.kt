package com.difft.android.setting

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SetCustomIdActivity : DetailShellActivity() {
    companion object {
        const val EXTRA_CONTACT_ID = "EXTRA_CONTACT_ID"
        fun startActivity(activity: Context, contactId: String) {
            activity.startActivity(Intent(activity, SetCustomIdActivity::class.java).putExtra(EXTRA_CONTACT_ID, contactId))
        }
    }
    override fun createPage(): Fragment = SetCustomIdFragment.newInstance(intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty())
}
