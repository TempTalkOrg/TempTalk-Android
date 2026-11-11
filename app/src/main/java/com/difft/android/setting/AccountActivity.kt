package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.commit
import com.difft.android.R
import com.difft.android.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint





@AndroidEntryPoint
class AccountActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, AccountActivity::class.java)
            activity.startActivity(intent)
        }
    }

    
    override fun participatesInSideChrome(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_container)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, AccountFragment.newInstance())
            }
        }
    }
}
