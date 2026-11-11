package com.difft.android.chat.focus.settings

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class FocusGroupPickerShellActivity : DetailShellActivity() {
    override fun createPage(): Fragment = FocusGroupPickerFragment()
}


object FocusGroupPickerDestination : DetailDestination {
    override fun newFragment(): Fragment = FocusGroupPickerFragment()
    override fun shellIntent(context: Context): Intent = Intent(context, FocusGroupPickerShellActivity::class.java)
}
