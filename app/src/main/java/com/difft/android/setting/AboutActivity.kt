package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.commit
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.network.UrlManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Activity container for AboutFragment
 * The actual UI logic is in AboutFragment for dual-pane support
 */
@AndroidEntryPoint
class AboutActivity : BaseActivity() {
    @Inject
    lateinit var updateManager: UpdateManager

    @Inject
    lateinit var urlManager: UrlManager

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, AboutActivity::class.java)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_container)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, AboutFragment.newInstance())
            }
        }
    }
}