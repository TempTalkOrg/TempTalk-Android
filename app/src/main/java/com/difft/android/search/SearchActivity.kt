package com.difft.android.search

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

/** Full-window shell for [GlobalSearchFragment] (legacy `ActivityType.SEARCH` intents; the home screen opens the page as its search layer). */
@AndroidEntryPoint
class SearchActivity : DetailShellActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            activity.startActivity(Intent(activity, SearchActivity::class.java))
        }
    }

    override fun createPage(): Fragment = GlobalSearchFragment.newInstance()
}
