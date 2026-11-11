package com.difft.android.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class SearchMoreActivity : DetailShellActivity() {

    companion object {
        const val SEARCH_TYPE_CONTACT = 1
        const val SEARCH_TYPE_GROUP = 2

        fun startActivity(activity: Activity, type: Int, key: String) {
            activity.startActivity(intent(activity, type, key))
        }

        fun intent(context: Context, type: Int, key: String): Intent =
            Intent(context, SearchMoreActivity::class.java).putExtras(SearchMoreFragment.arguments(type, key))
    }

    override fun createPage(): Fragment? =
        intent.extras?.takeIf { it.getInt("type", -1) in SEARCH_TYPE_CONTACT..SEARCH_TYPE_GROUP }?.let { SearchMoreFragment.newInstance(it) }
}
