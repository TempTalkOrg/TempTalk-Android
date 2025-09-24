package com.difft.android.chat.group

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatActivityGroupInviteBinding
import com.difft.android.chat.ui.SelectChatsUtils
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.util.Util
import javax.inject.Inject

@AndroidEntryPoint
class GroupInviteActivity : BaseActivity() {

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    companion object {
        fun startActivity(activity: Activity, inviteCode: String, myName: String, groupName: String) {
            val intent = Intent(activity, GroupInviteActivity::class.java)
            intent.putExtra("inviteCode", inviteCode)
            intent.putExtra("myName", myName)
            intent.putExtra("groupName", groupName)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ChatActivityGroupInviteBinding by viewbind()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        val inviteCode = intent.getStringExtra("inviteCode")
        val myName = intent.getStringExtra("myName")
        val groupName = intent.getStringExtra("groupName")

        val shareContent = getString(R.string.group_share_content, myName, groupName, inviteCode)

        mBinding.textviewGroupName.text = groupName
        mBinding.tvShareContent.text = shareContent

        mBinding.btnCopy.setOnClickListener {
            Util.copyToClipboard(this, shareContent)
        }

        mBinding.ibShare.setOnClickListener {
            selectChatsUtils.showChatSelectAndSendDialog(this, shareContent)
        }
    }
}