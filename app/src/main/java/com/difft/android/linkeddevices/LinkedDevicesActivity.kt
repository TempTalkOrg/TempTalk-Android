package com.difft.android.linkeddevices

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.commit
import com.difft.android.R
import com.difft.android.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint






@AndroidEntryPoint
class LinkedDevicesActivity : BaseActivity() {

    companion object {
        fun startActivity(context: Context) =
            context.startActivity(Intent(context, LinkedDevicesActivity::class.java))
    }

    
    override fun participatesInSideChrome(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_container)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, LinkedDevicesFragment.newInstance())
            }
        }
    }
}
