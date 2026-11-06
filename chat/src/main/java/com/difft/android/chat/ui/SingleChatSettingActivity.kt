package com.difft.android.chat.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-window shell for [SingleChatSettingFragment], used where the page is not pushed onto the
 * home screen's detail stack (chat shells, popups, ContactDetailActivity).
 */
@AndroidEntryPoint
class SingleChatSettingActivity : BaseActivity() {

    companion object {
        private const val EXTRA_ID = "id"

        fun startActivity(activity: Activity, contactId: String) {
            activity.startActivity(intent(activity, contactId))
        }

        fun intent(context: Context, contactId: String): Intent =
            Intent(context, SingleChatSettingActivity::class.java).putExtra(EXTRA_ID, contactId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contactId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (contactId.isEmpty()) {
            L.w { "[SingleChatSetting] shell started without a contact id" }
            finish()
            return
        }
        setContentView(R.layout.chat_activity_single_chat_setting)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container_single_chat_setting, SingleChatSettingFragment.newInstance(contactId))
                .commit()
        }
    }
}
