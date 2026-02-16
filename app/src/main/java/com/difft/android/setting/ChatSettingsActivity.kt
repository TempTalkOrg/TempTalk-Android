package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.commit
import com.difft.android.R
import com.difft.android.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activity container for ChatSettingsFragment
 * The actual UI logic is in ChatSettingsFragment for dual-pane support
 */
@AndroidEntryPoint
class ChatSettingsActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, ChatSettingsActivity::class.java)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_container)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, ChatSettingsFragment.newInstance())
            }
        }
    }
}