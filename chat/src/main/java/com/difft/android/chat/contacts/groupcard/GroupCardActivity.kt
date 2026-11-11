package com.difft.android.chat.contacts.groupcard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import com.difft.android.base.BaseActivity
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardViewModel
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class GroupCardActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gid = intent.getStringExtra(GroupCardViewModel.ARG_GROUP_ID).orEmpty()
        if (gid.isBlank()) {
            finish()
            return
        }
        setContentView(FrameLayout(this))
        
        
        showGroupCard(this, gid)
    }

    companion object {
        fun intent(context: Context, gid: String): Intent = Intent(context, GroupCardActivity::class.java)
            .putExtra(GroupCardViewModel.ARG_GROUP_ID, gid)
    }
}
