package com.difft.android.chat.ui

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class GroupInCommonActivity : DetailShellActivity() {

    companion object {
        fun intent(context: Context, contactId: String): Intent =
            Intent(context, GroupInCommonActivity::class.java).putExtras(GroupInCommonFragment.arguments(contactId))
    }

    override fun createPage(): Fragment? =
        intent.getStringExtra("id")?.takeIf { it.isNotEmpty() }?.let { GroupInCommonFragment.newInstance(it) }
}
