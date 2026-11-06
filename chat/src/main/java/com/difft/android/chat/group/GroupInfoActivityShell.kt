package com.difft.android.chat.group

import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-window shell for [GroupInfoFragment], used where the page is not pushed onto the home
 * screen's detail stack (group chat shell, popups). Launched with [KEY_GROUP_ID].
 */
@AndroidEntryPoint
class GroupInfoActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val groupId = intent.getStringExtra(KEY_GROUP_ID).orEmpty()
        if (groupId.isEmpty()) {
            L.w { "[GroupInfo] shell started without a group id" }
            finish()
            return
        }
        setContentView(R.layout.chat_activity_group_info_shell)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container_group_info, GroupInfoFragment.newInstance(groupId))
                .commit()
        }
    }
}
