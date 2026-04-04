package com.difft.android.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ContactProfileSettingActivity : BaseActivity() {

    companion object {
        private const val BUNDLE_KEY_FROM = "BUNDLE_KEY_FROM"

        const val BUNDLE_VALUE_FROM_REGISTER = ContactProfileSettingFragment.FROM_REGISTER
        const val BUNDLE_VALUE_FROM_CONTACT = ContactProfileSettingFragment.FROM_CONTACT
        const val BUNDLE_VALUE_FROM_SIGN_UP = ContactProfileSettingFragment.FROM_SIGN_UP

        fun startActivity(activity: Activity, from: Int) {
            val intent = Intent(activity, ContactProfileSettingActivity::class.java)
            intent.from = from
            activity.startActivity(intent)
        }

        private var Intent.from: Int
            get() = getIntExtra(BUNDLE_KEY_FROM, -1)
            set(value) {
                putExtra(BUNDLE_KEY_FROM, value)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_container)

        if (savedInstanceState == null) {
            val from = intent.from
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragment_container,
                    ContactProfileSettingFragment.newInstance(from),
                    ContactProfileSettingFragment::class.java.simpleName
                )
                .commit()
        }
    }
}
