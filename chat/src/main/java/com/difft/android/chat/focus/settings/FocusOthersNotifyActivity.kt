package com.difft.android.chat.focus.settings

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class FocusOthersNotifyActivity : DetailShellActivity() {
    override fun createPage(): Fragment = FocusOthersNotifyFragment()
}


object FocusOthersNotifyDestination : DetailDestination {
    override fun newFragment(): Fragment = FocusOthersNotifyFragment()
    override fun shellIntent(context: Context): Intent = Intent(context, FocusOthersNotifyActivity::class.java)
}
