package com.difft.android.chat.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

/** Full-window shell for [SearchMessageFragment] (global search, chat and group-info shells). */
@AndroidEntryPoint
class SearchMessageActivity : DetailShellActivity() {

    companion object {
        fun startActivity(activity: Activity, conversationId: String, isGroup: Boolean, key: String?) {
            activity.startActivity(intent(activity, conversationId, isGroup, key))
        }

        fun intent(context: Context, conversationId: String, isGroup: Boolean, key: String?): Intent =
            Intent(context, SearchMessageActivity::class.java).putExtras(SearchMessageFragment.arguments(conversationId, isGroup, key))
    }

    override fun createPage(): Fragment? =
        intent.extras?.takeIf { !it.getString("conversationId").isNullOrEmpty() }?.let { SearchMessageFragment.newInstance(it) }
}
